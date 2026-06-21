// Briefing-card data access (M0 feed surface). Idempotent upsert (server bumps
// version), keyset sync incl. tombstones, soft-delete.
import { q } from "./db.ts";

const J = (v: unknown) => (v == null ? null : JSON.stringify(v));
export const SYNC_LIMIT = 200; // single source for the sync page size (F2)

export async function upsertCard(familyId: string, id: string, b: any) {
  const r = await q(
    `INSERT INTO briefing_cards
       (id, family_id, kind, title, body_md, target_hub_id, target_section_id,
        target_block_id, provenance, triggers, actions, not_before, expires_at,
        type, payload, privacy, hub_ref, related, related_kicker, version)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       kind=EXCLUDED.kind, title=EXCLUDED.title, body_md=EXCLUDED.body_md,
       target_hub_id=EXCLUDED.target_hub_id, target_section_id=EXCLUDED.target_section_id,
       target_block_id=EXCLUDED.target_block_id, provenance=EXCLUDED.provenance,
       triggers=EXCLUDED.triggers, actions=EXCLUDED.actions,
       not_before=EXCLUDED.not_before, expires_at=EXCLUDED.expires_at,
       type=EXCLUDED.type, payload=EXCLUDED.payload, privacy=EXCLUDED.privacy,
       hub_ref=EXCLUDED.hub_ref, related=EXCLUDED.related, related_kicker=EXCLUDED.related_kicker,
       version=briefing_cards.version + 1, deleted_at=NULL
     RETURNING *`,
    [id, familyId, b.kind ?? "info", b.title, b.body_md ?? null,
     b.target?.hubId ?? null, b.target?.sectionId ?? null, b.target?.blockId ?? null,
     J(b.provenance), J(b.triggers), J(b.actions), b.not_before ?? null, b.expires_at ?? null,
     b.type ?? null, J(b.payload), J(b.privacy), b.hubRef ?? null, J(b.related), b.relatedKicker ?? null],
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
export async function syncCards(familyId: string, su: string | null, si: string | null, limit = SYNC_LIMIT) {
  const r = await q(
    `SELECT * FROM briefing_cards WHERE family_id=$1 AND (updated_at, id) > ($2::timestamptz, $3)
     ORDER BY updated_at, id LIMIT $4`,
    [familyId, su ?? "-infinity", si ?? "", limit]);
  return r.rows;
}
