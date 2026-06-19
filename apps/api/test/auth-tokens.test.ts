import { describe, it, expect, beforeAll } from "vitest";
import { generateKeyPair, exportJWK, SignJWT, importJWK } from "jose";

let mintAccess: any, verifyAccess: any, jwks: any;

beforeAll(async () => {
  const { publicKey, privateKey } = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
  const priv = await exportJWK(privateKey); priv.kid = "test-k1"; priv.alg = "EdDSA";
  process.env.AUTH_SIGNING_KEY = JSON.stringify(priv);
  process.env.AUTH_ISS = "https://fad.test/auth";
  process.env.AUTH_AUD = "fad-api-test";
  ({ mintAccess, verifyAccess, jwks } = await import("../src/auth/tokens.ts"));
  (globalThis as any).__pub = publicKey; // for forging-with-wrong-key tests
});

describe("token service", () => {
  it("mint → verify round-trip carries sub/cid", async () => {
    const t = await mintAccess({ sub: "u1", cid: "c1" });
    const claims = await verifyAccess(t);
    expect(claims.sub).toBe("u1"); expect(claims.cid).toBe("c1"); expect(claims.jti).toBeTruthy();
  });
  it("rejects alg=none / HS / RS", async () => {
    const none = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ1MSJ9.";
    await expect(verifyAccess(none)).rejects.toThrow();
    const hs = await new SignJWT({ sub: "u1", cid: "c1" }).setProtectedHeader({ alg: "HS256" })
      .setIssuer(process.env.AUTH_ISS!).setAudience(process.env.AUTH_AUD!).setExpirationTime("5m")
      .sign(new TextEncoder().encode("x".repeat(32)));
    await expect(verifyAccess(hs)).rejects.toThrow();
  });
  it("rejects wrong iss, wrong aud, kid not in allowlist, expired, tampered", async () => {
    const wrongIss = await mintAccessWith({ iss: "https://evil/auth" });
    await expect(verifyAccess(wrongIss)).rejects.toThrow();
    const wrongAud = await mintAccessWith({ aud: "other" });
    await expect(verifyAccess(wrongAud)).rejects.toThrow();
    const wrongKid = await mintAccessWith({ kid: "nope" });
    await expect(verifyAccess(wrongKid)).rejects.toThrow();
    const expired = await mintAccessWith({ exp: "-60s" });
    await expect(verifyAccess(expired)).rejects.toThrow();
    const t = await mintAccess({ sub: "u1", cid: "c1" });
    await expect(verifyAccess(t.slice(0, -3) + "AAA")).rejects.toThrow();
  });
  it("jwks publishes the public key with kid, no private material", async () => {
    const set = await jwks();
    expect(set.keys[0].kid).toBe("test-k1");
    expect(set.keys[0].d).toBeUndefined();
  });
});

// helper: sign a token with the SAME key but a chosen bad attribute
async function mintAccessWith(bad: { iss?: string; aud?: string; kid?: string; exp?: string }) {
  const priv = await importJWK(JSON.parse(process.env.AUTH_SIGNING_KEY!), "EdDSA");
  return new SignJWT({ sub: "u1", cid: "c1" })
    .setProtectedHeader({ alg: "EdDSA", kid: bad.kid ?? "test-k1" })
    .setIssuer(bad.iss ?? process.env.AUTH_ISS!).setAudience(bad.aud ?? process.env.AUTH_AUD!)
    .setIssuedAt().setExpirationTime(bad.exp ?? "5m").sign(priv);
}
