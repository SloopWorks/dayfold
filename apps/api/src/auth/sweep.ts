import { q } from "../db.ts";

// Retention sweep (S3/S4 follow) — purge transient/expired auth ephemera before
// non-dogfood traffic. ONLY rows that are safely dead: never user content, and
// never an invite a membership references (invites are revoked-not-deleted by
// design, memberships.invite_id → NO ACTION). Idempotent; safe to run on a cron.
//
// `graceMs` keeps very recent rows even if technically terminal/expired (avoids
// racing an in-flight flow); default 24h.
export interface SweepResult { rate_limits: number; device_authorizations: number; invites: number; refresh_tokens: number; op_log: number }

// op_id idempotency rows (ADR 0039 §6.5) only need to outlive any in-flight retry of
// the same op — an offline outbox draining hours later, at most. A 7-day floor is far
// beyond that while keeping the table from growing unboundedly.
const OP_LOG_TTL_MS = 7 * 24 * 3600 * 1000;

export async function sweep(graceMs = 24 * 3600 * 1000): Promise<SweepResult> {
  const grace = new Date(Date.now() - graceMs).toISOString();

  // Fixed-window counters whose window is old and whose lockout (if any) lapsed.
  const rate = (await q(
    `DELETE FROM rate_limits WHERE window_start < $1 AND (locked_until IS NULL OR locked_until < now())`,
    [grace],
  )).rowCount ?? 0;

  // Device-grant codes that are terminal or expired (nothing references them).
  const devices = (await q(
    `DELETE FROM device_authorizations
       WHERE created_at < $1 AND (expires_at < now() OR status IN ('denied','expired','consumed'))`,
    [grace],
  )).rowCount ?? 0;

  // Invites: only truly-orphan ones — never redeemed (used_count=0) and not
  // referenced by any membership — so deleting can't violate the NO-ACTION FK.
  const invites = (await q(
    `DELETE FROM invites i
       WHERE i.used_count = 0 AND i.status <> 'active' AND i.expires_at < $1
         AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.invite_id = i.id)`,
    [grace],
  )).rowCount ?? 0;

  // Refresh tokens past their ABSOLUTE lifetime. A replayed expired token is
  // rejected by rotate() on expiry regardless, so deleting it loses no signal.
  // SECURITY: only EXPIRED rows — a consumed-but-unexpired token is deliberately
  // KEPT, because a replay of it must still be caught as reuse → revoke-lineage
  // (ADR 0011 §5) until it expires. Never widen this to `consumed_at IS NOT NULL`.
  const refresh = (await q(
    `DELETE FROM refresh_tokens WHERE expires_at < $1`, [grace],
  )).rowCount ?? 0;

  // op_log idempotency rows past the TTL floor (ADR 0039 §6.5). Independent of the
  // auth-grace param — these have their own retention window.
  const opLogGrace = new Date(Date.now() - OP_LOG_TTL_MS).toISOString();
  const opLog = (await q(
    `DELETE FROM op_log WHERE created_at < $1`, [opLogGrace],
  )).rowCount ?? 0;

  return { rate_limits: rate, device_authorizations: devices, invites, refresh_tokens: refresh, op_log: opLog };
}
