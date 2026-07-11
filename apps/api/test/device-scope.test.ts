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
const { resolveGrants } = await import("../src/auth/scope.ts");
const J = { "content-type": "application/json" };

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql","0019_device_grant_scopes.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
  await q(`INSERT INTO families(id,name) VALUES ('famA','A')`);
  await q(`INSERT INTO users(id) VALUES ('uA')`);
});
afterAll(async () => { await pool.end(); });

const authorize = () => app.request("/device/authorize", { method: "POST", headers: J, body: "{}" });
const tokenReq = (device_code: string) =>
  app.request("/device/token", { method: "POST", headers: J,
    body: JSON.stringify({ grant_type: "urn:ietf:params:oauth:grant-type:device_code", device_code }) });

describe("device redeem mints granted_scopes (ADR 0029)", () => {
  it("restricted granted_scopes on the approved row -> redeem mints exactly that set (no content:write)", async () => {
    const a = await (await authorize()).json();
    await q(
      `UPDATE device_authorizations SET status='approved', user_id='uA', family_id='famA', approved_at=now(),
       granted_scopes='{hub:H1:read,hub:H1:write}' WHERE device_code=$1`,
      [a.device_code],
    );
    const r = await tokenReq(a.device_code); const t = await r.json();
    expect(r.status).toBe(200);
    const credId = (await q(`SELECT credential_id FROM device_authorizations WHERE device_code=$1`, [a.device_code])).rows[0].credential_id;
    const grants = (await resolveGrants(credId)).sort();
    expect(grants).toEqual(["hub:H1:read", "hub:H1:write"]);
    expect(grants).not.toContain("content:write");
    const cred = await q(`SELECT scopes FROM credentials WHERE id=$1`, [credId]);
    expect(cred.rows[0].scopes.sort()).toEqual(["hub:H1:read", "hub:H1:write"]);
  });

  it("null granted_scopes on the approved row -> redeem mints the blanket default", async () => {
    const a = await (await authorize()).json();
    await q(
      `UPDATE device_authorizations SET status='approved', user_id='uA', family_id='famA', approved_at=now(),
       granted_scopes=NULL WHERE device_code=$1`,
      [a.device_code],
    );
    const r = await tokenReq(a.device_code); const t = await r.json();
    expect(r.status).toBe(200);
    const credId = (await q(`SELECT credential_id FROM device_authorizations WHERE device_code=$1`, [a.device_code])).rows[0].credential_id;
    const grants = (await resolveGrants(credId)).sort();
    expect(grants).toEqual(["content:delete", "content:read", "content:write"]);
    const cred = await q(`SELECT scopes FROM credentials WHERE id=$1`, [credId]);
    expect(cred.rows[0].scopes.sort()).toEqual(["content:delete", "content:read", "content:write"]);
  });
});
