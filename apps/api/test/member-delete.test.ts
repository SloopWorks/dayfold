import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { generateKeyPair, exportJWK } from "jose";
const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.AUTH_ISS = "https://fad.test/auth"; process.env.AUTH_AUD = "fad-api-test";
process.env.ENABLE_DEV_AUTH = "1"; process.env.DEV_AUTH_SECRET = "dev"; delete process.env.VERCEL_ENV;
const kp = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
const priv = await exportJWK(kp.privateKey); priv.kid = "k1"; priv.alg = "EdDSA";
process.env.AUTH_SIGNING_KEY = JSON.stringify(priv);
const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0006_typed_content.sql","0007_related.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql","0015_two_way_reserve.sql","0016_hub_timeline.sql","0017_user_avatar.sql","0018_resource_visibility_role.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
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
const H = (tok: string, extra: Record<string, string> = {}) => ({ "content-type": "application/json", authorization: `Bearer ${tok}`, ...extra });
const putHub = (fid: string, id: string, tok: string, body: any) =>
  app.request(`/families/${fid}/hubs/${id}`, { method: "PUT", headers: H(tok), body: JSON.stringify({ type: "party-event", title: "H", ...body }) });
const putSection = (fid: string, id: string, tok: string, hubId: string) =>
  app.request(`/families/${fid}/sections/${id}`, { method: "PUT", headers: H(tok), body: JSON.stringify({ hubId, title: "S" }) });
const putBlock = (fid: string, id: string, tok: string, sectionId: string, extra: Record<string, string> = {}) =>
  app.request(`/families/${fid}/blocks/${id}`, { method: "PUT", headers: H(tok, extra), body: JSON.stringify({
    sectionId, type: "checklist", payload: { items: [{ id: "i1", text: "x" }] }, provenance: { source: "member", at: "2026-06-29T10:00:00Z" } }) });
const delBlock = (fid: string, id: string, tok: string, extra: Record<string, string> = {}) =>
  app.request(`/families/${fid}/blocks/${id}`, { method: "DELETE", headers: H(tok, extra) });
async function hubWithSection(o: { familyId: string; token: string }, hubId: string, secId: string, vis: { visibility?: string; audience?: string[] } = {}) {
  expect((await putHub(o.familyId, hubId, o.token, vis)).status).toBe(200);
  expect((await putSection(o.familyId, secId, o.token, hubId)).status).toBe(200);
  return secId;
}

describe("W4 delete — content:delete scope + author-gate (ADR 0038 §W4)", () => {
  it("the author can delete their own block (204), and it's then gone from the tree", async () => {
    const o = await ownerOf("del-owner");
    const sec = await hubWithSection(o, "hDel", "sDel");
    expect((await putBlock(o.familyId, "bMine", o.token, sec)).status).toBe(200);
    expect((await delBlock(o.familyId, "bMine", o.token)).status).toBe(204);
    const tree = await (await app.request(`/families/${o.familyId}/hubs/hDel/tree`, { headers: H(o.token) })).json();
    expect(tree.blocks.find((b: any) => b.id === "bMine")).toBeUndefined();   // tombstoned
  });

  it("a member CANNOT delete another member's block — 403 (owner is no override)", async () => {
    const o = await ownerOf("del-owner2");
    const bob = await memberOf("del-bob", o.familyId);
    const sec = await hubWithSection(o, "hShared", "sShared");
    // ADR 0053 item 4 (DC3): a plain member can no longer write hub content by
    // visibility alone — grant bob 'contributor' so he can author the block this
    // test needs (the test's point is the DELETE author-gate, not the write-gate).
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES ($1,'hShared',$2,'contributor')`, [o.familyId, bob.userId]);
    expect((await putBlock(o.familyId, "bBob", bob.token, sec)).status).toBe(200);   // bob authors it
    expect((await delBlock(o.familyId, "bBob", o.token)).status).toBe(403);          // owner can't delete it
  });

  it("delete requires content:delete — a write-only credential is 403", async () => {
    const o = await ownerOf("del-owner3");
    const sec = await hubWithSection(o, "hScope", "sScope");
    expect((await putBlock(o.familyId, "bScope", o.token, sec)).status).toBe(200);
    // revoke only the delete grant for the owner's credential
    await q(`DELETE FROM credential_grants WHERE scope='content:delete'`);
    expect((await delBlock(o.familyId, "bScope", o.token)).status).toBe(403);
  });

  it("deleting a block in a hub the member can't see → 404 (no existence oracle)", async () => {
    const o = await ownerOf("del-owner4");
    const bob = await memberOf("del-bob4", o.familyId);
    const sec = await hubWithSection(o, "hPriv", "sPriv", { visibility: "restricted", audience: [o.userId] });
    expect((await putBlock(o.familyId, "bPriv", o.token, sec)).status).toBe(200);
    expect((await delBlock(o.familyId, "bPriv", bob.token)).status).toBe(404);
  });

  it("a repeated delete with the same Idempotency-Key is 204, not 404/410", async () => {
    const o = await ownerOf("del-owner5");
    const sec = await hubWithSection(o, "hIdem", "sIdem");
    expect((await putBlock(o.familyId, "bIdem", o.token, sec)).status).toBe(200);
    expect((await delBlock(o.familyId, "bIdem", o.token, { "idempotency-key": "OPDEL1" })).status).toBe(204);
    expect((await delBlock(o.familyId, "bIdem", o.token, { "idempotency-key": "OPDEL1" })).status).toBe(204);
  });

  it("deleting a block that never existed → 404", async () => {
    const o = await ownerOf("del-owner6");
    await hubWithSection(o, "hNone", "sNone");
    expect((await delBlock(o.familyId, "ghost", o.token)).status).toBe(404);
  });
});
