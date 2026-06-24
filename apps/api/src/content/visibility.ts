// ADR 0030 — per-member read visibility. The household/M0 legacy token is exempt
// (it authors restricted content, so it must read it); the exemption keys on the
// middleware `legacy` flag, NOT on user_id IS NULL (so no non-legacy NULL-user
// credential can reach god-mode — round-1 security P0-2).

type Caller = { userId: string | null; legacy: boolean };

// A card is visible iff family-visible, or the caller is in its author-stamped
// audience. (Cards have no created_by — round-2 R2-3; the author stamps themselves
// into audience to keep visibility.)
export function cardVisible(
  row: { visibility?: string | null; audience?: string[] | null },
  caller: Caller,
): boolean {
  if (caller.legacy) return true;
  if (!row.visibility || row.visibility === "family") return true;
  return !!caller.userId && Array.isArray(row.audience) && row.audience.includes(caller.userId);
}

// SQL fragment + params for filtering a card list to the caller's visible set.
// Returns a WHERE-clause suffix and the params to append. Legacy → no filter.
export function cardVisibilityClause(caller: Caller, nextParamIndex: number): { sql: string; params: unknown[] } {
  if (caller.legacy) return { sql: "", params: [] };
  // family rows, or restricted rows whose audience contains the caller.
  return {
    sql: ` AND (visibility = 'family' OR ($${nextParamIndex} = ANY(audience)))`,
    params: [caller.userId ?? "\0"], // non-null sentinel; a real user id never equals it
  };
}
