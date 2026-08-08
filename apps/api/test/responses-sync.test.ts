// ADR 0064 — response rows ride the ADR 0040 merged cursor. The per-member visibility rule
// is the interesting part: a personal rule is its owner's alone, and every other member gets
// a TOMBSTONE rather than an omission, so the cursor still advances and a rule that changed
// hands leaves the other member's cache.
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
const putResp = (fid: string, id: string, tok: string, body: any) =>
  app.request(`/families/${fid}/responses/${id}`, { method: "PUT", headers: authH(tok), body: JSON.stringify(body) });
const sync = async (fid: string, tok: string, since?: string) => {
  const url = `/families/${fid}/sync${since ? `?since=${encodeURIComponent(since)}` : ""}`;
  const r = await app.request(url, { headers: { authorization: `Bearer ${tok}` } });
  return { status: r.status, body: r.status === 200 ? await r.json() : null };
};

const mute = (over: any = {}) => ({
  kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
  audience_scope: "family", label: "Weather in Now", ...over,
});

describe("/sync emits response rows (ADR 0064)", () => {
  it("emits a family rule to every member", async () => {
    const o = await ownerOf("sync-1");
    const m = await memberOf("sync-1-b", o.familyId);
    await putResp(o.familyId, "r_fam", o.token, mute());
    const mine = await sync(o.familyId, o.token);
    const theirs = await sync(o.familyId, m.token);
    expect(mine.body.changes.responses.map((r: any) => r.id)).toContain("r_fam");
    expect(theirs.body.changes.responses.map((r: any) => r.id)).toContain("r_fam");
  });

  it("emits a personal rule to its owner and a tombstone to everyone else", async () => {
    const o = await ownerOf("sync-2");
    const m = await memberOf("sync-2-b", o.familyId);
    await putResp(o.familyId, "r_me", o.token, mute({ audience_scope: "personal", subject_ref: "kind:traffic" }));
    const mine = await sync(o.familyId, o.token);
    const theirs = await sync(o.familyId, m.token);
    expect(mine.body.changes.responses.map((r: any) => r.id)).toContain("r_me");
    expect(theirs.body.changes.responses.map((r: any) => r.id)).not.toContain("r_me");
    expect(theirs.body.tombstones).toContainEqual({ type: "response", id: "r_me" });
  });

  it("emits a tombstone to everyone once a rule is removed", async () => {
    const o = await ownerOf("sync-3");
    await putResp(o.familyId, "r_gone", o.token, mute());
    await app.request(`/families/${o.familyId}/responses/r_gone`, { method: "DELETE", headers: authH(o.token) });
    const after = await sync(o.familyId, o.token);
    expect(after.body.tombstones).toContainEqual({ type: "response", id: "r_gone" });
  });

  it("accepts a 3-part cursor whose type token is `response`", async () => {
    const o = await ownerOf("sync-4");
    await putResp(o.familyId, "r_c", o.token, mute());
    const cursor = Buffer.from(`${new Date().toISOString()}|response|r_c`).toString("base64");
    expect((await sync(o.familyId, o.token, cursor)).status).toBe(200);   // not 400 bad-cursor
  });

  // The cursor must advance past another member's personal rules. If they were omitted
  // instead of tombstoned, a page consisting only of such rules would look empty and the
  // client would resume from the same place forever.
  it("advances the cursor across a page of another member's personal rules", async () => {
    const o = await ownerOf("sync-5");
    const m = await memberOf("sync-5-b", o.familyId);
    for (let i = 0; i < 3; i++) {
      await putResp(o.familyId, `r_p${i}`, o.token, mute({ audience_scope: "personal", subject_ref: `kind:k${i}` }));
    }
    const first = await sync(o.familyId, m.token);
    expect(first.body.next_cursor).toBeTruthy();
    const second = await sync(o.familyId, m.token, first.body.next_cursor);
    expect(second.status).toBe(200);
    expect(second.body.tombstones).toEqual([]);      // fully drained, not stuck re-serving
    expect(second.body.changes.responses).toEqual([]);
  });

  it("a resumed cursor picks up a rule written after it", async () => {
    const o = await ownerOf("sync-6");
    const first = await sync(o.familyId, o.token);
    await putResp(o.familyId, "r_late", o.token, mute());
    const second = await sync(o.familyId, o.token, first.body.next_cursor);
    expect(second.body.changes.responses.map((r: any) => r.id)).toContain("r_late");
  });
});
