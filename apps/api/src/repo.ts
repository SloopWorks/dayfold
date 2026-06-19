// Briefing-card data access (M0 feed surface). Idempotent upsert (server bumps
// version), keyset sync incl. tombstones, soft-delete.
import { q } from "./db.ts";

const J = (v: unknown) => (v == null ? null : JSON.stringify(v));

export async function upsertCard(familyId: string, id: string, b: any) {
  const r = await q(
    `INSERT INTO briefing_cards
       (id, family_id, kind, title, body_md, target_hub_id, target_section_id,
        target_block_id, provenance, triggers, actions, not_before, expires_at, version)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       kind=EXCLUDED.kind, title=EXCLUDED.title, body_md=EXCLUDED.body_md,
       target_hub_id=EXCLUDED.target_hub_id, target_section_id=EXCLUDED.target_section_id,
       target_block_id=EXCLUDED.target_block_id, provenance=EXCLUDED.provenance,
       triggers=EXCLUDED.triggers, actions=EXCLUDED.actions,
       not_before=EXCLUDED.not_before, expires_at=EXCLUDED.expires_at,
       version=briefing_cards.version + 1, deleted_at=NULL
     RETURNING *`,
    [id, familyId, b.kind ?? "info", b.title, b.body_md ?? null,
     b.target?.hubId ?? null, b.target?.sectionId ?? null, b.target?.blockId ?? null,
     J(b.provenance), J(b.triggers), J(b.actions), b.not_before ?? null, b.expires_at ?? null],
  );
  return r.rows[0];
}

export async function listCards(familyId: string) {
  const r = await q(
    `SELECT * FROM briefing_cards WHERE family_id=$1 AND deleted_at IS NULL
     ORDER BY not_before NULLS LAST, id`, [familyId]);
  return r.rows;
}

export async function softDeleteCard(familyId: string, id: string) {
  const r = await q(
    `UPDATE briefing_cards SET deleted_at=now()
     WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id]);
  return (r.rowCount ?? 0) > 0;
}

// keyset over (updated_at, id); INCLUDES tombstones (deleted_at not null) — the
// trigger bumps updated_at on soft-delete so they sort past the cursor.
export async function syncCards(familyId: string, su: string | null, si: string | null, limit = 200) {
  const r = await q(
    `SELECT * FROM briefing_cards WHERE family_id=$1 AND (updated_at, id) > ($2::timestamptz, $3)
     ORDER BY updated_at, id LIMIT $4`,
    [familyId, su ?? "-infinity", si ?? "", limit]);
  return r.rows;
}
