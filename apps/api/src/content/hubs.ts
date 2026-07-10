// ADR 0006 hub content + ADR 0030 hub visibility. Hubs/sections/blocks data access.
// Sections/blocks are reachable ONLY through a hub (GET /hubs/:id/tree, gated by the
// hub's visibility) — there is no independent section/block read path in this slice,
// so they inherit the hub's visibility for free (the review's leak path was the
// deferred hub /sync stream; with no hub sync there is nothing to over-share).
import { q, pool } from "../db.ts";

const J = (v: unknown) => (v == null ? null : JSON.stringify(v));
type Caller = { userId: string | null; legacy: boolean };

// A single hub row's visibility for the caller.
export function hubVisible(
  row: { visibility?: string | null; created_by?: string | null; family_id?: string; id?: string },
  caller: Caller,
  allowListHas: (hubId: string) => boolean = () => false,
): boolean {
  if (caller.legacy) return true;
  if (!row.visibility || row.visibility === "family") return true;
  if (caller.userId && row.created_by && caller.userId === row.created_by) return true;
  return !!caller.userId && !!row.id && allowListHas(row.id);
}

// WHERE-suffix that filters a hubs query to the caller's visible set. Legacy → none.
function hubVisibilityClause(caller: Caller, p: number): { sql: string; params: unknown[] } {
  if (caller.legacy) return { sql: "", params: [] };
  return {
    sql: ` AND (visibility='family' OR created_by=$${p}
              OR EXISTS (SELECT 1 FROM resource_visibility rv
                          WHERE rv.family_id=hubs.family_id AND rv.hub_id=hubs.id AND rv.user_id=$${p}))`,
    params: [caller.userId ?? "\0"],
  };
}

// List hubs the caller may see, optionally narrowed to a credential's granted hub
// ids (null = global content grant → no narrowing).
export async function listHubs(familyId: string, caller: Caller, grantedHubIds: string[] | null) {
  const vis = hubVisibilityClause(caller, 2);
  const params: unknown[] = [familyId, ...vis.params];
  let grantSql = "";
  if (grantedHubIds !== null) { grantSql = ` AND id = ANY($${params.length + 1})`; params.push(grantedHubIds); }
  const r = await q(
    `SELECT * FROM hubs WHERE family_id=$1 AND deleted_at IS NULL${vis.sql}${grantSql}
      ORDER BY coalesce(start_at, created_at), id`, params);
  return r.rows;
}

export async function getHub(familyId: string, id: string) {
  const r = await q(`SELECT * FROM hubs WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, id]);
  return r.rows[0] ?? null;
}

// Allow-list user ids for a hub (to evaluate hubVisible on a fetched row).
export async function allowListFor(familyId: string, hubId: string): Promise<Set<string>> {
  const r = await q(`SELECT user_id FROM resource_visibility WHERE family_id=$1 AND hub_id=$2`, [familyId, hubId]);
  return new Set(r.rows.map((x: any) => x.user_id));
}

// Idempotent hub upsert + allow-list authoring, in one transaction. created_by is set
// on first author and preserved thereafter. visibility 'family'|'restricted'; when
// restricted, `audience` (user ids) replaces the allow-list rows.
export async function upsertHub(
  familyId: string, id: string, b: any, caller: Caller,
  visibility: "family" | "restricted", audience: string[] | undefined,
) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const r = await client.query(
      `INSERT INTO hubs (id, family_id, type, title, status, start_at, end_at, countdown_to, visibility, created_by, media, timeline, version)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1)
       ON CONFLICT (family_id, id) DO UPDATE SET
         type=EXCLUDED.type, title=EXCLUDED.title, status=EXCLUDED.status,
         start_at=EXCLUDED.start_at, end_at=EXCLUDED.end_at, countdown_to=EXCLUDED.countdown_to,
         visibility=EXCLUDED.visibility, created_by=COALESCE(hubs.created_by, EXCLUDED.created_by),
         media=EXCLUDED.media, timeline=EXCLUDED.timeline,
         version=hubs.version + 1, deleted_at=NULL
       RETURNING *`,
      [id, familyId, b.type, b.title, b.status ?? "active",
       b.start_at ?? null, b.end_at ?? null, b.countdown_to ?? null, visibility, caller.userId, J(b.media), J(b.timeline)],
    );
    // Replace the allow-list when restricted; clear it when family.
    await client.query(`DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id=$2`, [familyId, id]);
    if (visibility === "restricted") {
      for (const uid of audience ?? [])
        await client.query(`INSERT INTO resource_visibility(family_id,hub_id,user_id) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING`, [familyId, id, uid]);
    }
    await client.query("COMMIT");
    return r.rows[0];
  } catch (e) { await client.query("ROLLBACK"); throw e; } finally { client.release(); }
}

// status -> archived. Returns false if the hub is absent/already deleted.
export async function archiveHub(familyId: string, id: string) {
  const r = await q(`UPDATE hubs SET status='archived' WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id]);
  return (r.rowCount ?? 0) > 0;
}

