// Opaque, hashed refresh tokens with a per-credential lineage. Rotation = atomic
// CAS; older-consumed reuse → revoke the whole lineage. 04-auth §refresh.
import { randomBytes, createHash } from "node:crypto";
import { q, pool } from "../db.ts";

const ABS_TTL_DAYS = 45;
export const hashToken = (s: string) => createHash("sha256").update(s, "utf8").digest("hex");

export async function issueRefresh(credentialId: string): Promise<string> {
  const opaque = randomBytes(32).toString("base64url");
  await q(
    `INSERT INTO refresh_tokens(token_hash, credential_id, expires_at)
     VALUES ($1,$2, now() + ($3 || ' days')::interval)`,
    [hashToken(opaque), credentialId, String(ABS_TTL_DAYS)],
  );
  return opaque;
}

export async function rotate(opaque: string): Promise<{ refresh: string } | { reuse: true } | null> {
  const h = hashToken(opaque);

  const client = await pool.connect();
  try {
    await client.query("BEGIN");

    // atomic CAS: consume IFF currently unconsumed + unexpired
    const cas = await client.query(
      `UPDATE refresh_tokens SET consumed_at=now()
       WHERE token_hash=$1 AND consumed_at IS NULL AND expires_at > now()
       RETURNING credential_id`,
      [h],
    );

    if (cas.rowCount === 1) {
      const credentialId = cas.rows[0].credential_id;
      const nextOpaque = randomBytes(32).toString("base64url");
      const nextHash = hashToken(nextOpaque);

      await client.query(
        `INSERT INTO refresh_tokens(token_hash, credential_id, expires_at)
         VALUES ($1,$2, now() + ($3 || ' days')::interval)`,
        [nextHash, credentialId, String(ABS_TTL_DAYS)],
      );
      await client.query(
        `UPDATE refresh_tokens SET superseded_by=$1 WHERE token_hash=$2`,
        [nextHash, h],
      );

      await client.query("COMMIT");
      return { refresh: nextOpaque };
    }

    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }

  // not consumable: either unknown/expired, or already-consumed (reuse)
  const row = await q(`SELECT credential_id, consumed_at FROM refresh_tokens WHERE token_hash=$1`, [h]);
  if (row.rowCount === 0) return null;
  if (row.rows[0].consumed_at) {
    // real reuse of a consumed token → revoke the whole lineage
    await q(`UPDATE credentials SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL`, [row.rows[0].credential_id]);
    return { reuse: true };
  }
  return null;
}
