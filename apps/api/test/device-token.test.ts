import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { generateKeyPair, exportJWK } from "jose";
const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.AUTH_ISS = "https://fad.test/auth"; process.env.AUTH_AUD = "fad-api-test";
const kp = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
const priv = await exportJWK(kp.privateKey); priv.kid = "k1"; priv.alg = "EdDSA";
process.env.AUTH_SIGNING_KEY = JSON.stringify(priv);
const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");
const J = { "content-type": "application/json" };

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
  await q(`INSERT INTO families(id,name) VALUES ('famA','A')`);
  await q(`INSERT INTO users(id) VALUES ('uA')`);
});
afterAll(async () => { await pool.end(); });

const authorize = () => app.request("/device/authorize", { method: "POST", headers: J, body: "{}" });
const tokenReq = (device_code: string) =>
  app.request("/device/token", { method: "POST", headers: J,
    body: JSON.stringify({ grant_type: "urn:ietf:params:oauth:grant-type:device_code", device_code }) });

describe("/device/authorize + /device/token", () => {
  it("authorize returns codes with the right shape", async () => {
    const r = await authorize(); const b = await r.json();
    expect(b.user_code).toMatch(/^[23456789CFGHJMPQRVWX]{4}-[23456789CFGHJMPQRVWX]{4}$/);
    expect(b.device_code.length).toBeGreaterThan(20);
    expect(b.interval).toBe(5); expect(b.expires_in).toBe(600);
  });
  it("token: pending -> authorization_pending; fast re-poll -> slow_down", async () => {
    const a = await (await authorize()).json();
    expect((await (await tokenReq(a.device_code)).json()).error).toBe("authorization_pending");
    expect((await (await tokenReq(a.device_code)).json()).error).toBe("slow_down"); // immediate re-poll
  });
  it("token: approved -> mints {access_token, refresh_token}; one-time", async () => {
    const a = await (await authorize()).json();
    // seed approval directly (approve endpoint is Task 5)
    await q(`UPDATE device_authorizations SET status='approved', user_id='uA', family_id='famA', approved_at=now() WHERE device_code=$1`, [a.device_code]);
    const r1 = await tokenReq(a.device_code); const t1 = await r1.json();
    expect(r1.status).toBe(200); expect(t1.access_token).toBeTruthy(); expect(t1.refresh_token).toBeTruthy();
    // minted credential is kind='cli', family-scoped, content scopes (array)
    const cred = await q(`SELECT kind, family_scope, scopes FROM credentials WHERE id=(SELECT credential_id FROM device_authorizations WHERE device_code=$1)`, [a.device_code]);
    expect(cred.rows[0].kind).toBe("cli");
    expect(cred.rows[0].family_scope).toBe("famA");
    expect(cred.rows[0].scopes).toContain("content:write");
    // one-time: second redeem fails
    expect((await (await tokenReq(a.device_code)).json()).error).toBe("expired_token");
  });
  it("token: expired -> expired_token", async () => {
    const a = await (await authorize()).json();
    await q(`UPDATE device_authorizations SET expires_at=now()-interval '1 min' WHERE device_code=$1`, [a.device_code]);
    expect((await (await tokenReq(a.device_code)).json()).error).toBe("expired_token");
  });
});
