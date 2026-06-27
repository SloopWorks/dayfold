import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.HOUSEHOLD_SECRET = "test-secret-123";
process.env.HOUSEHOLD_CREDENTIAL_ID = "hcred";

// import AFTER env is set (db pool reads DATABASE_URL on import)
const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");

const AUTH = { authorization: "Bearer test-secret-123" };
const card = (over = {}) => ({ kind: "info", title: "Party Sat",
  provenance: { source: "claude", at: "2026-06-18T10:00:00Z" }, ...over });

async function put(fid: string, id: string, body: any, headers = AUTH) {
  return app.request(`/families/${fid}/cards/${id}`, {
    method: "PUT", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body) });
}

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql", "0002_auth.sql", "0006_typed_content.sql", "0007_related.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql"]) // typed-card + related cols
    await q(readFileSync(resolve(here, "../migrations/" + m), "utf8"));
  await q(`INSERT INTO families(id,name) VALUES ('fam1','Test')`);
  await q(`INSERT INTO credentials(id,kind,family_scope,scopes) VALUES ('hcred','cli','fam1','{content:read,content:write}')`);
  await q(`INSERT INTO credential_grants(credential_id,scope) VALUES ('hcred','content:read'),('hcred','content:write')`);
});
afterAll(async () => { await pool.end(); });

describe("M0 content API — card round-trip + security (vs live Postgres)", () => {
  it("PUT (auth) → 200 version 1; re-PUT → version 2 (idempotent)", async () => {
    const r1 = await put("fam1", "c1", card());
    expect(r1.status).toBe(200);
    expect((await r1.json()).version).toBe("1");
    const r2 = await put("fam1", "c1", card({ title: "Party (edited)" }));
    expect((await r2.json()).version).toBe("2");
  });

  it("GET cards + GET sync return the card", async () => {
    const cards = await (await app.request("/families/fam1/cards", { headers: AUTH })).json();
    expect(cards.some((c: any) => c.id === "c1")).toBe(true);
    const sync = await (await app.request("/families/fam1/sync", { headers: AUTH })).json();
    expect(sync.changes.cards.some((c: any) => c.id === "c1")).toBe(true);
  });

  it("DELETE → 204; sync surfaces a tombstone (trigger bumped updated_at)", async () => {
    await put("fam1", "c2", card({ title: "to delete" }));
    const del = await app.request("/families/fam1/cards/c2", { method: "DELETE", headers: AUTH });
    expect(del.status).toBe(204);
    const sync = await (await app.request("/families/fam1/sync", { headers: AUTH })).json();
    expect(sync.tombstones.some((t: any) => t.id === "c2" && t.type === "card")).toBe(true);
  });

  it("bad token → 401", async () => {
    expect((await put("fam1", "c3", card(), { authorization: "Bearer WRONG" })).status).toBe(401);
    expect((await app.request("/families/fam1/cards")).status).toBe(401);
  });

  it("cross-tenant → 404 (not 403, no enumeration)", async () => {
    expect((await app.request("/families/OTHER/cards", { headers: AUTH })).status).toBe(404);
    expect((await put("OTHER", "c4", card())).status).toBe(404);
  });

  it("mass-assignment: body family_id/version ignored", async () => {
    const r = await put("fam1", "c5", card({ family_id: "EVIL", version: 999 } as any));
    const row = await r.json();
    expect(row.family_id).toBe("fam1");   // from path, not body
    expect(row.version).toBe("1");         // server-bumped, not 999
  });

  it("provenance credential_id is stamped, not client-forged", async () => {
    const r = await put("fam1", "c6", card({ provenance: { source: "claude", at: "2026-06-18T10:00:00Z", credential_id: "FORGED" } }));
    const row = await r.json();
    expect(row.provenance.credential_id).toBe("hcred");
  });

  it("[F4] malformed sync cursor → 400 (not a silent full re-scan)", async () => {
    const r = await app.request("/families/fam1/sync?since=not-a-cursor", { headers: AUTH });
    expect(r.status).toBe(400);
  });

  it("[F3] sync cursor round-trips (next_cursor → no duplicate/skip)", async () => {
    const first = await (await app.request("/families/fam1/sync", { headers: AUTH })).json();
    expect(first.changes.cards.length).toBeGreaterThan(0);
    const again = await (await app.request(`/families/fam1/sync?since=${encodeURIComponent(first.next_cursor)}`, { headers: AUTH })).json();
    expect(again.changes.cards.length).toBe(0); // nothing new past the cursor
  });

  it("[F8] body over 1 MB → 413", async () => {
    const big = "x".repeat(1_100_000);
    const r = await put("fam1", "c7", card({ body_md: big }));
    expect(r.status).toBe(413);
  });
});