// Soft-delete the hub + its sections + blocks in ONE transaction (no live orphans).
export async function softDeleteHub(familyId: string, id: string) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const h = await client.query(`UPDATE hubs SET deleted_at=now() WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id]);
    if ((h.rowCount ?? 0) === 0) { await client.query("ROLLBACK"); return false; }
    await client.query(`UPDATE sections SET deleted_at=now() WHERE family_id=$1 AND hub_id=$2 AND deleted_at IS NULL`, [familyId, id]);
    await client.query(
      `UPDATE blocks SET deleted_at=now()
        WHERE family_id=$1 AND deleted_at IS NULL
          AND section_id IN (SELECT id FROM sections WHERE family_id=$1 AND hub_id=$2)`, [familyId, id]);
    await client.query("COMMIT");
    return true;
  } catch (e) { await client.query("ROLLBACK"); throw e; } finally { client.release(); }
}

// W4 (ADR 0038): soft-delete a single block (tombstone via deleted_at; cleartext, so
// E2EE-clean and it drives keyset cache-eviction on /sync). Returns false if the block
// was already absent/tombstoned (the route maps that to 404 / idempotent 204).
export async function softDeleteBlock(familyId: string, id: string): Promise<boolean> {
  const r = await q(`UPDATE blocks SET deleted_at=now() WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id]);
  return (r.rowCount ?? 0) > 0;
}

// A block row in ANY state (incl. tombstoned), with the author + parent section — the
// W4 delete route needs created_by (author-gate) + section_id (hub-visibility gate)
// before it can decide, so it can't use getBlock (which filters live rows only).
export async function blockForDelete(familyId: string, id: string): Promise<{ section_id: string; created_by: string | null; deleted: boolean } | null> {
  const r = await q(`SELECT section_id, created_by, deleted_at FROM blocks WHERE family_id=$1 AND id=$2`, [familyId, id]);
  if (r.rowCount === 0) return null;
  const row = r.rows[0];
  return { section_id: row.section_id, created_by: row.created_by ?? null, deleted: row.deleted_at != null };
}

// hub tree (hub + live sections + live blocks). Caller must already be checked
// visible on the hub (the route 404s otherwise).
export async function getHubTree(familyId: string, hubId: string) {
  const hub = await getHub(familyId, hubId);
  if (!hub) return null;
  const sections = (await q(`SELECT * FROM sections WHERE family_id=$1 AND hub_id=$2 AND deleted_at IS NULL ORDER BY ord, id`, [familyId, hubId])).rows;
  const blocks = (await q(
    `SELECT * FROM blocks WHERE family_id=$1 AND deleted_at IS NULL
       AND section_id IN (SELECT id FROM sections WHERE family_id=$1 AND hub_id=$2) ORDER BY ord, id`, [familyId, hubId])).rows;
  return { hub, sections, blocks };
}

