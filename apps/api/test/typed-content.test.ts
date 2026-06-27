// CL-2 — typed content storage end-to-end vs live Postgres (extend
// briefing_cards in place, ADR 0022 D2). Applies 0001 + 0005 only (household-
// token path; no auth migrations). Mirrors the api.test.ts harness.
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.HOUSEHOLD_SECRET = "test-secret-123";
process.env.HOUSEHOLD_CREDENTIAL_ID = "hcred";

const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");

const AUTH = { authorization: "Bearer test-secret-123" };
const prov = { source: "claude", at: "2026-06-20T10:00:00Z" };

async function put(fid: string, id: string, body: any, headers = AUTH) {
  return app.request(`/families/${fid}/cards/${id}`, {
    method: "PUT", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body) });
}
const get = (path: string, headers = AUTH) => app.request(path, { headers });

// One representative card per type (subset of CL-1 example fields).
const TYPED: Record<string, any> = {
  file:    { file: { filename: "permission.pdf", mime: "application/pdf", size: 240000, pages: 2 } },
  link:    { link: { url: "https://school.example/form", domain: "school.example", kind: "form", fieldCount: 4 } },
  invite:  { invite: { eventName: "Maya's party", host: "The Garcias", rsvpState: "none", guestCount: 12 } },
  contact: { contact: { name: "Dr. Lee", role: "Pediatrician", phone: "+15551234567" } },
  geo:     { geo: { label: "Soccer field", lat: 37.42, lng: -122.08, etaMin: 14, travelMode: "driving" } },
  email:   { email: { from: "Coach", subject: "Practice moved", date: "2026-06-20T09:00:00Z", threadLen: 3 } },
};
const typedCard = (type: string, over: any = {}) =>
  ({ kind: "action", title: `${type} card`, provenance: prov, type, payload: TYPED[type], ...over });

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql", "0002_auth.sql", "0006_typed_content.sql", "0007_related.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql"])
    await q(readFileSync(resolve(here, "../migrations/" + m), "utf8"));
  await q(`INSERT INTO families(id,name) VALUES ('famA','A'),('famB','B')`);
  await q(`INSERT INTO credentials(id,kind,family_scope,scopes) VALUES ('hcred','cli','famA','{content:read,content:write}')`);
  await q(`INSERT INTO credential_grants(credential_id,scope) VALUES ('hcred','content:read'),('hcred','content:write')`);
});
afterAll(async () => { await pool.end(); });

