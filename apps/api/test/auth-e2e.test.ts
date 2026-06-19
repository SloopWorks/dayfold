import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { generateKeyPair, exportJWK } from "jose";
const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.AUTH_ISS = "https://fad.test/auth"; process.env.AUTH_AUD = "fad-api-test";
process.env.HOUSEHOLD_SECRET = "legacy-secret"; process.env.HOUSEHOLD_CREDENTIAL_ID = "hcred";
process.env.ENABLE_DEV_AUTH = "1"; process.env.DEV_AUTH_SECRET = "dev-key"; delete process.env.VERCEL_ENV;
const kp = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
const priv = await exportJWK(kp.privateKey); priv.kid = "k1"; priv.alg = "EdDSA";
process.env.AUTH_SIGNING_KEY = JSON.stringify(priv);
const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");

const dev = { "content-type": "application/json", authorization: "Bearer dev-key" };
async function devToken(provider_uid: string) {
  const r = await app.request("/auth/dev-token", { method: "POST", headers: dev,
    body: JSON.stringify({ provider: "dev", provider_uid }) });
  return (await r.json()).access as string;
}

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  await q(readFileSync(resolve(here, "../migrations/0001_m0_init.sql"), "utf8"));
  await q(readFileSync(resolve(here, "../migrations/0002_auth.sql"), "utf8"));
});
afterAll(async () => { await pool.end(); });

describe("auth E2E + multitenancy", () => {
  it("dev-token → create family → write a card with the JWT", async () => {
    const t = await devToken("alice");
    const fam = await (await app.request("/families", { method: "POST", headers: { ...dev, authorization: `Bearer ${t}` },
      body: JSON.stringify({ name: "Alice Fam" }) })).json();
    const put = await app.request(`/families/${fam.familyId}/cards/c1`, {
      method: "PUT", headers: { "content-type": "application/json", authorization: `Bearer ${t}` },
      body: JSON.stringify({ kind: "info", title: "Hi", provenance: { source: "dev", at: "2026-06-18T10:00:00Z" } }) });
    expect(put.status).toBe(200);
  });
  it("IDOR: Bob's token cannot read Alice's family → 404", async () => {
    const ta = await devToken("alice2");
    const fa = await (await app.request("/families", { method: "POST", headers: { ...dev, authorization: `Bearer ${ta}` }, body: JSON.stringify({ name: "A2" }) })).json();
    const tb = await devToken("bob");
    const r = await app.request(`/families/${fa.familyId}/cards`, { headers: { authorization: `Bearer ${tb}` } });
    expect(r.status).toBe(404);
  });
  it("dev-token refused in production and preview", async () => {
    process.env.VERCEL_ENV = "production";
    const p = await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: "x" }) });
    expect(p.status).toBe(404);
    process.env.VERCEL_ENV = "preview";
    const pv = await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: "x" }) });
    expect(pv.status).toBe(404);
    delete process.env.VERCEL_ENV;
  });
  it("dev-token refused without DEV_AUTH_SECRET", async () => {
    const r = await app.request("/auth/dev-token", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ provider: "dev", provider_uid: "x" }) });
    expect(r.status).toBe(401);
  });
  it("jwks publishes a key", async () => {
    const r = await (await app.request("/.well-known/jwks.json")).json();
    expect(r.keys[0].kid).toBe("k1");
  });
  it("refresh returns a usable new access token", async () => {
    const t = await devToken("refresh-user");
    const fam = await (await app.request("/families", { method: "POST", headers: { ...dev, authorization: `Bearer ${t}` },
      body: JSON.stringify({ name: "Refresh Fam" }) })).json();
    // get refresh token from dev-token response
    const tokenRes = await app.request("/auth/dev-token", { method: "POST", headers: dev,
      body: JSON.stringify({ provider: "dev", provider_uid: "refresh-user2" }) });
    const { access: origAccess, refresh: origRefresh } = await tokenRes.json();
    const fam2 = await (await app.request("/families", { method: "POST", headers: { ...dev, authorization: `Bearer ${origAccess}` },
      body: JSON.stringify({ name: "Refresh Fam2" }) })).json();
    // rotate the refresh token
    const rotRes = await app.request("/auth/refresh", { method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ refresh: origRefresh }) });
    expect(rotRes.status).toBe(200);
    const { access: newAccess, refresh: newRefresh } = await rotRes.json();
    expect(newAccess).toBeTruthy();
    expect(newRefresh).toBeTruthy();
    // new access token must work on a content route
    const r = await app.request(`/families/${fam2.familyId}/cards`, { headers: { authorization: `Bearer ${newAccess}` } });
    expect(r.status).toBe(200);
  });
});