// "Who can see this hub" (ADR 0030): the full active roster, each flagged
// `permitted` = the hub is family-visible, OR the member is its author
// (created_by), OR the member is on the allow-list. Owner is NOT auto-permitted
// (option A) — they show permitted=false unless author/allow-listed.
export async function hubAudience(familyId: string, hubId: string) {
  const r = await q(
    `SELECT m.user_id AS uid, u.display_name, u.avatar_color, u.avatar_ref, m.role,
            CASE WHEN m.user_id = h.created_by THEN 'co_owner' ELSE rv2.role END AS participation_role,
            (h.visibility = 'family'
             OR m.user_id = h.created_by
             OR EXISTS (SELECT 1 FROM resource_visibility rv
                         WHERE rv.family_id=$1 AND rv.hub_id=$2 AND rv.user_id=m.user_id)) AS permitted
       FROM memberships m
       JOIN users u ON u.id = m.user_id
       JOIN hubs h ON h.family_id=$1 AND h.id=$2
       LEFT JOIN resource_visibility rv2 ON rv2.family_id=$1 AND rv2.hub_id=$2 AND rv2.user_id=m.user_id
      WHERE m.family_id=$1 AND m.status='active'
      ORDER BY (m.role='owner') DESC, u.display_name, m.user_id`,
    [familyId, hubId]);
  return r.rows;
}

// ADR 0053 DC2: may `caller` manage this hub's participant roster / visibility?
// Author (created_by) OR an existing co_owner row OR legacy (M0 operator) — family
// `owner` is NOT auto-permitted (mirrors the ADR 0030 §7 "owner is not an override"
// stance already applied to hub audience/authorship elsewhere in this file).
export async function canManageHub(familyId: string, hubId: string, caller: Caller): Promise<boolean> {
  if (caller.legacy) return true;
  if (!caller.userId) return false;
  const r = await q(
    `SELECT 1 FROM hubs h
      WHERE h.family_id=$1 AND h.id=$2 AND h.deleted_at IS NULL
        AND (h.created_by=$3
             OR EXISTS (SELECT 1 FROM resource_visibility rv
                         WHERE rv.family_id=$1 AND rv.hub_id=$2 AND rv.user_id=$3 AND rv.role='co_owner'))`,
    [familyId, hubId, caller.userId]);
  return (r.rowCount ?? 0) > 0;
}

export type ParticipantRole = "viewer" | "contributor" | "co_owner";

// Upsert a single participant's role on the hub's allow-list. The 0018 trigger
// (AFTER INSERT OR UPDATE OR DELETE) touches hubs.updated_at so the change
// re-surfaces via keyset sync. Caller (the route) must already have rejected
// uid === hub.created_by (author is a permanent implicit co_owner, not a row here).
export async function setParticipant(familyId: string, hubId: string, uid: string, role: ParticipantRole) {
  const r = await q(
    `INSERT INTO resource_visibility (family_id, hub_id, user_id, role)
     VALUES ($1,$2,$3,$4)
     ON CONFLICT (family_id, hub_id, user_id) DO UPDATE SET role=EXCLUDED.role
     RETURNING *`,
    [familyId, hubId, uid, role]);
  return r.rows[0];
}

// Remove a participant's allow-list row. `uid<>created_by` is a belt-and-suspenders
// guard (the route already rejects the author before calling this) — never let the
// author's implicit co-ownership be revoked via this path.
export async function removeParticipant(familyId: string, hubId: string, uid: string): Promise<boolean> {
  const r = await q(
    `DELETE FROM resource_visibility rv
      WHERE rv.family_id=$1 AND rv.hub_id=$2 AND rv.user_id=$3
        AND rv.user_id <> (SELECT created_by FROM hubs WHERE family_id=$1 AND id=$2)
      RETURNING 1`,
    [familyId, hubId, uid]);
  return (r.rowCount ?? 0) > 0;
}

