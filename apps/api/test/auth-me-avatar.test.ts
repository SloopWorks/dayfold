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
async function token(uid: string) {
  return (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:uid})})).json()).access;
}
const auth = (t: string) => ({ ...dev, authorization: `Bearer ${t}` });

describe("GET/PATCH /auth/me avatar", () => {
  it("GET includes avatar_color/avatar_ref as null by default", async () => {
    const t = await token("avatar-user-0");
    const g = await (await app.request("/auth/me", { headers: auth(t) })).json();
    expect(g.avatar_color).toBeNull();
    expect(g.avatar_ref).toBeNull();
  });

  it("sets and returns a bundled avatar_ref + color", async () => {
    const t = await token("avatar-user-1");
    const p = await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify({ avatar_ref: "avatar:fox-01", avatar_color: "teal" }) });
    expect(p.status).toBe(200);
    const pj = await p.json();
    expect(pj.avatar_ref).toBe("avatar:fox-01");
    expect(pj.avatar_color).toBe("teal");
    const g = await (await app.request("/auth/me", { headers: auth(t) })).json();
    expect(g.avatar_ref).toBe("avatar:fox-01");
    expect(g.avatar_color).toBe("teal");
  });

  it("rejects a malformed avatar_ref (URL, not a bundled id)", async () => {
    const t = await token("avatar-user-2");
    const r = await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify({ avatar_ref: "http://evil/x.png" }) });
    expect(r.status).toBe(400);
    expect((await r.json()).type).toBe("bad-avatar");
  });

  it("rejects an over-long avatar_color", async () => {
    const t = await token("avatar-user-3");
    const r = await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify({ avatar_color: "x".repeat(33) }) });
    expect(r.status).toBe(400);
    expect((await r.json()).type).toBe("bad-avatar");
  });

  it("clears avatar_ref with null", async () => {
    const t = await token("avatar-user-4");
    await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify({ avatar_ref: "avatar:fox-01" }) });
    const cleared = await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify({ avatar_ref: null }) });
    expect(cleared.status).toBe(200);
    expect((await cleared.json()).avatar_ref).toBeNull();
    const g = await (await app.request("/auth/me", { headers: auth(t) })).json();
    expect(g.avatar_ref).toBeNull();
  });

  it("updates only the provided subset, leaving other fields untouched", async () => {
    const t = await token("avatar-user-5");
    await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify({ display_name: "Sub Setter", avatar_ref: "avatar:owl-02", avatar_color: "coral" }) });
    const p = await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify({ display_name: "Sub Setter 2" }) });
    expect(p.status).toBe(200);
    const pj = await p.json();
    expect(pj.display_name).toBe("Sub Setter 2");
    expect(pj.avatar_ref).toBe("avatar:owl-02");
    expect(pj.avatar_color).toBe("coral");
  });

  it("treats a non-object JSON body (e.g. a bare number) as an empty patch, not a 500", async () => {
    const t = await token("avatar-user-6");
    const r = await app.request("/auth/me", { method: "PATCH", headers: auth(t), body: JSON.stringify(42) });
    expect(r.status).toBe(200);
    const rj = await r.json();
    expect(rj.avatar_ref).toBeNull();
    expect(rj.avatar_color).toBeNull();
  });
});
