// EdDSA (Ed25519) access tokens. Single alg; env-pinned iss/aud; kid allowlist
// built from env at boot (self-published JWKS — no external fetch). 04-auth.
import { SignJWT, jwtVerify, importJWK, type JWK } from "jose";
import { randomUUID } from "node:crypto";

const AUTH_SIGNING_KEY = process.env.AUTH_SIGNING_KEY;
const AUTH_ISS = process.env.AUTH_ISS;
const AUTH_AUD = process.env.AUTH_AUD;
if (!AUTH_SIGNING_KEY) throw new Error("Missing required env var: AUTH_SIGNING_KEY");
if (!AUTH_ISS) throw new Error("Missing required env var: AUTH_ISS");
if (!AUTH_AUD) throw new Error("Missing required env var: AUTH_AUD");

const ISS = AUTH_ISS;
const AUD = AUTH_AUD;
const ACCESS_TTL = "5m";
// 30s clock tolerance for normal multi-node clock drift (applies to exp and nbf).
const LEEWAY = 30; // seconds

const privJwk: JWK & { kid: string } = JSON.parse(AUTH_SIGNING_KEY);
const KID = privJwk.kid;
const ALLOWLIST = new Set([KID]); // in-memory, deterministic per deploy

const privKeyP = importJWK({ ...privJwk, alg: "EdDSA" }, "EdDSA");
// public JWK = private minus `d`
const pubJwk: JWK = (() => { const { d, ...pub } = privJwk as any; return { ...pub, alg: "EdDSA", use: "sig" }; })();
const pubKeyP = importJWK(pubJwk, "EdDSA");

export async function mintAccess({ sub, cid }: { sub: string; cid: string }): Promise<string> {
  return new SignJWT({ cid })
    .setProtectedHeader({ alg: "EdDSA", kid: KID })
    .setSubject(sub).setIssuer(ISS).setAudience(AUD)
    .setIssuedAt().setJti(randomUUID()).setExpirationTime(ACCESS_TTL)
    .sign(await privKeyP);
}

export async function verifyAccess(token: string): Promise<{ sub: string; cid: string; jti: string }> {
  const key = await pubKeyP;
  const { payload, protectedHeader } = await jwtVerify(token, key, {
    algorithms: ["EdDSA"], issuer: ISS, audience: AUD, clockTolerance: LEEWAY,
  });
  if (!protectedHeader.kid || !ALLOWLIST.has(protectedHeader.kid)) throw new Error("bad kid");
  return { sub: String(payload.sub), cid: String((payload as any).cid), jti: String(payload.jti) };
}

export async function jwks(): Promise<{ keys: JWK[] }> {
  return { keys: [pubJwk] };
}
