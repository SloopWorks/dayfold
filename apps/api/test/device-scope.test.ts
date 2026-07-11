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
const { resolveGrants, requireScope } = await import("../src/auth/scope.ts");
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

// Mint an owner of a fresh family via dev-token + POST /families (mirrors device-approve.test.ts)
async function ownerOf(uid: string) {
  const dev = { "content-type": "application/json", authorization: "Bearer dev" };
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  const fam = await (await app.request("/families", { method: "POST", headers: { ...dev, authorization: `Bearer ${t}` }, body: JSON.stringify({ name: uid }) })).json();
  return { token: t as string, familyId: fam.familyId as string };
}
const approveWith = (fid: string, tok: string, body: unknown) =>
  app.request(`/families/${fid}/device/approve`, { method: "POST", headers: { ...J, authorization: `Bearer ${tok}` }, body: JSON.stringify(body) });

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

describe("device/approve accepts + validates a per-hub scope selection (ADR 0029 T2)", () => {
  it("{scope:hubs, hubs:[H1]} -> approve stores granted_scopes; redeemed token can write H1, not H2 or content", async () => {
    const owner = await ownerOf("scopeOwner1");
    await q(`INSERT INTO hubs(id, family_id, type, title) VALUES ('H1', $1, 'other', 'Hub 1')`, [owner.familyId]);
    const a = await (await authorize()).json();
    const res = await approveWith(owner.familyId, owner.token, { user_code: a.user_code, scope: "hubs", hubs: ["H1"] });
    expect(res.status).toBe(204);
    const row = (await q(`SELECT granted_scopes FROM device_authorizations WHERE device_code=$1`, [a.device_code])).rows[0];
    expect(row.granted_scopes.sort()).toEqual(["hub:H1:read", "hub:H1:write"]);
    const r = await tokenReq(a.device_code); const t = await r.json();
    expect(r.status).toBe(200);
    const credId = (await q(`SELECT credential_id FROM device_authorizations WHERE device_code=$1`, [a.device_code])).rows[0].credential_id;
    expect(await requireScope(credId, "hub:H1", "write")).toBe(true);
    expect(await requireScope(credId, "hub:H2", "write")).toBe(false);
    expect(await requireScope(credId, "content", "write")).toBe(false);
  });

  it("unknown/other-family hub -> 400 bad-scope", async () => {
    const owner = await ownerOf("scopeOwner2");
    const other = await ownerOf("scopeOwner2other");
    await q(`INSERT INTO hubs(id, family_id, type, title) VALUES ('H2', $1, 'other', 'Hub 2')`, [other.familyId]);
    const a = await (await authorize()).json();
    const res = await approveWith(owner.familyId, owner.token, { user_code: a.user_code, scope: "hubs", hubs: ["H2"] });
    expect(res.status).toBe(400);
    expect((await res.json()).type).toBe("bad-scope");

    const a2 = await (await authorize()).json();
    const res2 = await approveWith(owner.familyId, owner.token, { user_code: a2.user_code, scope: "hubs", hubs: ["nonexistent"] });
    expect(res2.status).toBe(400);
  });

  it("{scope:hubs, hubs:[]} -> 400 bad-scope", async () => {
    const owner = await ownerOf("scopeOwner3");
    const a = await (await authorize()).json();
    const res = await approveWith(owner.familyId, owner.token, { user_code: a.user_code, scope: "hubs", hubs: [] });
    expect(res.status).toBe(400);
    expect((await res.json()).type).toBe("bad-scope");
  });

  it("malformed hub id (fails idError charset guard) -> 422", async () => {
    const owner = await ownerOf("scopeOwner4");
    const a = await (await authorize()).json();
    const res = await approveWith(owner.familyId, owner.token, { user_code: a.user_code, scope: "hubs", hubs: ["not a valid id!"] });
    expect(res.status).toBe(422);
  });

  it("absent body -> granted_scopes stays NULL (blanket, byte-identical to pre-existing behavior)", async () => {
    const owner = await ownerOf("scopeOwner5");
    const a = await (await authorize()).json();
    const res = await app.request(`/families/${owner.familyId}/device/approve`, {
      method: "POST",
      headers: { ...J, authorization: `Bearer ${owner.token}` },
      body: JSON.stringify({ user_code: a.user_code }),
    });
    expect(res.status).toBe(204);
    const row = (await q(`SELECT granted_scopes FROM device_authorizations WHERE device_code=$1`, [a.device_code])).rows[0];
    expect(row.granted_scopes).toBeNull();
  });

  it("{scope:full} -> granted_scopes stays NULL (blanket)", async () => {
    const owner = await ownerOf("scopeOwner6");
    const a = await (await authorize()).json();
    const res = await approveWith(owner.familyId, owner.token, { user_code: a.user_code, scope: "full" });
    expect(res.status).toBe(204);
    const row = (await q(`SELECT granted_scopes FROM device_authorizations WHERE device_code=$1`, [a.device_code])).rows[0];
    expect(row.granted_scopes).toBeNull();
  });
});
