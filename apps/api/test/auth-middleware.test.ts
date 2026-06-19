import { describe, it, expect, beforeAll, afterAll, vi } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { generateKeyPair, exportJWK } from "jose";
const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.AUTH_ISS = "https://fad.test/auth"; process.env.AUTH_AUD = "fad-api-test";
process.env.HOUSEHOLD_SECRET = "legacy-secret"; process.env.HOUSEHOLD_CREDENTIAL_ID = "hcred";

const kp = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
const priv = await exportJWK(kp.privateKey); priv.kid = "k1"; priv.alg = "EdDSA";
process.env.AUTH_SIGNING_KEY = JSON.stringify(priv);

const { pool, q } = await import("../src/db.ts");
const { mintAccess } = await import("../src/auth/tokens.ts");
const { authorizeTenant } = await import("../src/auth/middleware.ts");

const ctx = (token?: string) => ({ req: { header: (h: string) => h.toLowerCase() === "authorization" && token ? `Bearer ${token}` : undefined } });

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  await q(readFileSync(resolve(here, "../migrations/0001_m0_init.sql"), "utf8"));
  await q(readFileSync(resolve(here, "../migrations/0002_auth.sql"), "utf8"));
  await q(`INSERT INTO families(id,name) VALUES ('famA','A'),('famB','B')`);
  await q(`INSERT INTO users(id) VALUES ('uA')`);
  await q(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ('uA','famA','owner','active')`);
  await q(`INSERT INTO credentials(id,user_id,family_scope,kind) VALUES ('cA','uA','famA','app')`);
  await q(`INSERT INTO credentials(id,kind,family_scope,scopes) VALUES ('hcred','cli','famA','{content:read,content:write}')`);
});
afterAll(async () => { await pool.end(); });

describe("authorizeTenant", () => {
  it("valid JWT + active membership → authorized", async () => {
    const t = await mintAccess({ sub: "uA", cid: "cA" });
    const r = await authorizeTenant(ctx(t), "famA") as any;
    expect(r.status).toBeUndefined(); expect(r.userId).toBe("uA"); expect(r.role).toBe("owner");
  });
  it("JWT user on another family → 404 (no membership there)", async () => {
    const t = await mintAccess({ sub: "uA", cid: "cA" });
    expect(await authorizeTenant(ctx(t), "famB")).toEqual({ status: 404 });
  });
  it("revoked credential → 401", async () => {
    await q(`UPDATE credentials SET revoked_at=now() WHERE id='cA'`);
    const t = await mintAccess({ sub: "uA", cid: "cA" });
    expect(await authorizeTenant(ctx(t), "famA")).toEqual({ status: 401 });
    await q(`UPDATE credentials SET revoked_at=NULL WHERE id='cA'`);
  });
  it("no token → 401", async () => { expect(await authorizeTenant(ctx(), "famA")).toEqual({ status: 401 }); });
  it("legacy household token works on its family, content-scope, no role", async () => {
    const r = await authorizeTenant(ctx("legacy-secret"), "famA") as any;
    expect(r.legacy).toBe(true); expect(r.role).toBeNull(); expect(r.scopes).toContain("content:write");
  });
  it("legacy token on another family → 404", async () => {
    expect(await authorizeTenant(ctx("legacy-secret"), "famB")).toEqual({ status: 404 });
  });

  // --- backfill tests ---

  it("inactive (removed) membership → 403", async () => {
    // Fresh user + family to avoid coupling with uA/famA state.
    await q(`INSERT INTO families(id,name) VALUES ('famX','X')`);
    await q(`INSERT INTO users(id) VALUES ('uX')`);
    await q(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ('uX','famX','adult','removed')`);
    await q(`INSERT INTO credentials(id,user_id,family_scope,kind) VALUES ('cX','uX','famX','app')`);
    const t = await mintAccess({ sub: "uX", cid: "cX" });
    expect(await authorizeTenant(ctx(t), "famX")).toEqual({ status: 403 });
  });

  it("garbage bearer token → 401", async () => {
    expect(await authorizeTenant(ctx("not-a-real-token"), "famA")).toEqual({ status: 401 });
  });

  it("JWT credential family_scope ≠ path family → 404", async () => {
    // uY has active membership on famA, but credential cY is scoped to famB.
    // Membership check passes; family_scope guard fires → 404.
    await q(`INSERT INTO users(id) VALUES ('uY')`);
    await q(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ('uY','famA','adult','active')`);
    await q(`INSERT INTO credentials(id,user_id,family_scope,kind) VALUES ('cY','uY','famB','app')`);
    const t = await mintAccess({ sub: "uY", cid: "cY" });
    expect(await authorizeTenant(ctx(t), "famA")).toEqual({ status: 404 });
  });

  it("fail-closed: DB error during JWT path → 401 (never fail-open)", async () => {
    // Spy on the `q` export so the second call (credential lookup) rejects once.
    // We import the db module through the already-loaded module graph.
    const dbMod = await import("../src/db.ts");
    const spy = vi.spyOn(dbMod, "q").mockRejectedValueOnce(new Error("simulated DB failure"));
    const t = await mintAccess({ sub: "uA", cid: "cA" });
    const result = await authorizeTenant(ctx(t), "famA");
    spy.mockRestore();
    expect(result).toEqual({ status: 401 });
  });
});
