// ADR 0039 §6.5 — op_log idempotency. A mutation carries a client-minted `op_id`
// (the `Idempotency-Key` header). The first time the server applies it, it records the
// result (kind/ref/version); a retried or echoed op with the same `op_id` short-circuits
// to the recorded result instead of re-applying — so an offline sender draining a queue,
// or a duplicated request, never double-applies and never spuriously 412s on its own echo.
//
// The table (migration 0015) is PK (family_id, op_id) + a created_at index for the TTL
// sweep (see auth/sweep.ts). Rows are content-blind: cleartext keys only, never payload.
import { q } from "../db.ts";

export type OpResult = { result_kind: string | null; result_ref: string | null; result_version: number | null };

// The recorded result for a prior op_id, or null if this op_id is new to the family.
export async function findOp(familyId: string, opId: string): Promise<OpResult | null> {
  const r = await q(
    `SELECT result_kind, result_ref, result_version FROM op_log WHERE family_id=$1 AND op_id=$2`,
    [familyId, opId],
  );
  if (r.rowCount === 0) return null;
  const row = r.rows[0];
  return {
    result_kind: row.result_kind ?? null,
    result_ref: row.result_ref ?? null,
    result_version: row.result_version != null ? Number(row.result_version) : null,
  };
}

// Record the result of applying an op. Idempotent (ON CONFLICT DO NOTHING) so a race
// between two retries of the same op_id keeps the first-write result.
export async function recordOp(
  familyId: string,
  opId: string,
  kind: string,
  ref: string,
  version: number | null,
): Promise<void> {
  await q(
    `INSERT INTO op_log (family_id, op_id, result_kind, result_ref, result_version)
     VALUES ($1,$2,$3,$4,$5) ON CONFLICT (family_id, op_id) DO NOTHING`,
    [familyId, opId, kind, ref, version],
  );
}
