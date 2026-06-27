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
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0005_invites.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
afterAll(async () => { await pool.end(); });

const dev = { "content-type":"application/json", authorization:"Bearer dev" };
const auth = (t: string) => ({ ...dev, authorization: `Bearer ${t}` });
async function token(uid: string) {
  return (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:uid})})).json()).access;
}
async function ownerOf(uid: string) {
  const t = await token(uid);
  const fam = await (await app.request("/families",{method:"POST",headers:auth(t),body:JSON.stringify({name:uid})})).json();
  return { token: t, familyId: fam.familyId };
}
// invitee joins fam (pending) then owner approves → active; returns invitee uid
async function addMember(o: { token: string; familyId: string }, uid: string) {
  const mk = await (await app.request(`/families/${o.familyId}/invites`, { method:"POST", headers: auth(o.token), body: JSON.stringify({ mode:"link", max_uses:5 }) })).json();
  const it = await token(uid);
  await app.request("/invites:redeem", { method:"POST", headers: auth(it), body: JSON.stringify({ token: mk.token }) });
  const pend = (await (await app.request(`/families/${o.familyId}/invites`, { headers: auth(o.token) })).json()).pending;
  const memberUid = pend[0].uid;
  await app.request(`/families/${o.familyId}/members/${memberUid}:approve`, { method:"POST", headers: auth(o.token) });
  return memberUid;
}

describe("DELETE /auth/me — soft delete", () => {
  it("soft-deletes a solo account and revokes its sessions", async () => {
    const o = await ownerOf("solo-del");
    const uid = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND role='owner'`, [o.familyId])).rows[0].user_id;
    const r = await app.request("/auth/me", { method: "DELETE", headers: auth(o.token) });
    expect(r.status).toBe(204);
    expect((await q(`SELECT deleted_at FROM users WHERE id=$1`, [uid])).rows[0].deleted_at).not.toBeNull();
    // the session is dead — the same token now 401s
    expect((await app.request("/auth/me/credentials", { headers: auth(o.token) })).status).toBe(401);
  });

  it("blocks a sole owner with other members, allows after transfer", async () => {
    const o = await ownerOf("transfer-del");
    const memberUid = await addMember(o, "transfer-member");
    // sole owner + another active member → must transfer first
    const blocked = await app.request("/auth/me", { method: "DELETE", headers: auth(o.token) });
    expect(blocked.status).toBe(409);
    expect((await blocked.json()).type).toBe("transfer-required");
    // promote the member → owner, then delete is allowed
    expect((await app.request(`/families/${o.familyId}/members/${memberUid}:promote`, { method:"POST", headers: auth(o.token) })).status).toBe(204);
    expect((await app.request("/auth/me", { method: "DELETE", headers: auth(o.token) })).status).toBe(204);
    // the promoted member is now an owner
    expect((await q(`SELECT role FROM memberships WHERE user_id=$1 AND family_id=$2`, [memberUid, o.familyId])).rows[0].role).toBe("owner");
  });

  it("401s without a token", async () => {
    expect((await app.request("/auth/me", { method: "DELETE" })).status).toBe(401);
  });
});
