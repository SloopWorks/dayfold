// Briefing-card data access (M0 feed surface). Idempotent upsert (server bumps
// version), keyset sync incl. tombstones, soft-delete.
import { currentDbClient, pool, q } from "./db.ts";
import { cardVisibilityClause } from "./content/visibility.ts";
// ADR 0064 — the suppression key is stamped here, from the id the server already owns.
// Never from the request body: an author-chosen key could step around a mute.
import { buildCardSubjectRef } from "./content/subject-ref.ts";

const J = (v: unknown) => (v == null ? null : JSON.stringify(v));
export const SYNC_LIMIT = 200; // single source for the sync page size (F2)

async function touchResponsesForCard(
  client: { query: (sql: string, params?: unknown[]) => Promise<any> },
  familyId: string,
  id: string,
) {
  await client.query(
    `UPDATE content_responses SET updated_at=now()
      WHERE family_id=$1 AND subject_ref=$2 AND deleted_at IS NULL`,
    [familyId, buildCardSubjectRef(id)],
  );
}

export async function upsertCard(familyId: string, id: string, b: any) {
  // ADR 0030: visibility + author-stamped audience (default family). Validated by
  // the route; stored here. No inheritance/materialization (round-2 R2-1).
  const visibility = b.visibility === "restricted" ? "restricted" : "family";
  const audience = visibility === "restricted" && Array.isArray(b.audience) ? b.audience : null;
  const inherited = currentDbClient();
  const client = inherited ?? await pool.connect();
  try {
    if (!inherited) await client.query("BEGIN");
    const r = await client.query(
    `INSERT INTO briefing_cards
       (id, family_id, kind, title, body_md, target_hub_id, target_section_id,
        target_block_id, provenance, triggers, actions, not_before, expires_at,
        type, payload, privacy, hub_ref, related, related_kicker, visibility, audience, media,
        subject_ref, version)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       kind=EXCLUDED.kind, title=EXCLUDED.title, body_md=EXCLUDED.body_md,
       target_hub_id=EXCLUDED.target_hub_id, target_section_id=EXCLUDED.target_section_id,
       target_block_id=EXCLUDED.target_block_id, provenance=EXCLUDED.provenance,
       triggers=EXCLUDED.triggers, actions=EXCLUDED.actions,
       not_before=EXCLUDED.not_before, expires_at=EXCLUDED.expires_at,
       type=EXCLUDED.type, payload=EXCLUDED.payload, privacy=EXCLUDED.privacy,
       hub_ref=EXCLUDED.hub_ref, related=EXCLUDED.related, related_kicker=EXCLUDED.related_kicker,
       visibility=EXCLUDED.visibility, audience=EXCLUDED.audience, media=EXCLUDED.media,
       subject_ref=EXCLUDED.subject_ref,
       version=briefing_cards.version + 1, deleted_at=NULL
     RETURNING *`,
    [id, familyId, b.kind ?? "info", b.title, b.body_md ?? null,
     b.target?.hubId ?? null, b.target?.sectionId ?? null, b.target?.blockId ?? null,
     J(b.provenance), J(b.triggers), J(b.actions), b.not_before ?? null, b.expires_at ?? null,
     b.type ?? null, J(b.payload), J(b.privacy), b.hubRef ?? null, J(b.related), b.relatedKicker ?? null,
     visibility, audience, J(b.media), buildCardSubjectRef(id)],
    );
    await touchResponsesForCard(client, familyId, id);
    if (!inherited) await client.query("COMMIT");
    return r.rows[0];
  } catch (e) {
    if (!inherited) await client.query("ROLLBACK");
    throw e;
  } finally {
    if (!inherited) client.release();
  }
}

export async function listCards(familyId: string, caller: { userId: string | null; legacy: boolean }) {
  const vis = cardVisibilityClause(caller, 2);
  const r = await q(
    `SELECT * FROM briefing_cards WHERE family_id=$1 AND deleted_at IS NULL${vis.sql}
     ORDER BY not_before NULLS LAST, id`, [familyId, ...vis.params]);
  return r.rows;
}

