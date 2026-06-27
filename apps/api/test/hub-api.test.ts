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
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0006_typed_content.sql","0007_related.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql"])
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
const authH = (tok: string) => ({ "content-type": "application/json", authorization: `Bearer ${tok}` });
const put = (fid: string, path: string, tok: string, body: any) =>
  app.request(`/families/${fid}/${path}`, { method: "PUT", headers: authH(tok), body: JSON.stringify(body) });
const getJson = async (fid: string, path: string, tok: string) => {
  const r = await app.request(`/families/${fid}/${path}`, { headers: { authorization: `Bearer ${tok}` } });
  return { status: r.status, body: r.status === 200 ? await r.json() : null };
};
const prov = { source: "cli", at: "2026-06-18T10:00:00Z" };

describe("hub content API (ADR 0006/0029/0030)", () => {
  it("hub + section + block round-trip via /tree", async () => {
    const o = await ownerOf("hub-o1");
    expect((await put(o.familyId, "hubs/h1", o.token, { type: "party-event", title: "Party" })).status).toBe(200);
    expect((await put(o.familyId, "sections/s1", o.token, { hubId: "h1", title: "Plan", ord: 0 })).status).toBe(200);
    expect((await put(o.familyId, "blocks/b1", o.token, { sectionId: "s1", type: "text", body_md: "buy cake", ord: 0 })).status).toBe(200);
    const tree = await getJson(o.familyId, "hubs/h1/tree", o.token);
    expect(tree.status).toBe(200);
    expect(tree.body.hub.id).toBe("h1");
    expect(tree.body.sections.map((s: any) => s.id)).toEqual(["s1"]);
    expect(tree.body.blocks.map((b: any) => b.id)).toEqual(["b1"]);
    expect(tree.body.blocks[0].provenance.credential_id).toBeTruthy(); // server-stamped
  });

  it("restricted hub: a non-audience member cannot list it, fetch it, OR reach its blocks (leak fix)", async () => {
    const o = await ownerOf("hub-o2");
    const b = await memberOf("hub-bob", o.familyId);
    // hub restricted to the owner only, with a block carrying sensitive body_md
    await put(o.familyId, "hubs/sec", o.token, { type: "medical", title: "Private", visibility: "restricted", audience: [o.userId] });
    await put(o.familyId, "sections/ss", o.token, { hubId: "sec", title: "notes" });
    await put(o.familyId, "blocks/bb", o.token, { sectionId: "ss", type: "text", body_md: "SENSITIVE" });

    // owner sees everything
    expect((await getJson(o.familyId, "hubs/sec", o.token)).status).toBe(200);
    expect((await getJson(o.familyId, "hubs/sec/tree", o.token)).body.blocks[0].body_md).toBe("SENSITIVE");
    // bob: not in the list, 404 on the hub, AND 404 on the tree — cannot reach the block
    const bobList = await getJson(o.familyId, "hubs", b.token);
    expect(bobList.body.map((h: any) => h.id)).not.toContain("sec");
    expect((await getJson(o.familyId, "hubs/sec", b.token)).status).toBe(404);
    expect((await getJson(o.familyId, "hubs/sec/tree", b.token)).status).toBe(404);
  });

  it("audience: roster + permitted flags; non-permitted member gets 404 (ADR 0030 'who can see')", async () => {
    const o = await ownerOf("aud-owner");
    const bob = await memberOf("aud-bob", o.familyId);
    const eve = await memberOf("aud-eve", o.familyId);
    // restricted hub authored by the owner, allow-listing bob (NOT eve)
    await put(o.familyId, "hubs/r", o.token, { type: "medical", title: "Private", visibility: "restricted", audience: [bob.userId] });

    // author can read the audience: owner(author)+bob permitted, eve not
    const aud = await getJson(o.familyId, "hubs/r/audience", o.token);
    expect(aud.status).toBe(200);
    expect(aud.body.visibility).toBe("restricted");
    const by = (uid: string) => aud.body.members.find((m: any) => m.uid === uid)?.permitted;
    expect(by(o.userId)).toBe(true);    // author
    expect(by(bob.userId)).toBe(true);  // allow-listed
    expect(by(eve.userId)).toBe(false); // not permitted (and owner-not-auto-permitted holds for non-owner authors too)
    // eve (not permitted) cannot even enumerate the audience → uniform 404
    expect((await getJson(o.familyId, "hubs/r/audience", eve.token)).status).toBe(404);

    // a family-visible hub: everyone permitted
    await put(o.familyId, "hubs/f", o.token, { type: "party-event", title: "Party", visibility: "family" });
    const audF = await getJson(o.familyId, "hubs/f/audience", eve.token);
    expect(audF.status).toBe(200);
    expect(audF.body.members.every((m: any) => m.permitted)).toBe(true);
  });

  it("parent-must-exist: section under a missing hub → 409; block under a missing section → 409", async () => {
    const o = await ownerOf("hub-o3");
    expect((await put(o.familyId, "sections/orphan", o.token, { hubId: "nope" })).status).toBe(409);
    expect((await put(o.familyId, "blocks/orphan", o.token, { sectionId: "nope", type: "text", body_md: "x" })).status).toBe(409);
  });

  it("soft-delete cascades hub→sections→blocks in one tx; archive flips status", async () => {
    const o = await ownerOf("hub-o4");
    await put(o.familyId, "hubs/h4", o.token, { type: "move", title: "Move" });
    await put(o.familyId, "sections/s4", o.token, { hubId: "h4" });
    await put(o.familyId, "blocks/b4", o.token, { sectionId: "s4", type: "text", body_md: "x" });
    expect((await app.request(`/families/${o.familyId}/hubs/h4/archive`, { method: "POST", headers: authH(o.token) })).status).toBe(204);
    expect((await getJson(o.familyId, "hubs/h4", o.token)).body.status).toBe("archived");
    const del = await app.request(`/families/${o.familyId}/hubs/h4`, { method: "DELETE", headers: authH(o.token) });
    expect(del.status).toBe(204);
    expect((await getJson(o.familyId, "hubs/h4", o.token)).status).toBe(404);
    expect((await getJson(o.familyId, "hubs/h4/tree", o.token)).status).toBe(404);
    const blk = await q(`SELECT deleted_at FROM blocks WHERE family_id=$1 AND id='b4'`, [o.familyId]);
    expect(blk.rows[0].deleted_at).not.toBeNull(); // block soft-deleted by the cascade
  });

  it("cross-tenant: another family's owner gets 404 on these hubs", async () => {
    const o = await ownerOf("hub-o5");
    await put(o.familyId, "hubs/h5", o.token, { type: "vacation", title: "Trip" });
    const stranger = await ownerOf("hub-stranger");
    expect((await getJson(o.familyId, "hubs/h5", stranger.token)).status).toBe(404);     // not a member → tenancy 404
    expect((await put(o.familyId, "hubs/h5", stranger.token, { type: "vacation", title: "hijack" })).status).toBe(404);
  });

  it("hub id charset is constrained (no ':' that could ambiguate a grant string)", async () => {
    const o = await ownerOf("hub-o6");
    expect((await put(o.familyId, "hubs/" + encodeURIComponent("a:b"), o.token, { type: "move", title: "x" })).status).toBe(422);
  });

  it("the same id charset is enforced on cards/sections/blocks, not just hubs", async () => {
    const o = await ownerOf("hub-o6b");
    const evil = encodeURIComponent("a:b");                       // ':' is outside [A-Za-z0-9_-]
    expect((await put(o.familyId, "cards/" + evil, o.token, { kind: "info", title: "x", provenance: prov })).status).toBe(422);
    expect((await put(o.familyId, "sections/" + evil, o.token, { hubId: "h1", title: "x" })).status).toBe(422);
    expect((await put(o.familyId, "blocks/" + evil, o.token, { sectionId: "s1", type: "text", body_md: "x" })).status).toBe(422);
    // a well-formed id on the same routes is accepted (guard rejects the charset, not the route)
    expect((await put(o.familyId, "hubs/ok-1", o.token, { type: "move", title: "x" })).status).toBe(200);
    expect((await put(o.familyId, "sections/ok-1", o.token, { hubId: "ok-1", title: "x" })).status).toBe(200);
  });

  it("DELETE routes guard the id charset too — 422, not a misleading 404", async () => {
    const o = await ownerOf("hub-o6c");
    const evil = encodeURIComponent("a:b");                       // ':' could ambiguate `hub:<id>` scope
    const del = (path: string) => app.request(`/families/${o.familyId}/${path}`, { method: "DELETE", headers: authH(o.token) });
    expect((await del("cards/" + evil)).status).toBe(422);
    expect((await del("hubs/" + evil)).status).toBe(422);
    // a well-formed id that simply doesn't exist still 404s (guard is about charset, not existence)
    expect((await del("cards/ok-2")).status).toBe(404);
  });

  it("§6: only the author or an already-permitted member may rewrite a hub's visibility", async () => {
    const o = await ownerOf("s6-owner");
    const bob = await memberOf("s6-bob", o.familyId);
    const fid = o.familyId;
    // owner authors a restricted hub; bob is NOT in the allow-list
    await put(fid, "hubs/s6", o.token, { type: "medical", title: "Private", visibility: "restricted", audience: [o.userId] });
    // give bob content:write so he clears requireScope and actually reaches the §6 gate
    await q(`INSERT INTO credential_grants(credential_id, scope)
             SELECT id, 'content:write' FROM credentials WHERE user_id=$1 ON CONFLICT DO NOTHING`, [bob.userId]);

    // non-author, non-permitted → 403 (can't widen a hub he can't even see)
    expect((await put(fid, "hubs/s6", bob.token, { type: "medical", title: "Private", visibility: "family" })).status).toBe(403);
    expect((await getJson(fid, "hubs/s6", bob.token)).status).toBe(404);   // unchanged: still restricted, still hidden from bob

    // once the author adds bob to the allow-list, bob MAY rewrite it
    await put(fid, "hubs/s6", o.token, { type: "medical", title: "Private", visibility: "restricted", audience: [o.userId, bob.userId] });
    expect((await put(fid, "hubs/s6", bob.token, { type: "medical", title: "Open", visibility: "family" })).status).toBe(200);
  });
});
