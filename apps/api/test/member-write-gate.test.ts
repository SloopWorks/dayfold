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
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0006_typed_content.sql","0007_related.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql","0015_two_way_reserve.sql"])
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
const putBlock = (fid: string, id: string, tok: string, sectionId: string, items: any[], extra: Record<string, string> = {}) =>
  app.request(`/families/${fid}/blocks/${id}`, { method: "PUT", headers: H(tok, extra), body: JSON.stringify({
    sectionId, type: "checklist", payload: { items }, provenance: { source: "member", at: "2026-06-29T10:00:00Z" } }) });

// Build a hub + section owned by `owner`; returns the section id a member writes blocks into.
async function hubWithSection(o: { familyId: string; token: string }, hubId: string, secId: string, vis: { visibility?: string; audience?: string[] } = {}) {
  expect((await putHub(o.familyId, hubId, o.token, vis)).status).toBe(200);
  expect((await putSection(o.familyId, secId, o.token, hubId)).status).toBe(200);
  return secId;
}

describe("member write visibility-on-write matrix (ADR 0038 §6.2 / 0030)", () => {
  it("own / family / restricted-visible → 200; restricted-invisible → 404 (no existence oracle)", async () => {
    const o = await ownerOf("mw-owner");
    const bob = await memberOf("mw-bob", o.familyId);

    // own: owner writes a block into their own family hub
    const ownSec = await hubWithSection(o, "hubOwn", "secOwn");
    expect((await putBlock(o.familyId, "bOwn", o.token, ownSec, [{ id: "i1", text: "x" }])).status).toBe(200);

    // family: a member writes into a family-visible hub
    const famSec = await hubWithSection(o, "hubFam", "secFam");
    expect((await putBlock(o.familyId, "bFam", bob.token, famSec, [{ id: "i1", text: "x" }])).status).toBe(200);

    // restricted-visible: bob is in the hub audience → can write
    const visSec = await hubWithSection(o, "hubVis", "secVis", { visibility: "restricted", audience: [bob.userId] });
    expect((await putBlock(o.familyId, "bVis", bob.token, visSec, [{ id: "i1", text: "x" }])).status).toBe(200);

    // restricted-invisible: bob is NOT in the audience → uniform 404 (not 403, no oracle)
    const invSec = await hubWithSection(o, "hubInv", "secInv", { visibility: "restricted", audience: [o.userId] });
    expect((await putBlock(o.familyId, "bInv", bob.token, invSec, [{ id: "i1", text: "x" }])).status).toBe(404);
  });
});

describe("If-Match → 412 (ADR 0038 §6.2)", () => {
  it("matching base version writes; a stale base version is 412", async () => {
    const o = await ownerOf("mw-ifm");
    const sec = await hubWithSection(o, "hubIfm", "secIfm");
    expect((await putBlock(o.familyId, "blk", o.token, sec, [{ id: "i1", text: "x" }])).status).toBe(200); // v1

    const r1 = await putBlock(o.familyId, "blk", o.token, sec, [{ id: "i1", text: "x", done: true }], { "if-match": "1" });
    expect(r1.status).toBe(200); // v1 → v2
    expect(Number((await r1.json()).version)).toBe(2);

    const stale = await putBlock(o.familyId, "blk", o.token, sec, [{ id: "i1", text: "x", done: false }], { "if-match": "1" });
    expect(stale.status).toBe(412); // base v1 is stale (current v2)
  });
});

describe("410-on-tombstone (ADR 0038 §6.3 — no member resurrection)", () => {
  it("a member write to a soft-deleted block is 410 Gone (not a resurrection)", async () => {
    const o = await ownerOf("mw-tomb");
    const bob = await memberOf("mw-tomb-bob", o.familyId);
    const sec = await hubWithSection(o, "hubTomb", "secTomb");
    expect((await putBlock(o.familyId, "zblk", bob.token, sec, [{ id: "i1", text: "x" }])).status).toBe(200);
    // the loop deletes the block (simulate the tombstone the W4 path will create)
    await q(`UPDATE blocks SET deleted_at=now() WHERE family_id=$1 AND id=$2`, [o.familyId, "zblk"]);
    const r = await putBlock(o.familyId, "zblk", bob.token, sec, [{ id: "i1", text: "x", done: true }]);
    expect(r.status).toBe(410);
    // and it stayed dead (not resurrected)
    const live = await q(`SELECT 1 FROM blocks WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [o.familyId, "zblk"]);
    expect(live.rowCount).toBe(0);
  });
});

describe("op_id idempotency (ADR 0039 §6.5)", () => {
  it("a retried op (same Idempotency-Key) returns the recorded result, never double-applies", async () => {
    const o = await ownerOf("mw-op");
    const sec = await hubWithSection(o, "hubOp", "secOp");
    expect((await putBlock(o.familyId, "oblk", o.token, sec, [{ id: "i1", text: "x" }])).status).toBe(200); // v1

    const first = await putBlock(o.familyId, "oblk", o.token, sec, [{ id: "i1", text: "x", done: true }], { "idempotency-key": "op-1" });
    expect(first.status).toBe(200);
    const v = Number((await first.json()).version); // v2

    // retry the SAME op_id (a draining offline sender / a duplicate) → recorded result, NOT v3
    const retry = await putBlock(o.familyId, "oblk", o.token, sec, [{ id: "i1", text: "x", done: true }], { "idempotency-key": "op-1" });
    expect(retry.status).toBe(200);
    expect(Number((await retry.json()).version)).toBe(v); // unchanged

    const cur = await q(`SELECT version FROM blocks WHERE family_id=$1 AND id=$2`, [o.familyId, "oblk"]);
    expect(Number(cur.rows[0].version)).toBe(v); // exactly one apply
  });

  it("a retried op short-circuits BEFORE If-Match (own echo never 412s)", async () => {
    const o = await ownerOf("mw-op2");
    const sec = await hubWithSection(o, "hubOp2", "secOp2");
    await putBlock(o.familyId, "o2", o.token, sec, [{ id: "i1", text: "x" }]); // v1
    await putBlock(o.familyId, "o2", o.token, sec, [{ id: "i1", text: "x", done: true }], { "idempotency-key": "op-2", "if-match": "1" }); // v2
    // replay with the now-stale If-Match: 1 — op_id short-circuit must win over the 412
    const replay = await putBlock(o.familyId, "o2", o.token, sec, [{ id: "i1", text: "x", done: true }], { "idempotency-key": "op-2", "if-match": "1" });
    expect(replay.status).toBe(200);
  });
});
