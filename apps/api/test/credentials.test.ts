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
// two distinct app sessions (→ two credentials) for the same user
async function session(uid: string) {
  return (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:uid})})).json()).access;
}
const auth = (t: string) => ({ ...dev, authorization: `Bearer ${t}` });

describe("connected devices — GET/DELETE /auth/me/credentials", () => {
  it("lists the caller's active credentials and flags the current one", async () => {
    const a = await session("dev-alice");   // session 1
    await session("dev-alice");             // session 2 (same user, 2nd credential)
    const r = await app.request("/auth/me/credentials", { headers: auth(a) });
    expect(r.status).toBe(200);
    const b = await r.json();
    expect(b.credentials.length).toBeGreaterThanOrEqual(2);
    expect(b.credentials.filter((x: any) => x.current).length).toBe(1);   // exactly one current
    expect(JSON.stringify(b).toLowerCase()).not.toContain("hash");
  });

  it("revokes another of the caller's sessions, then it's gone from the list", async () => {
    const a = await session("dev-bob");      // current
    await session("dev-bob");
    const list = (await (await app.request("/auth/me/credentials", { headers: auth(a) })).json()).credentials;
    const other = list.find((x: any) => !x.current);
    const del = await app.request(`/auth/me/credentials/${other.id}`, { method: "DELETE", headers: auth(a) });
    expect(del.status).toBe(204);
    const after = (await (await app.request("/auth/me/credentials", { headers: auth(a) })).json()).credentials;
    expect(after.find((x: any) => x.id === other.id)).toBeUndefined();
  });

  it("can't revoke another user's credential (anti-IDOR → 404)", async () => {
    const a = await session("dev-carol");
    const victim = await session("dev-victim");
    const victimCred = (await (await app.request("/auth/me/credentials", { headers: auth(victim) })).json()).credentials[0].id;
    const r = await app.request(`/auth/me/credentials/${victimCred}`, { method: "DELETE", headers: auth(a) });
    expect(r.status).toBe(404);   // not yours
  });

  it("401s without a token", async () => {
    expect((await app.request("/auth/me/credentials")).status).toBe(401);
  });
});
