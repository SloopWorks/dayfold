// ADR 0064 — the response endpoints. The security-shaped assertions here are the point:
// ownership is assigned from the token (never the body), the identity columns are immutable
// after creation, and a personal rule belongs to exactly one member.
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { generateKeyPair, exportJWK } from "jose";
import { applyAllMigrations } from "./_migrations.ts";
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.AUTH_ISS = "https://fad.test/auth"; process.env.AUTH_AUD = "fad-api-test";
process.env.ENABLE_DEV_AUTH = "1"; process.env.DEV_AUTH_SECRET = "dev"; delete process.env.VERCEL_ENV;
const kp = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
const priv = await exportJWK(kp.privateKey); priv.kid = "k1"; priv.alg = "EdDSA";
process.env.AUTH_SIGNING_KEY = JSON.stringify(priv);
const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");

beforeAll(async () => { await applyAllMigrations(q); });
afterAll(async () => { await pool.end(); });

const dev = { "content-type": "application/json", authorization: "Bearer dev" };
async function ownerOf(uid: string) {
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  const fam = await (await app.request("/families", { method: "POST", headers: { ...dev, authorization: `Bearer ${t}` }, body: JSON.stringify({ name: uid }) })).json();
  const me = await (await app.request("/auth/me", { headers: { authorization: `Bearer ${t}` } })).json();
  return { token: t as string, familyId: fam.familyId as string, userId: me.user_id as string };
}
async function memberOf(uid: string, familyId: string) {
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  const me = await (await app.request("/auth/me", { headers: { authorization: `Bearer ${t}` } })).json();
  await q(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ($1,$2,'adult','active')`, [me.user_id, familyId]);
  return { token: t as string, userId: me.user_id as string };
}
const authH = (tok: string) => ({ "content-type": "application/json", authorization: `Bearer ${tok}` });
const putResp = (fid: string, id: string, tok: string, body: any, opId?: string) =>
  app.request(`/families/${fid}/responses/${id}`, {
    method: "PUT",
    headers: { ...authH(tok), ...(opId ? { "idempotency-key": opId } : {}) },
    body: JSON.stringify(body),
  });
const delResp = (fid: string, id: string, tok: string, opId?: string) =>
  app.request(`/families/${fid}/responses/${id}`, {
    method: "DELETE",
    headers: { ...authH(tok), ...(opId ? { "idempotency-key": opId } : {}) },
  });

const mute = (over: any = {}) => ({
  kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
  audience_scope: "personal", label: "Weather cards", sublabel: "From Morning briefing", ...over,
});

describe("response endpoints (ADR 0064)", () => {
  it("creates a personal mute owned by the caller", async () => {
    const o = await ownerOf("resp-1");
    const res = await putResp(o.familyId, "r_1", o.token, mute());
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.audience_scope).toBe("personal");
    expect(body.user_id).toBe(o.userId);
    expect(body.created_by).toBe(o.userId);
    expect(body.version).toBe("1");
  });

  // Ownership comes from the token. If a body could set it, one member could write rules
  // that suppress content for another member.
  it("ignores a client-supplied user_id", async () => {
    const o = await ownerOf("resp-2");
    const res = await putResp(o.familyId, "r_2", o.token, mute({ user_id: "someone_else" }));
    expect((await res.json()).user_id).toBe(o.userId);
  });

  it("creates a family-wide mute with no owner, still attributed", async () => {
    const o = await ownerOf("resp-3");
    const res = await putResp(o.familyId, "r_3", o.token, mute({ audience_scope: "family" }));
    const body = await res.json();
    expect(body.user_id).toBeNull();
    expect(body.created_by).toBe(o.userId);
  });

  it("is idempotent under a replayed op id", async () => {
    const o = await ownerOf("resp-4");
    const a = await putResp(o.familyId, "r_4", o.token, mute(), "op_abc");
    const b = await putResp(o.familyId, "r_4", o.token, mute(), "op_abc");
    expect((await a.json()).version).toBe((await b.json()).version);
  });

  // Editing a rule's identity would let a member widen their own personal rule to the whole
  // family, or re-attribute someone else's. Editable fields are the display strings only.
  it("does not let a re-PUT widen a personal rule to family-wide", async () => {
    const o = await ownerOf("resp-5");
    await putResp(o.familyId, "r_5", o.token, mute());
    const res = await putResp(o.familyId, "r_5", o.token, mute({ audience_scope: "family", label: "Renamed" }));
    const body = await res.json();
    expect(body.audience_scope).toBe("personal");
    expect(body.user_id).toBe(o.userId);
    expect(body.label).toBe("Renamed"); // display strings DO update
  });

  it("rejects a done row that is not family/subject shaped", async () => {
    const o = await ownerOf("resp-6");
    const res = await putResp(o.familyId, "r_6", o.token, {
      kind: "done", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "personal", label: "nope",
    });
    expect(res.status).toBe(422);
  });

  it("rejects a class ref carrying a subject match scope, and vice versa", async () => {
    const o = await ownerOf("resp-7");
    expect((await putResp(o.familyId, "r_7a", o.token, mute({ subject_ref: "kind:weather", match_scope: "subject" }))).status).toBe(422);
    expect((await putResp(o.familyId, "r_7b", o.token, mute({ subject_ref: "card:c1", match_scope: "kind" }))).status).toBe(422);
  });

  it("rejects a malformed body", async () => {
    const o = await ownerOf("resp-8");
    expect((await putResp(o.familyId, "r_8a", o.token, { kind: "mute" })).status).toBe(422);
    expect((await putResp(o.familyId, "r_8b", o.token, mute({ match_scope: "vibes" }))).status).toBe(422);
  });

  it("soft-deletes, and a re-delete is idempotent", async () => {
    const o = await ownerOf("resp-9");
    await putResp(o.familyId, "r_9", o.token, mute());
    expect((await delResp(o.familyId, "r_9", o.token)).status).toBe(204);
    expect((await delResp(o.familyId, "r_9", o.token)).status).toBe(204);
    const row = await q(`SELECT deleted_at FROM content_responses WHERE family_id=$1 AND id=$2`, [o.familyId, "r_9"]);
    expect(row.rows[0].deleted_at).not.toBeNull();
  });

  it("deleting an id that never existed is still 204", async () => {
    const o = await ownerOf("resp-10");
    expect((await delResp(o.familyId, "r_nope", o.token)).status).toBe(204);
  });

  // Decided Q2: any adult removes a FAMILY rule. A personal rule is its owner's alone —
  // otherwise one member could un-mute content for another.
  it("any member may remove a family rule, but not another member's personal rule", async () => {
    const o = await ownerOf("resp-11");
    const m = await memberOf("resp-11-b", o.familyId);
    await putResp(o.familyId, "r_fam", o.token, mute({ audience_scope: "family" }));
    await putResp(o.familyId, "r_mine", o.token, mute());
    expect((await delResp(o.familyId, "r_fam", m.token)).status).toBe(204);
    expect((await delResp(o.familyId, "r_mine", m.token)).status).toBe(403);
  });

  it("refuses a caller outside the family", async () => {
    const o = await ownerOf("resp-12");
    const other = await ownerOf("resp-12-out");
    const res = await putResp(o.familyId, "r_x", other.token, mute());
    expect([403, 404]).toContain(res.status);
  });
});


// ADR 0064 — the READ side, which is what lets the authoring loop avoid producing similar
// content rather than merely having it rejected after the fact.
describe("GET /responses — what the authoring path reads before it writes", () => {
  it("returns family rules with their human-readable labels", async () => {
    const o = await ownerOf("resp-get-1");
    await putResp(o.familyId, "r_w", o.token, mute({
      audience_scope: "family", label: "Weather cards", sublabel: "From Morning briefing",
    }));
    const res = await app.request(`/families/${o.familyId}/responses`, {
      headers: { authorization: `Bearer ${o.token}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    const row = body.responses.find((r: any) => r.id === "r_w");
    // The labels are the SEMANTIC signal — the server never reads them, the agent does.
    expect(row.label).toBe("Weather cards");
    expect(row.sublabel).toBe("From Morning briefing");
    expect(row.subject_ref).toBe("kind:weather");
    expect(row.match_scope).toBe("kind");
  });

  it("includes done records, so a resolved subject is not re-authored", async () => {
    const o = await ownerOf("resp-get-2");
    await putResp(o.familyId, "d1", o.token, {
      kind: "done", subject_ref: "card:c_task", match_scope: "subject",
      audience_scope: "family", label: "Verify emergency contact",
    });
    const body = await (await app.request(`/families/${o.familyId}/responses`, {
      headers: { authorization: `Bearer ${o.token}` },
    })).json();
    expect(body.responses.some((r: any) => r.kind === "done" && r.id === "d1")).toBe(true);
  });

  // Another member's personal rule is COUNTED, never disclosed: the author learns that
  // suppression exists without learning who muted what.
  it("counts another member's personal rules without exposing them", async () => {
    const o = await ownerOf("resp-get-3");
    const m = await memberOf("resp-get-3-b", o.familyId);
    await putResp(o.familyId, "r_theirs", m.token, mute({ audience_scope: "personal" }));
    const body = await (await app.request(`/families/${o.familyId}/responses`, {
      headers: { authorization: `Bearer ${o.token}` },
    })).json();
    expect(body.responses.some((r: any) => r.id === "r_theirs")).toBe(false);
    expect(body.personal_rules_not_visible).toBe(1);
  });

  it("shows a member their OWN personal rule", async () => {
    const o = await ownerOf("resp-get-4");
    await putResp(o.familyId, "r_mine", o.token, mute({ audience_scope: "personal" }));
    const body = await (await app.request(`/families/${o.familyId}/responses`, {
      headers: { authorization: `Bearer ${o.token}` },
    })).json();
    expect(body.responses.some((r: any) => r.id === "r_mine")).toBe(true);
    expect(body.personal_rules_not_visible).toBe(0);
  });
});
