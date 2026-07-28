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

const dev = { "content-type":"application/json", authorization:"Bearer dev" };
async function token(uid: string) {
  return (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:uid})})).json()).access;
}
const auth = (t: string) => ({ ...dev, authorization: `Bearer ${t}` });

describe("GET/PATCH /auth/me", () => {
  it("gets the profile then persists an updated display name", async () => {
    const t = await token("profile-user");
    const g0 = await (await app.request("/auth/me", { headers: auth(t) })).json();
    expect(g0.user_id).toBeTruthy();
    const p = await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify({ display_name: "  Pat Jackson  " }) });
    expect(p.status).toBe(200);
    expect((await p.json()).display_name).toBe("Pat Jackson");   // trimmed
    const g1 = await (await app.request("/auth/me", { headers: auth(t) })).json();
    expect(g1.display_name).toBe("Pat Jackson");                 // persisted
  });

  it("rejects empty / over-long names", async () => {
    const t = await token("profile-bad");
    expect((await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify({ display_name: "   " }) })).status).toBe(400);
    expect((await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify({ display_name: "x".repeat(81) }) })).status).toBe(400);
  });

  it("401s without a token", async () => {
    expect((await app.request("/auth/me")).status).toBe(401);
    expect((await app.request("/auth/me", { method: "PATCH", headers: { "content-type": "application/json" }, body: "{}" })).status).toBe(401);
  });
});
