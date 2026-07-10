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
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0005_invites.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql","0017_user_avatar.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
afterAll(async () => { await pool.end(); });

const dev = { "content-type":"application/json", authorization:"Bearer dev" };
async function ownerOf(uid: string) {
  const t = (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:uid})})).json()).access;
  const fam = await (await app.request("/families",{method:"POST",headers:{...dev,authorization:`Bearer ${t}`},body:JSON.stringify({name:uid})})).json();
  const me = await (await app.request("/auth/me", { headers: { authorization: `Bearer ${t}` } })).json();
  return { token: t, familyId: fam.familyId, userId: me.user_id as string };
}

describe("GET /families/:fid/members", () => {
  it("returns the active roster to a member", async () => {
    const o = await ownerOf("rosterowner");
    const r = await app.request(`/families/${o.familyId}/members`, { headers: { ...dev, authorization: `Bearer ${o.token}` } });
    expect(r.status).toBe(200);
    const b = await r.json();
    expect(b.members.length).toBe(1);
    expect(b.members[0].uid).toBeTruthy();
    expect(b.members[0].role).toBe("owner");
    expect(b.members[0].status).toBe("active");
    // no avatar set yet — fields present but null (nullable, additive)
    expect(b.members[0].avatar_color).toBeNull();
    expect(b.members[0].avatar_ref).toBeNull();
  });

  it("roster rows carry avatar fields once set via PATCH /auth/me", async () => {
    const o = await ownerOf("rosteravatar");
    await app.request("/auth/me", {
      method: "PATCH",
      headers: { ...dev, authorization: `Bearer ${o.token}` },
      body: JSON.stringify({ avatar_ref: "avatar:fox-01", avatar_color: "teal" }),
    });
    const r = await app.request(`/families/${o.familyId}/members`, { headers: { ...dev, authorization: `Bearer ${o.token}` } });
    const b = await r.json();
    const me = b.members.find((m: any) => m.uid === o.userId);
    expect(me.avatar_ref).toBe("avatar:fox-01");
    expect(me.avatar_color).toBe("teal");
  });

  it("404s for a non-member (tenant isolation, no leak)", async () => {
    const o = await ownerOf("isoowner");
    const outsider = await ownerOf("isooutsider");
    const r = await app.request(`/families/${o.familyId}/members`, { headers: { ...dev, authorization: `Bearer ${outsider.token}` } });
    expect(r.status).toBe(404);
  });

  it("401s without a token", async () => {
    const o = await ownerOf("noauth");
    const r = await app.request(`/families/${o.familyId}/members`);
    expect([401, 404]).toContain(r.status);   // default-deny
  });
});
