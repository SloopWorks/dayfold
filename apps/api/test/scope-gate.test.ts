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
const { mintAccess } = await import("../src/auth/tokens.ts");

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0006_typed_content.sql","0007_related.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
afterAll(async () => { await pool.end(); });

const dev = { "content-type": "application/json", authorization: "Bearer dev" };
// owner of a fresh family + that owner's user_id
async function ownerOf(uid: string) {
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  const headers = { ...dev, authorization: `Bearer ${t}` };
  const fam = await (await app.request("/families", { method: "POST", headers, body: JSON.stringify({ name: uid }) })).json();
  const me = await (await app.request("/auth/me", { headers: { authorization: `Bearer ${t}` } })).json();
  return { token: t, familyId: fam.familyId as string, userId: me.user_id as string };
}
// Mint an access token for a brand-new credential with the given scopes.
async function tokenWithScopes(userId: string, familyId: string, scopes: string[]) {
  const cid = "cred_scope_" + Math.random().toString(16).slice(2);
  await q(`INSERT INTO credentials(id,user_id,family_scope,kind,scopes) VALUES ($1,$2,$3,'cli',$4)`,
    [cid, userId, familyId, `{${scopes.join(",")}}`]);
  return mintAccess({ sub: userId, cid });
}
const get = (path: string, tok: string) => app.request(path, { headers: { authorization: `Bearer ${tok}` } });

describe("content scope gate (ADR 0029)", () => {
  it("read routes require content:read — scopeless cred → 403, read cred → 200", async () => {
    const o = await ownerOf("sgrid");
    const none = await tokenWithScopes(o.userId, o.familyId, []);
    expect((await get(`/families/${o.familyId}/cards`, none)).status).toBe(403);
    expect((await get(`/families/${o.familyId}/sync`, none)).status).toBe(403);
    const ro = await tokenWithScopes(o.userId, o.familyId, ["content:read"]);
    expect((await get(`/families/${o.familyId}/cards`, ro)).status).toBe(200);
    expect((await get(`/families/${o.familyId}/sync`, ro)).status).toBe(200);
  });

  it("read-only cred cannot write — PUT/DELETE require content:write → 403", async () => {
    const o = await ownerOf("wgrid");
    const ro = await tokenWithScopes(o.userId, o.familyId, ["content:read"]);
    const put = await app.request(`/families/${o.familyId}/cards/c1`, {
      method: "PUT", headers: { "content-type": "application/json", authorization: `Bearer ${ro}` },
      body: JSON.stringify({ kind: "info", title: "x", provenance: { source: "cli", at: "2026-06-18T10:00:00Z" } }),
    });
    expect(put.status).toBe(403);
    const del = await app.request(`/families/${o.familyId}/cards/c1`, { method: "DELETE", headers: { authorization: `Bearer ${ro}` } });
    expect(del.status).toBe(403);
  });

  it("read+write cred can write", async () => {
    const o = await ownerOf("rwgrid");
    const rw = await tokenWithScopes(o.userId, o.familyId, ["content:read", "content:write"]);
    const put = await app.request(`/families/${o.familyId}/cards/c1`, {
      method: "PUT", headers: { "content-type": "application/json", authorization: `Bearer ${rw}` },
      body: JSON.stringify({ kind: "info", title: "x", provenance: { source: "cli", at: "2026-06-18T10:00:00Z" } }),
    });
    expect(put.status).toBe(200);
  });
});