export async function softDeleteCard(familyId: string, id: string) {
  const inherited = currentDbClient();
  const client = inherited ?? await pool.connect();
  try {
    if (!inherited) await client.query("BEGIN");
    const r = await client.query(
      `UPDATE briefing_cards SET deleted_at=now()
       WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id]);
    if ((r.rowCount ?? 0) > 0) await touchResponsesForCard(client, familyId, id);
    if (!inherited) await client.query("COMMIT");
    return (r.rowCount ?? 0) > 0;
  } catch (e) {
    if (!inherited) await client.query("ROLLBACK");
    throw e;
  } finally {
    if (!inherited) client.release();
  }
}

// Merged keyset over (updated_at, type, id) spanning cards + hubs + sections + blocks.
// Row-wise comparison so the tuple is globally unique. INCLUDES tombstones (deleted_at
// not null) — soft-delete bumps updated_at so they surface past any cursor.
// Section/block rows carry parent hub fields (hub_id, hub_visibility, hub_created_by)
// so the route can evaluate hubVisible without an extra round-trip.
export async function syncContent(
  familyId: string,
  su: string,   // start updated_at ("" → "-infinity")
  st: string,   // start type ("" → "")
  si: string,   // start id ("" → "")
  limit = SYNC_LIMIT,
) {
  const r = await q(
    `SELECT updated_at, type, id, family_id, deleted_at, payload,
            hub_id, hub_visibility, hub_created_by FROM (
       SELECT updated_at, 'card' AS type, id, family_id, deleted_at,
              to_jsonb(briefing_cards.*) AS payload,
              NULL::text AS hub_id, NULL::text AS hub_visibility, NULL::text AS hub_created_by
         FROM briefing_cards WHERE family_id=$1
       UNION ALL
       SELECT updated_at, 'hub' AS type, id, family_id, deleted_at,
              to_jsonb(hubs.*) AS payload,
              NULL::text AS hub_id, NULL::text AS hub_visibility, NULL::text AS hub_created_by
         FROM hubs WHERE family_id=$1
       UNION ALL
       SELECT s.updated_at, 'section' AS type, s.id, s.family_id, s.deleted_at,
              to_jsonb(s.*) AS payload,
              h.id AS hub_id, h.visibility AS hub_visibility, h.created_by AS hub_created_by
         FROM sections s JOIN hubs h ON h.family_id=s.family_id AND h.id=s.hub_id
        WHERE s.family_id=$1
       UNION ALL
       SELECT b.updated_at, 'block' AS type, b.id, b.family_id, b.deleted_at,
              to_jsonb(b.*) AS payload,
              h.id AS hub_id, h.visibility AS hub_visibility, h.created_by AS hub_created_by
         FROM blocks b
         JOIN sections s ON s.family_id=b.family_id AND s.id=b.section_id
         JOIN hubs h ON h.family_id=s.family_id AND h.id=s.hub_id
        WHERE b.family_id=$1
       UNION ALL
       -- ADR 0064 — Tier-1 response rows ride the SAME keyset cursor as content, so a
       -- rule reaches every device on the cadence the family already has. Per-member
       -- visibility (a personal rule is its owner's alone) is applied in the projection,
       -- not here: filtering in SQL would make a page of another member's rules shrink the
       -- window and stall the cursor.
       SELECT updated_at, 'response' AS type, id, family_id, deleted_at,
              to_jsonb(content_responses.*) AS payload,
              NULL::text AS hub_id, NULL::text AS hub_visibility, NULL::text AS hub_created_by
         FROM content_responses WHERE family_id=$1
     ) merged
     WHERE (updated_at, type, id) > ($2::timestamptz, $3, $4)
     ORDER BY updated_at, type, id LIMIT $5`,
    [familyId, su === "" ? "-infinity" : su, st, si, limit],
  );
  return r.rows as {
    updated_at: string;
    type: string;
    id: string;
    family_id: string;
    deleted_at: string | null;
    payload: any;
    hub_id: string | null;
    hub_visibility: string | null;
    hub_created_by: string | null;
  }[];
}