// Flip a hub's visibility (family<->restricted) without touching the allow-list rows
// (DC2: incremental participant management, distinct from upsertHub's full-replace).
// The 0011 fan-out trigger re-touches the section/block subtree on this UPDATE.
export async function setHubVisibility(familyId: string, hubId: string, visibility: "family" | "restricted") {
  const r = await q(
    `UPDATE hubs SET visibility=$3, updated_at=now() WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING *`,
    [familyId, hubId, visibility]);
  return r.rows[0] ?? null;
}

// Returns the parent hub id for a section if it exists and is live, else null.
export async function liveHubOfSection(familyId: string, sectionId: string): Promise<string | null> {
  const r = await q(
    `SELECT s.hub_id FROM sections s JOIN hubs h ON h.family_id=s.family_id AND h.id=s.hub_id
      WHERE s.family_id=$1 AND s.id=$2 AND s.deleted_at IS NULL AND h.deleted_at IS NULL`, [familyId, sectionId]);
  return r.rows[0]?.hub_id ?? null;
}

// Parent hub must exist + be live. Returns null (caller maps to 409) if not.
export async function upsertSection(familyId: string, id: string, hubId: string, b: any) {
  const live = await q(`SELECT 1 FROM hubs WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, hubId]);
  if (live.rowCount === 0) return null;
  const r = await q(
    `INSERT INTO sections (id, family_id, hub_id, title, ord, version)
     VALUES ($1,$2,$3,$4,$5,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       hub_id=EXCLUDED.hub_id, title=EXCLUDED.title, ord=EXCLUDED.ord,
       version=sections.version + 1, deleted_at=NULL
     RETURNING *`,
    [id, familyId, hubId, b.title ?? null, b.ord ?? 0]);
  return r.rows[0];
}

// A live block row by id (null when absent or tombstoned). Used to return the recorded
// result on an idempotent op replay.
export async function getBlock(familyId: string, id: string) {
  const r = await q(`SELECT * FROM blocks WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, id]);
  return r.rows[0] ?? null;
}

// `allowResurrect` (default true = the loop/CLI authoring path) keeps the
// re-create-by-PUT behaviour (ON CONFLICT clears deleted_at). The member path passes
// false (ADR 0038 §6.3): the DO UPDATE then matches only a LIVE row, so a concurrent
// delete between the route's 410 check and this upsert yields no row → the caller 410s
// instead of silently resurrecting a tombstoned block.
export async function upsertBlock(
  familyId: string, id: string, sectionId: string, b: any,
  opts: { allowResurrect?: boolean; createdBy?: string | null } = {},
) {
  const allowResurrect = opts.allowResurrect !== false;
  const live = await q(`SELECT 1 FROM sections WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, sectionId]);
  if (live.rowCount === 0) return null;
  // created_by is set ONCE at creation (the INSERT) and never touched by the DO UPDATE —
  // so a member toggling/editing a loop-authored block can never claim its authorship.
  // It is the substrate the W4 delete author-gate keys on (ADR 0038 §W4 / the W2 stamp).
  const r = await q(
    `INSERT INTO blocks (id, family_id, section_id, type, payload, body_md, body_ref, provenance, triggers, actions, ord, created_by, version)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       section_id=EXCLUDED.section_id, type=EXCLUDED.type, payload=EXCLUDED.payload,
       body_md=EXCLUDED.body_md, body_ref=EXCLUDED.body_ref, provenance=EXCLUDED.provenance,
       triggers=EXCLUDED.triggers, actions=EXCLUDED.actions, ord=EXCLUDED.ord,
       version=blocks.version + 1, deleted_at=NULL
       ${allowResurrect ? "" : "WHERE blocks.deleted_at IS NULL"}
     RETURNING *`,
    [id, familyId, sectionId, b.type, J(b.payload), b.body_md ?? null, b.body_ref ?? null,
     J(b.provenance), J(b.triggers), J(b.actions), b.ord ?? 0, opts.createdBy ?? null]);
  return r.rows[0] ?? null;
}
