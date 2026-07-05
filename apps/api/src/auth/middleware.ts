// The one mandatory tenant auth+authz gate. Fail-closed; default-deny. Accepts a
// new EdDSA access-JWT OR the legacy household token (a distinct, content-scope-
// only path — TODO(S3-cutover): remove the legacy branch once the CLI is on JWTs).
import { q } from "../db.ts";
import { constantTimeEqual } from "../security.ts";
// verifyAccess is imported lazily inside the function so that importing this
// module does not trigger the module-level AUTH_* env guards in tokens.ts.
// Tests that don't set AUTH vars (api.test.ts) can still load app.ts safely.

export function bearer(c: any): string | undefined {
  const h = c.req.header("authorization") || "";
  return h.startsWith("Bearer ") ? h.slice(7) : undefined;
}

type Ok = { cred: any; userId: string | null; role: string | null; scopes: string[]; legacy: boolean };
type Err = { status: 401 | 403 | 404 };

export async function authorizeTenant(c: any, fid: string): Promise<Ok | Err> {
  const token = bearer(c);
  if (!token) return { status: 401 };

  // Legacy branch: constant-time match against the household secret (content-scope only).
  const legacySecret = process.env.HOUSEHOLD_SECRET || "";
  if (legacySecret && constantTimeEqual(token, legacySecret)) {              // TODO(S3-cutover)
    try {
      const r = await q(`SELECT * FROM credentials WHERE id=$1 AND revoked_at IS NULL`,
        [process.env.HOUSEHOLD_CREDENTIAL_ID || ""]);
      const cred = r.rows[0];
      if (!cred) return { status: 401 };
      if (cred.family_scope !== fid) return { status: 404 };
      return { cred, userId: null, role: null, scopes: cred.scopes ?? [], legacy: true };
    } catch { return { status: 401 }; }
  }

  // Access-JWT branch.
  let claims: { sub: string; cid: string };
  try {
    const { verifyAccess } = await import("./tokens.ts");
    claims = await verifyAccess(token);
  } catch { return { status: 401 }; }
  try {
    const r = await q(`SELECT * FROM credentials WHERE id=$1`, [claims.cid]);
    const cred = r.rows[0];
    if (!cred || cred.revoked_at) return { status: 401 };
    // family resolved from PATH only; cross-tenant 404 before membership.
    const m = await q(`SELECT role, status FROM memberships WHERE user_id=$1 AND family_id=$2`, [claims.sub, fid]);
    if (m.rowCount === 0) return { status: 404 };                 // not a member of this family
    if (m.rows[0].status !== "active") return { status: 403 };
    if (cred.family_scope && cred.family_scope !== fid) return { status: 404 };
    return { cred, userId: claims.sub, role: m.rows[0].role, scopes: cred.scopes ?? [], legacy: false };
  } catch { return { status: 401 }; }
}
