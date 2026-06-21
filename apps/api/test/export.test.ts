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
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0005_invites.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
afterAll(async () => { await pool.end(); });

const dev = { "content-type":"application/json", authorization:"Bearer dev" };
async function ownerOf(uid: string) {
  const t = (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:uid})})).json()).access;
  const fam = await (await app.request("/families",{method:"POST",headers:{...dev,authorization:`Bearer ${t}`},body:JSON.stringify({name:uid})})).json();
  return { token: t, familyId: fam.familyId };
}

describe("GET /auth/me/export", () => {
  it("returns the caller's own data and never any secret", async () => {
    const o = await ownerOf("exportme");
    const r = await app.request("/auth/me/export", { headers: { ...dev, authorization: `Bearer ${o.token}` } });
    expect(r.status).toBe(200);
    const b = await r.json();
    expect(b.exported_at).toBeTruthy();
    expect(b.user.id).toBeTruthy();
    expect(Array.isArray(b.identities)).toBe(true);
    expect(b.memberships.some((m: any) => m.family_id === o.familyId && m.role === "owner")).toBe(true);
    expect(b.credentials.length).toBeGreaterThanOrEqual(1);
    // hard rule: NO secret material in the dump
    const blob = JSON.stringify(b).toLowerCase();
    for (const leaked of ["refresh_hash", "token_hash", "refresh_tokens", "secret", '"hash"'])
      expect(blob).not.toContain(leaked);
  });

  it("401s without a token", async () => {
    const r = await app.request("/auth/me/export");
    expect(r.status).toBe(401);
  });
});
