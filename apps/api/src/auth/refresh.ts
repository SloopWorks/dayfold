// Opaque, hashed refresh tokens with a per-credential lineage. Rotation = atomic
// CAS; older-consumed reuse → revoke the whole lineage. 04-auth §refresh.
// Grace window (~20s): idempotent re-rotation with per-lineage advisory lock so
// concurrent double-present can't self-revoke.
import { randomBytes, createHash } from "node:crypto";
import { q, pool } from "../db.ts";

const ABS_TTL_DAYS = 45;
export const hashToken = (s: string) => createHash("sha256").update(s, "utf8").digest("hex");

export async function issueRefresh(
  credentialId: string,
  client?: { query: (...args: any[]) => Promise<any> },
): Promise<string> {
  const opaque = randomBytes(32).toString("base64url");
  const run = client ? client.query.bind(client) : q;
  await run(
    `INSERT INTO refresh_tokens(token_hash, credential_id, expires_at)
     VALUES ($1,$2, now() + ($3 || ' days')::interval)`,
    [hashToken(opaque), credentialId, String(ABS_TTL_DAYS)],
  );
  return opaque;
}

export async function rotate(
  opaque: string,
): Promise<{ refresh: string; graced?: true } | { reuse: true } | null> {
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

  // Not consumed by CAS: unknown/expired, or already-consumed (reuse or grace).
  const row = await q(
    `SELECT credential_id, consumed_at, superseded_by FROM refresh_tokens WHERE token_hash=$1`,
    [h],
  );
  if (row.rowCount === 0) return null;
  const { credential_id: cid, consumed_at, superseded_by } = row.rows[0];
  if (!consumed_at) return null; // unexpired-but-not-consumed shouldn't reach here; expired -> null

  // [I-1] serialize per-lineage so concurrent double-present can't both fall to revoke.
  const gc = await pool.connect();
  // Track what we decided inside the txn so we can act after release.
  let graceResult: { refresh: string; graced: true } | null = null;
  let graceCollision = false; // within-grace window but successor already consumed by a peer grace
  let genuineReuse = false;
  try {
    await gc.query("BEGIN");
    await gc.query(`SELECT pg_advisory_xact_lock(hashtext($1))`, [cid]);

    // grace: presented token consumed <20s ago AND its successor is the live tip.
    const grace = await gc.query(
      `SELECT 1 FROM refresh_tokens prior
         JOIN refresh_tokens succ ON succ.token_hash = prior.superseded_by
        WHERE prior.token_hash=$1 AND prior.consumed_at > now() - interval '20 seconds'
          AND succ.consumed_at IS NULL`,
      [h],
    );

    if (grace.rowCount === 1 && superseded_by) {
      // rotate FROM the live successor, inside this txn
      const cas2 = await gc.query(
        `UPDATE refresh_tokens SET consumed_at=now()
           WHERE token_hash=$1 AND consumed_at IS NULL AND expires_at > now() RETURNING credential_id`,
        [superseded_by],
      );
      if (cas2.rowCount === 1) {
        const nextOpaque = randomBytes(32).toString("base64url");
        const nextHash = hashToken(nextOpaque);
        await gc.query(
          `INSERT INTO refresh_tokens(token_hash, credential_id, expires_at, graced_from)
           VALUES ($1,$2, now() + ($3 || ' days')::interval, $4)`,
          [nextHash, cid, String(ABS_TTL_DAYS), h],
        );
        await gc.query(
          `UPDATE refresh_tokens SET superseded_by=$1 WHERE token_hash=$2`,
          [nextHash, superseded_by],
        );
        graceResult = { refresh: nextOpaque, graced: true };
      } else {
        // cas2 failed: defensive; unreachable while the advisory lock holds.
        // The advisory lock (pg_advisory_xact_lock) is the real serializer here —
        // it serializes concurrent grace presenters under any pool size (max:10 in
        // tests + non-Vercel; max:1 is Vercel-only and NOT the reason this is safe).
        // Do NOT remove the lock: without it, two concurrent presenters of the same
        // grace-window token could both pass the grace check and both attempt cas2,
        // forking the lineage. The lock ensures the second sees the first's committed
        // consumption and falls through to the collision branch instead.
        genuineReuse = true;
        await gc.query(
          `UPDATE credentials SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL`,
          [cid],
        );
      }
    } else if (!superseded_by) {
      // No superseded_by: token was consumed but has no successor — genuine reuse
      genuineReuse = true;
      await gc.query(
        `UPDATE credentials SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL`,
        [cid],
      );
    } else {
      // Successor exists but not live tip. Check if it was consumed as a grace rotation
      // FROM this same token — if so, this is a concurrent collision, not genuine reuse.
      const collision = await gc.query(
        `SELECT 1 FROM refresh_tokens next_token
           JOIN refresh_tokens issued ON issued.token_hash = next_token.superseded_by
          WHERE next_token.token_hash=$1
            AND issued.graced_from=$2
            AND next_token.consumed_at > now() - interval '20 seconds'`,
        [superseded_by, h],
      );
      if (collision.rowCount === 1) {
        // Peer already did the grace rotation from this token. Safe to signal 401
        // without revoking — the client can use the other in-flight response.
        graceCollision = true;
      } else {
        // genuine reuse -> revoke lineage
        genuineReuse = true;
        await gc.query(
          `UPDATE credentials SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL`,
          [cid],
        );
      }
    }
    await gc.query("COMMIT");
  } catch (e) {
    await gc.query("ROLLBACK");
    throw e;
  } finally {
    gc.release();
  }

  if (graceResult) return graceResult;
  if (graceCollision) return { reuse: true }; // 401 without revoke

  // genuineReuse path: audit + return
  const { audit } = await import("./audit.ts");
  await audit("refresh.reuse_revoked", { detail: { credential_id: cid } });
  return { reuse: true };
}
