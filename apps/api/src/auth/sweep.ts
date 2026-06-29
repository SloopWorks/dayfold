import { q } from "../db.ts";

// Retention sweep (S3/S4 follow) — purge transient/expired auth ephemera before
// non-dogfood traffic. ONLY rows that are safely dead: never user content, and
// never an invite a membership references (invites are revoked-not-deleted by
// design, memberships.invite_id → NO ACTION). Idempotent; safe to run on a cron.
//
// `graceMs` keeps very recent rows even if technically terminal/expired (avoids
// racing an in-flight flow); default 24h.
export interface SweepResult { rate_limits: number; device_authorizations: number; invites: number; refresh_tokens: number; op_log: number; content_tombstones: number }

// op_id idempotency rows (ADR 0039 §6.5) only need to outlive any in-flight retry of
// the same op — an offline outbox draining hours later, at most. A 7-day floor is far
// beyond that while keeping the table from growing unboundedly.
const OP_LOG_TTL_MS = 7 * 24 * 3600 * 1000;

// Content-tombstone retention floor (ADR 0040 §3) — a soft-deleted content row is
// hard-purged only once it is OLDER than this floor, which must be ≥ the max plausible
// slow-cadence / offline gap so any client that synced within the floor never misses a
// delete (below it, the keyset still floats the tombstone past their cursor). A client
// whose cursor is older than this floor takes the stale-cursor full-resync path instead
// (app.ts /sync). The SAME constant gates both — they are two halves of one contract.
// NOTE: the exact value is a cost/retention tradeoff → operator-gated [pending-ratify]
// (OQ-freshness-spectrum); 90d is the conservative end of the ADR's 60–90d recommendation
// (longer = safer for slow clients, more tombstone storage). Overridable via env for ops.
export const CONTENT_TOMBSTONE_RETENTION_DAYS = Number(process.env.CONTENT_TOMBSTONE_RETENTION_DAYS) || 90;
const CONTENT_TOMBSTONE_RETENTION_MS = CONTENT_TOMBSTONE_RETENTION_DAYS * 24 * 3600 * 1000;

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

  // Content tombstones past the retention floor (ADR 0040 §3). Hard-delete soft-deleted
  // rows across all four content tables — once older than the floor, no client that synced
  // within the floor still needs them; an arbitrarily-staler client takes the full-resync
  // path. blocks→sections→cards/hubs order is irrelevant (each filtered on its own
  // deleted_at; FKs are ON DELETE CASCADE but we only touch already-soft-deleted rows).
  const tombGrace = new Date(Date.now() - CONTENT_TOMBSTONE_RETENTION_MS).toISOString();
  let contentTombstones = 0;
  for (const table of ["blocks", "sections", "briefing_cards", "hubs"]) {
    contentTombstones += (await q(
      `DELETE FROM ${table} WHERE deleted_at IS NOT NULL AND deleted_at < $1`, [tombGrace],
    )).rowCount ?? 0;
  }

  return { rate_limits: rate, device_authorizations: devices, invites, refresh_tokens: refresh, op_log: opLog, content_tombstones: contentTombstones };
}
