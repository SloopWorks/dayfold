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

beforeAll(async () => {
  await applyAllMigrations(q);
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

    // family: a member writes into a family-visible hub. ADR 0053 item 4/7 (DC3):
    // family visibility grants READ to all, NOT write — bob needs an explicit
    // contributor role to write here (visibility alone is no longer enough).
    const famSec = await hubWithSection(o, "hubFam", "secFam");
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES ($1,'hubFam',$2,'contributor')`, [o.familyId, bob.userId]);
    expect((await putBlock(o.familyId, "bFam", bob.token, famSec, [{ id: "i1", text: "x" }])).status).toBe(200);

    // restricted-visible: bob is in the hub audience → can write. Being on the
    // allow-list alone defaults to 'viewer' (read-only); DC3 requires bumping him
    // to contributor to actually write.
    const visSec = await hubWithSection(o, "hubVis", "secVis", { visibility: "restricted", audience: [bob.userId] });
    await q(`UPDATE resource_visibility SET role='contributor' WHERE family_id=$1 AND hub_id='hubVis' AND user_id=$2`, [o.familyId, bob.userId]);
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

  it("serializes concurrent section updates before checking the same base version", async () => {
    const o = await ownerOf("mw-section-ifm-race");
    await putHub(o.familyId, "hubSectionIfm", o.token, {});
    expect((await putSection(o.familyId, "sectionIfm", o.token, "hubSectionIfm")).status).toBe(200);

    const update = (title: string) => app.request(
      `/families/${o.familyId}/sections/sectionIfm`,
      {
        method: "PUT",
        headers: H(o.token, { "if-match": "1" }),
        body: JSON.stringify({ hubId: "hubSectionIfm", title }),
      },
    );
    const results = await Promise.all([update("First"), update("Second")]);
    expect(results.map((r) => r.status).sort()).toEqual([200, 412]);
    expect(Number((await q(
      `SELECT version FROM sections WHERE family_id=$1 AND id='sectionIfm'`, [o.familyId],
    )).rows[0].version)).toBe(2);
  });
});

describe("410-on-tombstone (ADR 0038 §6.3 — no member resurrection)", () => {
  it("a member write to a soft-deleted block is 410 Gone (not a resurrection)", async () => {
    const o = await ownerOf("mw-tomb");
    const bob = await memberOf("mw-tomb-bob", o.familyId);
    const sec = await hubWithSection(o, "hubTomb", "secTomb");
    // DC3: family visibility no longer implies write — grant bob contributor so he
    // can author the block this tombstone-resurrection test needs.
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES ($1,'hubTomb',$2,'contributor')`, [o.familyId, bob.userId]);
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

  it("binds a block op key to its id and target and rechecks the current Hub ACL", async () => {
    const o = await ownerOf("mw-op-bound-owner");
    const m = await memberOf("mw-op-bound-member", o.familyId);
    const source = await hubWithSection(o, "hubOpBoundSource", "secOpBoundSource");
    const alternate = await hubWithSection(o, "hubOpBoundAlternate", "secOpBoundAlternate");
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES
      ($1,'hubOpBoundSource',$2,'contributor'),
      ($1,'hubOpBoundAlternate',$2,'contributor')`, [o.familyId, m.userId]);

    expect((await putBlock(o.familyId, "boundBlock", m.token, source,
      [{ id: "i1", text: "Private task" }], { "idempotency-key": "bound-op" })).status).toBe(200);

    const wrongTarget = await putBlock(o.familyId, "boundBlock", m.token, alternate,
      [{ id: "i1", text: "Private task" }], { "idempotency-key": "bound-op" });
    expect(wrongTarget.status).toBe(409);
    expect((await wrongTarget.json()).type).toBe("idempotency-key-reused");

    const wrongId = await putBlock(o.familyId, "differentBlock", m.token, alternate,
      [{ id: "i1", text: "Different task" }], { "idempotency-key": "bound-op" });
    expect(wrongId.status).toBe(409);
    expect((await wrongId.json()).type).toBe("idempotency-key-reused");

    // Move the original row into a Hub the member cannot see. Replaying the old request still
    // passes its visible destination gate, so only a current-row ACL check prevents disclosure.
    const hidden = await hubWithSection(o, "hubOpBoundHidden", "secOpBoundHidden", {
      visibility: "restricted", audience: [o.userId],
    });
    expect((await putBlock(o.familyId, "boundBlock", o.token, hidden,
      [{ id: "i1", text: "Private task" }])).status).toBe(200);
    const hiddenReplay = await putBlock(o.familyId, "boundBlock", m.token, source,
      [{ id: "i1", text: "Private task" }], { "idempotency-key": "bound-op" });
    expect(hiddenReplay.status).toBe(404);
    expect(await hiddenReplay.text()).toBe("");
  });
});