describe("CL-2 typed content storage (vs live Postgres)", () => {
  it("all 6 types upsert → 200 and round-trip payload/type/privacy/hub_ref via GET + sync", async () => {
    for (const type of Object.keys(TYPED)) {
      const id = `c_${type}`;
      const r = await put("famA", id, typedCard(type, { privacy: { storage: "on_device" }, hubRef: "hub1" }));
      expect(r.status, `${type} upsert`).toBe(200);
      const row = await r.json();
      expect(row.type).toBe(type);
      // pg auto-parses jsonb → object (not string)
      expect(typeof row.payload).toBe("object");
      expect(row.payload).toEqual(TYPED[type]);
      expect(row.privacy).toEqual({ storage: "on_device" });
      expect(row.hub_ref).toBe("hub1");
    }
    const cards = await (await get("/families/famA/cards")).json();
    expect(cards.length).toBe(6);
    const file = cards.find((c: any) => c.id === "c_file");
    expect(file.payload.file.filename).toBe("permission.pdf");

    const sync = await (await get("/families/famA/sync")).json();
    const sInvite = sync.changes.cards.find((c: any) => c.id === "c_invite");
    expect(sInvite.type).toBe("invite");
    expect(sInvite.payload.invite.eventName).toBe("Maya's party");
  });

  it("type↔payload variant mismatch → 422", async () => {
    const r = await put("famA", "bad1", { kind: "info", title: "x", provenance: prov,
      type: "file", payload: { invite: { eventName: "nope" } } });
    expect(r.status).toBe(422);
    expect((await r.json()).type).toBe("validation");
  });

  it("payload without type → 422; type without payload → 422", async () => {
    const noType = await put("famA", "bad2", { kind: "info", title: "x", provenance: prov,
      payload: { file: { filename: "a.pdf" } } });
    expect(noType.status).toBe(422);
    const noPayload = await put("famA", "bad3", { kind: "info", title: "x", provenance: prov, type: "file" });
    expect(noPayload.status).toBe(422);
  });

  it("legacy kind-only card (no type/payload) still → 200 (back-compat)", async () => {
    const r = await put("famA", "legacy1", { kind: "info", title: "plain", provenance: prov });
    expect(r.status).toBe(200);
    const row = await r.json();
    expect(row.type).toBeNull();
    expect(row.payload).toBeNull();
  });

  it("typed card soft-delete → tombstone surfaces in sync", async () => {
    await put("famA", "del1", typedCard("link"));
    const del = await app.request("/families/famA/cards/del1", { method: "DELETE", headers: AUTH });
    expect(del.status).toBe(204);
    const sync = await (await get("/families/famA/sync")).json();
    expect(sync.tombstones.some((t: any) => t.id === "del1" && t.type === "card")).toBe(true);
    expect(sync.changes.cards.some((c: any) => c.id === "del1")).toBe(false);
  });

  it("tenancy: famA typed card never appears in famB list/sync (IDOR)", async () => {
    // famB has no credential here; assert via direct famB cred would need setup —
    // instead assert the household cred (scoped to famA) is cross-tenant-blocked on famB.
    expect((await get("/families/famB/cards")).status).toBe(404);
    expect((await get("/families/famB/sync")).status).toBe(404);
    // and famA's data is only under famA
    const cards = await (await get("/families/famA/cards")).json();
    expect(cards.every((c: any) => c.id.startsWith("c_") || c.id === "legacy1")).toBe(true);
  });

  it("cursor stability: re-sync from next_cursor returns no dupes", async () => {
    const first = await (await get("/families/famA/sync")).json();
    expect(first.next_cursor).toBeTruthy();
    const second = await (await get(`/families/famA/sync?since=${encodeURIComponent(first.next_cursor)}`)).json();
    expect(second.changes.cards.length).toBe(0);
    expect(second.tombstones.length).toBe(0);
  });

  // ── CL-8 related-edges ────────────────────────────────────────────────────

  it("related[] + relatedKicker round-trip (incl. attachment↔email edge)", async () => {
    const r = await put("famA", "email1", typedCard("email", {
      relatedKicker: "FROM THE SAME EMAIL",
      related: [
        { relation: "attachment", targetId: "c_file", targetType: "file", title: "permission.pdf", sub: "240 KB" },
        { relation: "same-hub", targetId: "c_invite", targetType: "invite", title: "Maya's party" },
      ],
    }));
    expect(r.status).toBe(200);
    const row = await r.json();
    expect(row.related_kicker).toBe("FROM THE SAME EMAIL");
    expect(Array.isArray(row.related)).toBe(true);
    expect(row.related[0]).toEqual({ relation: "attachment", targetId: "c_file", targetType: "file", title: "permission.pdf", sub: "240 KB" });

    const sync = await (await get("/families/famA/sync")).json();
    const synced = sync.changes.cards.find((c: any) => c.id === "email1");
    expect(synced.related.length).toBe(2);
    expect(synced.related.find((e: any) => e.relation === "attachment").targetId).toBe("c_file");
  });

  it("a malformed related edge is rejected (422)", async () => {
    // missing required targetId
    const bad = await put("famA", "bad_rel", typedCard("email", {
      related: [{ relation: "attachment", targetType: "file" }],
    }));
    expect(bad.status).toBe(422);
    // unknown targetType enum value
    const badEnum = await put("famA", "bad_rel2", typedCard("email", {
      related: [{ relation: "x", targetId: "t", targetType: "spaceship" }],
    }));
    expect(badEnum.status).toBe(422);
  });
});
