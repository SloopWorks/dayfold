// ADR 0053 item 4 — DC3: gate hub CONTENT writes (sections/blocks) on the caller's
// resource_visibility.role, not just credential scope. A viewer (or a member with no
// allow-list row) may see a family/restricted-visible hub but may NOT write into it;
// a contributor may. The legacy/M0 token stays write-exempt (author-equivalent).
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
const H = (tok: string) => ({ "content-type": "application/json", authorization: `Bearer ${tok}` });
const putHub = (fid: string, id: string, tok: string, body: any) =>
  app.request(`/families/${fid}/hubs/${id}`, { method: "PUT", headers: H(tok), body: JSON.stringify({ type: "party-event", title: "H", ...body }) });
const putSection = (fid: string, id: string, tok: string, hubId: string) =>
  app.request(`/families/${fid}/sections/${id}`, { method: "PUT", headers: H(tok), body: JSON.stringify({ hubId, title: "S" }) });
const putBlock = (fid: string, id: string, tok: string, sectionId: string) =>
  app.request(`/families/${fid}/blocks/${id}`, { method: "PUT", headers: H(tok), body: JSON.stringify({
    sectionId, type: "checklist", payload: { items: [{ id: "i1", text: "x" }] }, provenance: { source: "member", at: "2026-06-29T10:00:00Z" } }) });

describe("hub write-gate: member role (ADR 0053 item 4)", () => {
  it("a viewer member writing hub content (section/block) → denied (403)", async () => {
    const o = await ownerOf("hwr-owner1");
    const bob = await memberOf("hwr-bob1", o.familyId);
    const fid = o.familyId;
    // family-visible hub, bob NOT author, NOT given any role (no resource_visibility row).
    expect((await putHub(fid, "hwr-h1", o.token, {})).status).toBe(200);
    expect((await putSection(fid, "hwr-s1", o.token, "hwr-h1")).status).toBe(200);

    // bob may READ (family-visible) but may NOT write a new section...
    expect((await putSection(fid, "hwr-s1b", bob.token, "hwr-h1")).status).toBe(403);
    // ...nor a block into the existing section.
    expect((await putBlock(fid, "hwr-b1", bob.token, "hwr-s1")).status).toBe(403);

    // explicit viewer role (allow-listed but not promoted) is likewise denied.
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES ($1,'hwr-h1',$2,'viewer')
             ON CONFLICT (family_id,hub_id,user_id) DO UPDATE SET role='viewer'`, [fid, bob.userId]);
    expect((await putBlock(fid, "hwr-b2", bob.token, "hwr-s1")).status).toBe(403);
  });

  it("a contributor member writing hub content → ok (200)", async () => {
    const o = await ownerOf("hwr-owner2");
    const bob = await memberOf("hwr-bob2", o.familyId);
    const fid = o.familyId;
    expect((await putHub(fid, "hwr-h2", o.token, {})).status).toBe(200);
    expect((await putSection(fid, "hwr-s2", o.token, "hwr-h2")).status).toBe(200);
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES ($1,'hwr-h2',$2,'contributor')`, [fid, bob.userId]);

    expect((await putSection(fid, "hwr-s2b", bob.token, "hwr-h2")).status).toBe(200);
    expect((await putBlock(fid, "hwr-b3", bob.token, "hwr-s2")).status).toBe(200);
  });

  it("a co_owner member writing hub content → ok (200)", async () => {
    const o = await ownerOf("hwr-owner3");
    const bob = await memberOf("hwr-bob3", o.familyId);
    const fid = o.familyId;
    expect((await putHub(fid, "hwr-h3", o.token, { visibility: "restricted", audience: [o.userId, bob.userId] })).status).toBe(200);
    expect((await putSection(fid, "hwr-s3", o.token, "hwr-h3")).status).toBe(200);
    await q(`UPDATE resource_visibility SET role='co_owner' WHERE family_id=$1 AND hub_id='hwr-h3' AND user_id=$2`, [fid, bob.userId]);

    expect((await putBlock(fid, "hwr-b4", bob.token, "hwr-s3")).status).toBe(200);
  });

  it("the author may always write, even with no resource_visibility row at all", async () => {
    const o = await ownerOf("hwr-owner4");
    const fid = o.familyId;
    expect((await putHub(fid, "hwr-h4", o.token, {})).status).toBe(200);
    expect((await putSection(fid, "hwr-s4", o.token, "hwr-h4")).status).toBe(200);
    expect((await putBlock(fid, "hwr-b5", o.token, "hwr-s4")).status).toBe(200);
  });

  it("a restricted hub the caller can't see is still a uniform 404 (visibility gate precedes the role gate)", async () => {
    const o = await ownerOf("hwr-owner5");
    const bob = await memberOf("hwr-bob5", o.familyId);
    const fid = o.familyId;
    expect((await putHub(fid, "hwr-h5", o.token, { visibility: "restricted", audience: [o.userId] })).status).toBe(200);
    expect((await putSection(fid, "hwr-s5", o.token, "hwr-h5")).status).toBe(200);
    // bob isn't even on the allow-list → invisible, so it's 404, not 403.
    expect((await putBlock(fid, "hwr-b6", bob.token, "hwr-s5")).status).toBe(404);
  });
});
