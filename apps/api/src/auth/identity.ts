// Pluggable identity boundary + user/family/credential creation. S1 uses
// StubVerifier (tests) + DevVerifier (gated dev-token). S2 (ADR 0023/0027):
// FirebaseVerifier verifies a Firebase ID token by direct JWKS check (no Admin
// SDK / no service-account secret), then findOrCreateUser mints OUR tokens.
import { randomBytes } from "node:crypto";
import { jwtVerify, decodeJwt, createRemoteJWKSet, type JWTVerifyGetKey } from "jose";
import { pool, q } from "../db.ts";

export interface Identity { provider: string; provider_uid: string; email_verified?: boolean }
export interface IdentityVerifier { verify(assertion: unknown): Promise<Identity> }

// Tests inject the identity directly.
export class StubVerifier implements IdentityVerifier {
  async verify(a: unknown): Promise<Identity> {
    const o = a as Identity;
    if (!o?.provider || !o?.provider_uid) throw new Error("bad stub identity");
    return { provider: o.provider, provider_uid: o.provider_uid, email_verified: !!o.email_verified };
  }
}

// Firebase ID-token verifier (ADR 0027: direct JWKS, not Admin SDK).
//
// A Firebase ID token is an RS256 JWT signed by Google's securetoken service.
// We verify signature against Google's published keys, then bind identity to the
// UNDERLYING provider uid (ADR 0011: never rely on Firebase auto-link; key on
// provider + provider_uid — Google account id / Apple `sub`, not the Firebase uid).
//
// Emulator mode: the Firebase Auth Emulator issues UNSIGNED tokens (alg "none").
// When FIREBASE_AUTH_EMULATOR_HOST is set we decode-and-validate claims without a
// signature check — mirroring the Admin SDK's own emulator behaviour — so CI can
// exercise this exact code path with emulator-minted tokens (ADR 0027 test topology).
export interface FirebaseVerifierOpts {
  projectId: string;
  jwks?: JWTVerifyGetKey;   // DI for tests; default = Google's remote JWKS
  emulator?: boolean;       // skip signature (emulator tokens are unsigned)
}

const FIREBASE_JWKS_URL =
  process.env.FIREBASE_JWKS_URI ||
  "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

export class FirebaseVerifier implements IdentityVerifier {
  private jwks: JWTVerifyGetKey;
  private opts: FirebaseVerifierOpts;
  // NB: no constructor parameter properties — Node's strip-only TS loader
  // (`node src/server.ts`) rejects them (ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX),
  // even though esbuild (vitest + the Vercel bundle) accepts them.
  constructor(opts: FirebaseVerifierOpts) {
    this.opts = opts;
    this.jwks = opts.jwks ?? createRemoteJWKSet(new URL(FIREBASE_JWKS_URL));
  }
  async verify(assertion: unknown): Promise<Identity> {
    const idToken =
      typeof assertion === "string" ? assertion : (assertion as { idToken?: string })?.idToken;
    if (!idToken) throw new Error("missing idToken");
    const iss = `https://securetoken.google.com/${this.opts.projectId}`;

    let payload: Record<string, unknown>;
    if (this.opts.emulator) {
      payload = decodeJwt(idToken) as Record<string, unknown>;
      if (payload.iss !== iss) throw new Error("bad iss");
      if (payload.aud !== this.opts.projectId) throw new Error("bad aud");
      const exp = Number(payload.exp);
      if (!exp || exp * 1000 < Date.now()) throw new Error("expired");
      if (!payload.sub) throw new Error("missing sub");
    } else {
      ({ payload } = (await jwtVerify(idToken, this.jwks, {
        algorithms: ["RS256"],
        issuer: iss,
        audience: this.opts.projectId,
      })) as unknown as { payload: Record<string, unknown> });
    }

    // Bind to the underlying provider identity, not the Firebase uid.
    const fb = (payload.firebase ?? {}) as {
      sign_in_provider?: string;
      identities?: Record<string, string[]>;
    };
    const provider = fb.sign_in_provider;
    if (!provider || provider === "anonymous") throw new Error("unsupported provider");
    const ids = fb.identities?.[provider];
    const providerUid = Array.isArray(ids) && ids[0] ? String(ids[0]) : String(payload.sub);
    return { provider, provider_uid: providerUid, email_verified: !!payload.email_verified };
  }
}

const id = (p: string) => p + "_" + randomBytes(9).toString("hex");

export async function findOrCreateUser(idn: Identity): Promise<{ userId: string }> {
  // atomic: insert identity (+ user) once; concurrent callers converge on the same user.
  const existing = await q(
    `SELECT user_id FROM user_identities WHERE provider=$1 AND provider_uid=$2`,
    [idn.provider, idn.provider_uid],
  );
  if (existing.rowCount === 1) return { userId: existing.rows[0].user_id };

  const userId = id("usr");
  const r = await q(
    // Orphan-users invariant: the CTE's INSERT INTO users always commits its row,
    // even when the outer INSERT INTO user_identities hits ON CONFLICT DO NOTHING
    // (i.e. a concurrent caller won the race). That orphan users row has no FK
    // referencing it and is harmless today. Any future schema work that adds
    // references to users.id (e.g. a soft-delete flag, audit log FK, etc.) must
    // either tolerate these orphans or add a cleanup sweep.
    `WITH u AS (INSERT INTO users(id) VALUES ($1) RETURNING id)
     INSERT INTO user_identities(id,user_id,provider,provider_uid,email_verified)
     VALUES ($2, $1, $3, $4, $5)
     ON CONFLICT (provider, provider_uid) DO NOTHING
     RETURNING user_id`,
    [userId, id("uid"), idn.provider, idn.provider_uid, !!idn.email_verified],
  );
  if (r.rowCount === 1) return { userId: r.rows[0].user_id };

  // lost the race: orphan users row is harmless; re-read the winner
  const w = await q(
    `SELECT user_id FROM user_identities WHERE provider=$1 AND provider_uid=$2`,
    [idn.provider, idn.provider_uid],
  );
  return { userId: w.rows[0].user_id };
}

export async function createFamily(userId: string, name: string): Promise<{ familyId: string }> {
  const familyId = id("fam");
  const c = await pool.connect();
  try {
    await c.query("BEGIN");
    await c.query(`INSERT INTO families(id,name,created_by) VALUES ($1,$2,$3)`, [familyId, name, userId]);
    await c.query(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ($1,$2,'owner','active')`, [userId, familyId]);
    await c.query("COMMIT");
  } catch (e) { await c.query("ROLLBACK"); throw e; } finally { c.release(); }
  return { familyId };
}

export async function mintCredentialFor(userId: string): Promise<{ credentialId: string }> {
  const credentialId = id("cred");
  await q(
    `INSERT INTO credentials(id,user_id,kind,scopes) VALUES ($1,$2,'app','{content:read,content:write}')`,
    [credentialId, userId],
  );
  const { grantScopes } = await import("./scope.ts");            // ADR 0029 grant rows
  await grantScopes(credentialId, ["content:read", "content:write"]);
  return { credentialId };
}
