import { describe, it, expect, beforeAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import {
  generateKeyPair, exportJWK, SignJWT, createLocalJWKSet, decodeJwt, type JWTVerifyGetKey,
} from "jose";

const here = dirname(fileURLToPath(import.meta.url));
const PROJECT = "dayfold-test";

// Backend signing env (same shape as auth-e2e) so importing app.ts/tokens.ts is happy.
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.AUTH_ISS = "https://fad.test/auth"; process.env.AUTH_AUD = "fad-api-test";
process.env.HOUSEHOLD_SECRET = "legacy-secret"; process.env.HOUSEHOLD_CREDENTIAL_ID = "hcred";
process.env.FIREBASE_PROJECT_ID = PROJECT;
const akp = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
const apriv = await exportJWK(akp.privateKey); apriv.kid = "k1"; apriv.alg = "EdDSA";
process.env.AUTH_SIGNING_KEY = JSON.stringify(apriv);

const { pool, q } = await import("../src/db.ts");
const { FirebaseVerifier } = await import("../src/auth/identity.ts");
const { app } = await import("../src/app.ts");

// --- Firebase-shaped token helpers --------------------------------------------
const ISS = `https://securetoken.google.com/${PROJECT}`;
const nowSec = () => Math.floor(Date.now() / 1000);

function fbClaims(over: Record<string, unknown> = {}) {
  return {
    iss: ISS, aud: PROJECT, sub: "firebaseuid123",
    auth_time: nowSec(), iat: nowSec(), exp: nowSec() + 3600,
    email: "a@b.com", email_verified: true,
    firebase: { sign_in_provider: "google.com", identities: { "google.com": ["G-9999"] } },
    ...over,
  };
}

// A real RS256-signed Firebase-style token + a JWKS that resolves its key (DI).
async function signedSetup() {
  const kp = await generateKeyPair("RS256", { extractable: true });
  const pub = await exportJWK(kp.publicKey); pub.kid = "fbk1"; pub.alg = "RS256";
  const jwks = createLocalJWKSet({ keys: [pub] }) as JWTVerifyGetKey;
  const sign = (claims: Record<string, unknown>) =>
    new SignJWT(claims).setProtectedHeader({ alg: "RS256", kid: "fbk1" }).sign(kp.privateKey);
  return { jwks, sign };
}

// Emulator tokens are UNSIGNED (alg "none"): header.payload. with empty signature.
function unsignedToken(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "none", typ: "JWT" })}.${b64(claims)}.`;
}

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql", "0002_auth.sql", "0003_device_grant.sql",
    "0004_refresh_grace.sql", "0005_invites.sql", "0006_typed_content.sql", "0007_related.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql"])
    await q(readFileSync(resolve(here, "../migrations/" + m), "utf8"));
});

describe("FirebaseVerifier — signed (RS256) path", () => {
  it("verifies a valid token and binds to the underlying provider uid", async () => {
    const { jwks, sign } = await signedSetup();
    const v = new FirebaseVerifier({ projectId: PROJECT, jwks });
    const idn = await v.verify(await sign(fbClaims()));
    expect(idn).toEqual({ provider: "google.com", provider_uid: "G-9999", email_verified: true });
  });

  it("falls back to sub when identities[provider] is absent", async () => {
    const { jwks, sign } = await signedSetup();
    const v = new FirebaseVerifier({ projectId: PROJECT, jwks });
    const claims = fbClaims({ firebase: { sign_in_provider: "apple.com", identities: {} }, sub: "fbuid-apple" });
    const idn = await v.verify(await sign(claims));
    expect(idn).toEqual({ provider: "apple.com", provider_uid: "fbuid-apple", email_verified: true });
  });

  it("rejects a token for a different audience/project", async () => {
    const { jwks, sign } = await signedSetup();
    const v = new FirebaseVerifier({ projectId: PROJECT, jwks });
    await expect(v.verify(await sign(fbClaims({ aud: "other-project" })))).rejects.toThrow();
  });

  it("rejects a wrong-issuer token", async () => {
    const { jwks, sign } = await signedSetup();
    const v = new FirebaseVerifier({ projectId: PROJECT, jwks });
    await expect(v.verify(await sign(fbClaims({ iss: "https://evil.example/x" })))).rejects.toThrow();
  });

  it("rejects an expired token", async () => {
    const { jwks, sign } = await signedSetup();
    const v = new FirebaseVerifier({ projectId: PROJECT, jwks });
    await expect(v.verify(await sign(fbClaims({ exp: nowSec() - 10, iat: nowSec() - 3600 })))).rejects.toThrow();
  });

  it("rejects a token signed by a key not in the JWKS (forgery)", async () => {
    const { jwks } = await signedSetup();
    const other = await generateKeyPair("RS256", { extractable: true });
    const forged = await new SignJWT(fbClaims())
      .setProtectedHeader({ alg: "RS256", kid: "fbk1" }).sign(other.privateKey);
    const v = new FirebaseVerifier({ projectId: PROJECT, jwks });
    await expect(v.verify(forged)).rejects.toThrow();
  });

  it("rejects anonymous sign-in", async () => {
    const { jwks, sign } = await signedSetup();
    const v = new FirebaseVerifier({ projectId: PROJECT, jwks });
    const claims = fbClaims({ firebase: { sign_in_provider: "anonymous", identities: {} } });
    await expect(v.verify(await sign(claims))).rejects.toThrow();
  });
});

describe("FirebaseVerifier — emulator (unsigned) path", () => {
  it("accepts an unsigned emulator token by claims only", async () => {
    const v = new FirebaseVerifier({ projectId: PROJECT, emulator: true });
    const idn = await v.verify(unsignedToken(fbClaims()));
    expect(idn.provider).toBe("google.com");
    expect(idn.provider_uid).toBe("G-9999");
  });

  it("still enforces aud/iss/exp in emulator mode", async () => {
    const v = new FirebaseVerifier({ projectId: PROJECT, emulator: true });
    await expect(v.verify(unsignedToken(fbClaims({ aud: "nope" })))).rejects.toThrow();
    await expect(v.verify(unsignedToken(fbClaims({ exp: nowSec() - 5 })))).rejects.toThrow();
  });
});

describe("POST /auth/firebase — full wiring (emulator mode)", () => {
  const hdr = { "content-type": "application/json" };

  it("returns 400 without an idToken", async () => {
    const r = await app.request("/auth/firebase", { method: "POST", headers: hdr, body: "{}" });
    expect(r.status).toBe(400);
  });

  it("verifies an emulator token → mints our access + refresh, creates the user", async () => {
    process.env.FIREBASE_AUTH_EMULATOR_HOST = "127.0.0.1:9099";
    const idToken = unsignedToken(fbClaims({ sub: "fb-newuser", firebase: { sign_in_provider: "google.com", identities: { "google.com": ["G-NEW-1"] } } }));
    const r = await app.request("/auth/firebase", { method: "POST", headers: hdr, body: JSON.stringify({ idToken }) });
    expect(r.status).toBe(200);
    const j = await r.json();
    expect(typeof j.access).toBe("string");
    expect(typeof j.refresh).toBe("string");
    // access is OUR EdDSA token; sub = a freshly-created usr_ id, cid present
    const claims = decodeJwt(j.access);
    expect(String(claims.sub)).toMatch(/^usr_/);
    expect(claims.cid).toBeTruthy();
    // identity row keyed on the underlying provider uid
    const u = await q(`SELECT provider, provider_uid FROM user_identities WHERE provider_uid='G-NEW-1'`);
    expect(u.rowCount).toBe(1);
    expect(u.rows[0].provider).toBe("google.com");
    delete process.env.FIREBASE_AUTH_EMULATOR_HOST;
  });

  it("rejects a bad token with 401", async () => {
    process.env.FIREBASE_AUTH_EMULATOR_HOST = "127.0.0.1:9099";
    const r = await app.request("/auth/firebase", { method: "POST", headers: hdr, body: JSON.stringify({ idToken: unsignedToken(fbClaims({ iss: "https://evil/x" })) }) });
    expect(r.status).toBe(401);
    delete process.env.FIREBASE_AUTH_EMULATOR_HOST;
  });
});
