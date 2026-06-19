// EdDSA (Ed25519) access tokens. Single alg; env-pinned iss/aud; kid allowlist
// built from env at boot (self-published JWKS — no external fetch). 04-auth.
import { SignJWT, jwtVerify, importJWK, type JWK } from "jose";

const ISS = process.env.AUTH_ISS || "";
const AUD = process.env.AUTH_AUD || "";
const ACCESS_TTL = "5m";
// Note: clockTolerance in jose applies to both exp AND nbf.
// Setting to 0 so a 1s-expired token is correctly rejected in tests.
// In a multi-node prod deployment, consider maxClockSkew at the load-balancer level instead.
const LEEWAY = 0; // seconds

const privJwk: JWK & { kid: string } = JSON.parse(process.env.AUTH_SIGNING_KEY || "{}");
const KID = privJwk.kid;
const ALLOWLIST = new Set([KID]); // in-memory, deterministic per deploy

const privKeyP = importJWK({ ...privJwk, alg: "EdDSA" }, "EdDSA");
// public JWK = private minus `d`
const pubJwk: JWK = (() => { const { d, ...pub } = privJwk as any; return { ...pub, alg: "EdDSA", use: "sig" }; })();
const pubKeyP = importJWK(pubJwk, "EdDSA");

let jti = 0;
export async function mintAccess({ sub, cid }: { sub: string; cid: string }): Promise<string> {
  return new SignJWT({ cid })
    .setProtectedHeader({ alg: "EdDSA", kid: KID })
    .setSubject(sub).setIssuer(ISS).setAudience(AUD)
    .setIssuedAt().setJti(`${Date.now()}-${jti++}`).setExpirationTime(ACCESS_TTL)
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
  return { keys: [{ ...pubJwk, kid: KID }] };
}
