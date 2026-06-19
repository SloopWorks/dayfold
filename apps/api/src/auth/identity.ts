// Pluggable identity boundary + user/family/credential creation. Firebase verifier
// arrives at S2; S1 uses StubVerifier (tests) + DevVerifier (gated dev-token).
import { randomBytes } from "node:crypto";
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

export async function mintCredentialFor(userId: string, familyId: string): Promise<{ credentialId: string }> {
  const credentialId = id("cred");
  await q(
    `INSERT INTO credentials(id,user_id,family_scope,kind,scopes) VALUES ($1,$2,$3,'app','{content:read,content:write}')`,
    [credentialId, userId, familyId],
  );
  return { credentialId };
}
