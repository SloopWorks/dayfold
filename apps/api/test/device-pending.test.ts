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
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0006_typed_content.sql","0007_related.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
afterAll(async () => { await pool.end(); });

const dev = { "content-type": "application/json", authorization: "Bearer dev" };
// A signed-in session (app credential) — what the owner's phone carries.
async function session(uid: string) {
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  return t as string;
}
// Start a device authorization from a given origin IP (controls origin_kind).
async function authorizeFrom(ip: string) {
  return (await (await app.request("/device/authorize", { method: "POST", headers: { "content-type": "application/json", "x-forwarded-for": ip }, body: "{}" })).json());
}
const pending = (tok: string | null, code: string) =>
  app.request(`/device/pending?user_code=${encodeURIComponent(code)}`, { headers: tok ? { authorization: `Bearer ${tok}` } : {} });

describe("GET /device/pending — consent preview", () => {
  it("hit: returns the preview (no secrets) + datacenter origin_kind + audits device.lookup", async () => {
    const tok = await session("alice");
    const a = await authorizeFrom("52.1.2.3"); // AWS range → datacenter
    const r = await pending(tok, a.user_code);
    expect(r.status).toBe(200);
    const body = await r.json();
    expect(body.user_code).toBe(a.user_code);
    expect(body.client).toBe("dayfold-cli");
    expect(body.origin_ip).toBe("52.1.2.3");
    expect(body.origin_kind).toBe("datacenter");
    expect(body.expires_at).toBeTruthy();
    // never leak the secret half
    expect(body.device_code).toBeUndefined();
    expect(body.user_id).toBeUndefined();
    expect(body.credential_id).toBeUndefined();
    const aud = await q(`SELECT 1 FROM audit_log WHERE event='device.lookup' AND detail->>'user_code'=$1`, [a.user_code]);
    expect(aud.rowCount).toBe(1);
  });

  it("residential origin → origin_kind residential", async () => {
    const tok = await session("rita");
    const a = await authorizeFrom("24.1.2.3"); // not in the datacenter list
    const body = await (await pending(tok, a.user_code)).json();
    expect(body.origin_kind).toBe("residential");
  });

  it("miss → uniform 404 not-found", async () => {
    const tok = await session("mia");
    const r = await pending(tok, "ZZZZ-ZZZZ");
    expect(r.status).toBe(404);
    expect((await r.json()).type).toBe("not-found");
  });

  it("expired pending → uniform 404 (not visible)", async () => {
    const tok = await session("eli");
    const a = await authorizeFrom("8.8.8.8");
    await q(`UPDATE device_authorizations SET expires_at = now() - interval '1 minute' WHERE user_code=$1`, [a.user_code]);
    expect((await pending(tok, a.user_code)).status).toBe(404);
  });

  it("no session → 401; bad token → 401", async () => {
    const a = await authorizeFrom("9.9.9.9");
    expect((await pending(null, a.user_code)).status).toBe(401);
    expect((await pending("garbage", a.user_code)).status).toBe(401);
  });

  it("missing user_code → 400", async () => {
    const tok = await session("nan");
    const r = await app.request(`/device/pending`, { headers: { authorization: `Bearer ${tok}` } });
    expect(r.status).toBe(400);
  });

  it("shared account:approve lockout — 5 bad lookups then 429 (same budget as approve)", async () => {
    const tok = await session("lock");
    let last: Response | undefined;
    for (let i = 0; i < 5; i++) last = await pending(tok, "QQQQ-QQQQ");
    expect([404, 429]).toContain(last!.status);
    expect((await pending(tok, "QQQQ-QQQQ")).status).toBe(429);
  });
});
