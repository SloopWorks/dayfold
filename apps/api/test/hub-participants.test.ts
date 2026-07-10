// ADR 0053 DC2: in-app participant-management API — incremental add/set-role/remove
// of a hub participant + the family<->restricted visibility toggle. Gated to the hub
// author or an existing co_owner (canManageHub); the family `owner` role alone is NOT
// sufficient (mirrors the ADR 0030 §7 stance already applied to hub authorship/audience).
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
const authH = (tok: string) => ({ "content-type": "application/json", authorization: `Bearer ${tok}` });
const put = (fid: string, path: string, tok: string, body: any) =>
  app.request(`/families/${fid}/${path}`, { method: "PUT", headers: authH(tok), body: JSON.stringify(body) });
const del = (fid: string, path: string, tok: string) =>
  app.request(`/families/${fid}/${path}`, { method: "DELETE", headers: authH(tok) });
const getJson = async (fid: string, path: string, tok: string) => {
  const r = await app.request(`/families/${fid}/${path}`, { headers: { authorization: `Bearer ${tok}` } });
  return { status: r.status, body: r.status === 200 ? await r.json() : null };
};
describe("hub participant-management API (ADR 0053 DC2)", () => {
  it("author add/promote a participant; a non-manager member is 403; the author row is immutable; bad role is 400", async () => {
    const o = await ownerOf("hp-o1");
    const bob = await memberOf("hp-bob1", o.familyId);
    const carl = await memberOf("hp-carl1", o.familyId);
    // author (owner o) creates a restricted hub — o is the author.
    expect((await put(o.familyId, "hubs/hp1", o.token, { type: "party-event", title: "Party", visibility: "restricted", audience: [] })).status).toBe(200);

    // author adds bob as viewer.
    let r = await put(o.familyId, "hubs/hp1/participants/" + bob.userId, o.token, { role: "viewer" });
    expect(r.status).toBe(200);
    expect((await r.json()).role).toBe("viewer");

    // author promotes bob to contributor.
    r = await put(o.familyId, "hubs/hp1/participants/" + bob.userId, o.token, { role: "contributor" });
    expect(r.status).toBe(200);
    expect((await r.json()).role).toBe("contributor");

    // carl (not author, not co_owner) may not add/remove — 403. Also can't see the
    // restricted hub (not on the allow-list) so this doubles as the 403-not-404 check
    // once carl IS given a role below; for now carl is invisible → uniform 404 would
    // also be defensible, but canManageHub is evaluated only after the hub is visible
    // to the caller. Grant carl viewer visibility first via the author, then confirm
    // carl (a plain viewer, not co_owner) is still 403 on management.
    r = await put(o.familyId, "hubs/hp1/participants/" + carl.userId, o.token, { role: "viewer" });
    expect(r.status).toBe(200);
    r = await put(o.familyId, "hubs/hp1/participants/" + bob.userId, carl.token, { role: "viewer" });
    expect(r.status).toBe(403);
    r = await del(o.familyId, "hubs/hp1/participants/" + bob.userId, carl.token);
    expect(r.status).toBe(403);

    // removing the author is rejected.
    r = await put(o.familyId, "hubs/hp1/participants/" + o.userId, o.token, { role: "viewer" });
    expect(r.status).toBe(400);
    expect((await r.json()).type).toBe("author-immutable");
    r = await del(o.familyId, "hubs/hp1/participants/" + o.userId, o.token);
    expect(r.status).toBe(400);
    expect((await r.json()).type).toBe("author-immutable");

    // invalid role is 400.
    r = await put(o.familyId, "hubs/hp1/participants/" + bob.userId, o.token, { role: "wizard" });
    expect(r.status).toBe(400);
  });

  it("a co_owner (not the author) can manage: add bob as co_owner, then bob manages", async () => {
    const o = await ownerOf("hp-o2");
    const bob = await memberOf("hp-bob2", o.familyId);
    const dana = await memberOf("hp-dana2", o.familyId);
    expect((await put(o.familyId, "hubs/hp2", o.token, { type: "party-event", title: "Party" })).status).toBe(200);

    // author makes bob a co_owner.
    let r = await put(o.familyId, "hubs/hp2/participants/" + bob.userId, o.token, { role: "co_owner" });
    expect(r.status).toBe(200);

    // bob (co_owner, not author) can now add dana as a viewer.
    r = await put(o.familyId, "hubs/hp2/participants/" + dana.userId, bob.token, { role: "viewer" });
    expect(r.status).toBe(200);

    // bob can also remove dana.
    r = await del(o.familyId, "hubs/hp2/participants/" + dana.userId, bob.token);
    expect(r.status).toBe(204);

    // bob still cannot remove/demote the author.
    r = await del(o.familyId, "hubs/hp2/participants/" + o.userId, bob.token);
    expect(r.status).toBe(400);
  });

  it("visibility toggle family->restricted: a manager gets 200, a non-manager gets 403", async () => {
    const o = await ownerOf("hp-o3");
    const carl = await memberOf("hp-carl3", o.familyId);
    expect((await put(o.familyId, "hubs/hp3", o.token, { type: "party-event", title: "Party" })).status).toBe(200);

    // non-manager (plain member, not author/co_owner) is 403.
    let r = await put(o.familyId, "hubs/hp3/visibility", carl.token, { visibility: "restricted" });
    expect(r.status).toBe(403);

    // author flips family -> restricted.
    r = await put(o.familyId, "hubs/hp3/visibility", o.token, { visibility: "restricted" });
    expect(r.status).toBe(200);
    expect((await r.json()).visibility).toBe("restricted");

    const hub = await getJson(o.familyId, "hubs/hp3", o.token);
    expect(hub.body.visibility).toBe("restricted");

    // invalid visibility value is 400.
    r = await put(o.familyId, "hubs/hp3/visibility", o.token, { visibility: "public" });
    expect(r.status).toBe(400);
  });

  it("audience response includes can_manage for the caller", async () => {
    const o = await ownerOf("hp-o4");
    const carl = await memberOf("hp-carl4", o.familyId);
    expect((await put(o.familyId, "hubs/hp4", o.token, { type: "party-event", title: "Party" })).status).toBe(200);

    const asAuthor = await getJson(o.familyId, "hubs/hp4/audience", o.token);
    expect(asAuthor.status).toBe(200);
    expect(asAuthor.body.can_manage).toBe(true);

    const asMember = await getJson(o.familyId, "hubs/hp4/audience", carl.token);
    expect(asMember.status).toBe(200);
    expect(asMember.body.can_manage).toBe(false);
  });

  it("a hub the caller cannot see is a uniform 404 on the management routes (no existence oracle)", async () => {
    const o = await ownerOf("hp-o5");
    const carl = await memberOf("hp-carl5", o.familyId);
    // restricted, carl NOT on the allow-list.
    expect((await put(o.familyId, "hubs/hp5", o.token, { type: "party-event", title: "Party", visibility: "restricted", audience: [] })).status).toBe(200);

    let r = await put(o.familyId, "hubs/hp5/participants/" + carl.userId, carl.token, { role: "viewer" });
    expect(r.status).toBe(404);
    r = await del(o.familyId, "hubs/hp5/participants/" + carl.userId, carl.token);
    expect(r.status).toBe(404);
    r = await put(o.familyId, "hubs/hp5/visibility", carl.token, { visibility: "family" });
    expect(r.status).toBe(404);

    // nonexistent hub id entirely is also 404.
    r = await put(o.familyId, "hubs/nope/participants/" + carl.userId, o.token, { role: "viewer" });
    expect(r.status).toBe(404);
  });
});
