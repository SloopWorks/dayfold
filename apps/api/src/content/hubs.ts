// ADR 0006 hub content + ADR 0030 hub visibility. Hubs/sections/blocks data access.
// Sections/blocks are reachable ONLY through a hub (GET /hubs/:id/tree, gated by the
// hub's visibility) — there is no independent section/block read path in this slice,
// so they inherit the hub's visibility for free (the review's leak path was the
// deferred hub /sync stream; with no hub sync there is nothing to over-share).
import { currentDbClient, q, pool } from "../db.ts";
import { buildBlockSubjectRef } from "./subject-ref.ts";

const J = (v: unknown) => (v == null ? null : JSON.stringify(v));
type Caller = { userId: string | null; legacy: boolean };
const buildSectionSubjectRef = (hubId: string, sectionId: string) =>
  `hub:${hubId}/section:${sectionId}`;

export async function blockSubjectRef(familyId: string, blockId: string): Promise<string | null> {
  const row = await q(
    // Include tombstones: the member route must authorize the block's historical source Hub before
    // returning its 410, otherwise a guessed id in a restricted Hub becomes an existence oracle.
    `SELECT subject_ref FROM blocks WHERE family_id=$1 AND id=$2`,
    [familyId, blockId],
  );
  return row.rows[0]?.subject_ref ?? null;
}

export type SectionMoveLockPlan = {
  oldHubId: string | null;
  childSubjects: string[];
  lockSubjects: string[];
};

/** Snapshot the identities a section move must lock; upsertSection revalidates it under row locks. */
export async function sectionMoveLockPlan(
  familyId: string,
  sectionId: string,
  newHubId: string,
): Promise<SectionMoveLockPlan> {
  const section = await q(`SELECT hub_id FROM sections WHERE family_id=$1 AND id=$2`, [familyId, sectionId]);
  const oldHubId = (section.rows[0]?.hub_id as string | undefined) ?? null;
  const children = oldHubId ? await q(
    `SELECT id,subject_ref FROM blocks
      WHERE family_id=$1 AND section_id=$2 AND deleted_at IS NULL ORDER BY id`,
    [familyId, sectionId],
  ) : { rows: [] as any[] };
  const childSubjects = children.rows.map((row: any) => row.subject_ref as string);
  const lockSubjects = [buildSectionSubjectRef(newHubId, sectionId)];
  if (oldHubId) lockSubjects.push(buildSectionSubjectRef(oldHubId, sectionId));
  for (const row of children.rows as any[]) {
    lockSubjects.push(row.subject_ref, buildBlockSubjectRef(newHubId, sectionId, row.id));
  }
  return { oldHubId, childSubjects, lockSubjects };
}

// A response payload inherits its concrete Hub subject's visibility. Whenever that visibility
// or allow-list changes, re-touch matching active responses so a member newly granted access
// receives the row after the tombstone previously emitted to their sync cursor (and a member
// removed from access receives a fresh tombstone).
async function touchResponsesForHub(
  client: { query: (sql: string, params?: unknown[]) => Promise<any> },
  familyId: string,
  hubId: string,
) {
  const root = `hub:${hubId}`;
  await client.query(
    `UPDATE content_responses SET updated_at=now()
      WHERE family_id=$1 AND deleted_at IS NULL
        AND (subject_ref=$2 OR left(subject_ref, length($2) + 1)=$2 || '/')`,
    [familyId, root],
  );
}

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

// A single user's participation_role on a hub's allow-list (ADR 0053 item 4's write
// gate needs the CALLER's role, not the whole roster). null = no row (never granted a
// role — the pre-DC2 default; NOT the same as 'viewer', but hubWritableByMember treats
// both as non-writable so the distinction doesn't matter to the caller).
export async function roleFor(familyId: string, hubId: string, userId: string): Promise<string | null> {
  const r = await q(
    `SELECT role FROM resource_visibility WHERE family_id=$1 AND hub_id=$2 AND user_id=$3`,
    [familyId, hubId, userId]);
  return r.rows[0]?.role ?? null;
}

// ADR 0053 item 4 — the member-write gate: may `caller` write CONTENT (sections/
// blocks) parented by this hub? Author OR a participation role of contributor/
// co_owner OR legacy (M0 operator, write-exempt). A plain viewer (or a member with
// no allow-list row at all — `role` null) may NOT write. Mirrors hubVisible's
// null-safety: a non-legacy NULL-user credential can't match a loop-authored
// (created_by NULL) hub via the author branch (P0-2 — no NULL-vs-NULL god-mode).
export function hubWritableByMember(
  hub: { created_by?: string | null },
  caller: { userId: string | null; legacy: boolean },
  role: string | null,
): boolean {
  if (caller.legacy) return true;
  if (caller.userId && hub.created_by && caller.userId === hub.created_by) return true;
  return role === "contributor" || role === "co_owner";
}

// Idempotent hub upsert + allow-list authoring, in one transaction. created_by is set
// on first author and preserved thereafter. visibility 'family'|'restricted'; when
// restricted, `audience` (user ids) replaces the allow-list rows.
export async function upsertHub(
  familyId: string, id: string, b: any, caller: Caller,
  visibility: "family" | "restricted", audience: string[] | undefined,
) {
  const inheritedClient = currentDbClient();
  const client = inheritedClient ?? await pool.connect();
  try {
    if (!inheritedClient) await client.query("BEGIN");
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
    // Reconcile the allow-list to the target audience WITHOUT resetting survivors'
    // `role` (ADR 0053 DC2/DC3 fix — was a blind DELETE-all + reinsert, which reset
    // every remaining member's role to the column default 'viewer' on EVERY rewrite,
    // silently undoing grants made through the DC2 participant-management route).
    // Only remove uids that fell OUT of the target audience; only insert uids that
    // are newly added (role defaults to 'viewer' via the column default); leave
    // surviving rows — and their role — untouched.
    const targetAudience = visibility === "restricted" ? new Set(audience ?? []) : new Set<string>();
    const cur = await client.query(`SELECT user_id FROM resource_visibility WHERE family_id=$1 AND hub_id=$2`, [familyId, id]);
    const toRemove = cur.rows.map((x: any) => x.user_id).filter((uid: string) => !targetAudience.has(uid));
    if (toRemove.length)
      await client.query(
        `DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id=$2 AND user_id = ANY($3)`,
        [familyId, id, toRemove]);
    for (const uid of targetAudience)
      await client.query(`INSERT INTO resource_visibility(family_id,hub_id,user_id) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING`, [familyId, id, uid]);
    await touchResponsesForHub(client, familyId, id);
    if (!inheritedClient) await client.query("COMMIT");
    return r.rows[0];
  } catch (e) {
    if (!inheritedClient) await client.query("ROLLBACK");
    throw e;
  } finally {
    if (!inheritedClient) client.release();
  }
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
    // responseReadable resolves through a live Hub. Re-touch its completion history in the
    // same transaction so every incremental cursor emits tombstones instead of retaining
    // labels/notes from a Hub that has just become unreachable.
    await touchResponsesForHub(client, familyId, id);
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
export async function blockForDelete(familyId: string, id: string): Promise<{ section_id: string; subject_ref: string; created_by: string | null; deleted: boolean } | null> {
  const r = await q(`SELECT section_id, subject_ref, created_by, deleted_at FROM blocks WHERE family_id=$1 AND id=$2`, [familyId, id]);
  if (r.rowCount === 0) return null;
  const row = r.rows[0];
  return {
    section_id: row.section_id,
    subject_ref: row.subject_ref,
    created_by: row.created_by ?? null,
    deleted: row.deleted_at != null,
  };
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
            (m.user_id = h.created_by) AS is_author,
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
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const r = await client.query(
      `INSERT INTO resource_visibility (family_id, hub_id, user_id, role)
       VALUES ($1,$2,$3,$4)
       ON CONFLICT (family_id, hub_id, user_id) DO UPDATE SET role=EXCLUDED.role
       RETURNING *`,
      [familyId, hubId, uid, role]);
    await touchResponsesForHub(client, familyId, hubId);
    await client.query("COMMIT");
    return r.rows[0];
  } catch (e) { await client.query("ROLLBACK"); throw e; } finally { client.release(); }
}

// Remove a participant's allow-list row. `uid<>created_by` is a belt-and-suspenders
// guard (the route already rejects the author before calling this) — never let the
// author's implicit co-ownership be revoked via this path.
export async function removeParticipant(familyId: string, hubId: string, uid: string): Promise<boolean> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const r = await client.query(
      `DELETE FROM resource_visibility rv
        WHERE rv.family_id=$1 AND rv.hub_id=$2 AND rv.user_id=$3
          AND rv.user_id <> (SELECT created_by FROM hubs WHERE family_id=$1 AND id=$2)
        RETURNING 1`,
      [familyId, hubId, uid]);
    if ((r.rowCount ?? 0) > 0) await touchResponsesForHub(client, familyId, hubId);
    await client.query("COMMIT");
    return (r.rowCount ?? 0) > 0;
  } catch (e) { await client.query("ROLLBACK"); throw e; } finally { client.release(); }
}

// Flip a hub's visibility (family<->restricted) without touching the allow-list rows
// (DC2: incremental participant management, distinct from upsertHub's full-replace).
// The 0011 fan-out trigger re-touches the section/block subtree on this UPDATE.
export async function setHubVisibility(familyId: string, hubId: string, visibility: "family" | "restricted") {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const r = await client.query(
      `UPDATE hubs SET visibility=$3, updated_at=now() WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING *`,
      [familyId, hubId, visibility]);
    if (r.rows[0]) await touchResponsesForHub(client, familyId, hubId);
    await client.query("COMMIT");
    return r.rows[0] ?? null;
  } catch (e) { await client.query("ROLLBACK"); throw e; } finally { client.release(); }
}

// Returns the parent hub id for a section if it exists and is live, else null.
export async function liveHubOfSection(familyId: string, sectionId: string): Promise<string | null> {
  const r = await q(
    `SELECT s.hub_id FROM sections s JOIN hubs h ON h.family_id=s.family_id AND h.id=s.hub_id
      WHERE s.family_id=$1 AND s.id=$2 AND s.deleted_at IS NULL AND h.deleted_at IS NULL`, [familyId, sectionId]);
  return r.rows[0]?.hub_id ?? null;
}

// Parent hub must exist + be live. Returns null (caller maps to 409) if not.
export async function upsertSection(
  familyId: string,
  id: string,
  hubId: string,
  b: any,
  expected: { oldHubId: string | null; childSubjects: string[] } | null = null,
) {
  const inheritedClient = currentDbClient();
  const client = inheritedClient ?? await pool.connect();
  try {
    if (!inheritedClient) await client.query("BEGIN");
    // Lock both topology sides before the section row. Hub deletion locks its Hub first and then
    // sweeps children, so this common order prevents a create/move from committing beneath a Hub
    // deleted between the route's authorization read and this transaction.
    const requiredHubIds = [...new Set(
      [hubId, expected?.oldHubId].filter((x): x is string => !!x),
    )].sort();
    const live = await client.query(
      `SELECT id FROM hubs
        WHERE family_id=$1 AND id = ANY($2::text[]) AND deleted_at IS NULL
        ORDER BY id FOR UPDATE`,
      [familyId, requiredHubIds],
    );
    if (live.rowCount !== requiredHubIds.length) {
      if (!inheritedClient) await client.query("ROLLBACK");
      return null;
    }
    const previous = await client.query(
      `SELECT hub_id FROM sections WHERE family_id=$1 AND id=$2 FOR UPDATE`, [familyId, id],
    );
    const oldHubId = (previous.rows[0]?.hub_id as string | undefined) ?? null;
    const children = await client.query(
      `SELECT id,subject_ref FROM blocks
        WHERE family_id=$1 AND section_id=$2 AND deleted_at IS NULL ORDER BY id FOR UPDATE`,
      [familyId, id],
    );
    const childSubjects = children.rows.map((row: any) => row.subject_ref as string);
    if (expected && (oldHubId !== expected.oldHubId ||
      JSON.stringify(childSubjects) !== JSON.stringify(expected.childSubjects))) {
      if (!inheritedClient) await client.query("ROLLBACK");
      return null;
    }
    const r = await client.query(
      `INSERT INTO sections (id, family_id, hub_id, title, ord, version)
       VALUES ($1,$2,$3,$4,$5,1)
       ON CONFLICT (family_id, id) DO UPDATE SET
         hub_id=EXCLUDED.hub_id, title=EXCLUDED.title, ord=EXCLUDED.ord,
         version=sections.version + 1, deleted_at=NULL
       RETURNING *`,
      [id, familyId, hubId, b.title ?? null, b.ord ?? 0],
    );
    if (oldHubId && oldHubId !== hubId) {
      // A block's canonical suppression key contains its full Hub path. Moving the parent section
      // therefore moves every child identity too; re-key and re-version them in this transaction
      // so Mark done works immediately and /sync cannot pair the new topology with an old key.
      await client.query(
        `UPDATE blocks
            SET subject_ref='hub:' || $3 || '/section:' || $2 || '/block:' || id,
                version=version+1, updated_at=now()
          WHERE family_id=$1 AND section_id=$2`,
        [familyId, id, hubId],
      );
      await touchResponsesForHub(client, familyId, oldHubId);
      await touchResponsesForHub(client, familyId, hubId);
    }
    if (!inheritedClient) await client.query("COMMIT");
    return r.rows[0];
  } catch (e) {
    if (!inheritedClient) await client.query("ROLLBACK");
    throw e;
  } finally {
    if (!inheritedClient) client.release();
  }
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
  opts: {
    allowResurrect?: boolean;
    createdBy?: string | null;
    expectedHubId?: string;
    expectedOldSubjectRef?: string | null;
  } = {},
) {
  const allowResurrect = opts.allowResurrect !== false;
  const inheritedClient = currentDbClient();
  const client = inheritedClient ?? await pool.connect();
  try {
    if (!inheritedClient) await client.query("BEGIN");
    const live = await client.query(
      `SELECT hub_id FROM sections
        WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL
        FOR UPDATE`,
      [familyId, sectionId],
    );
    if (live.rowCount === 0) {
      if (!inheritedClient) await client.query("ROLLBACK");
      return null;
    }
    const newHubId = live.rows[0].hub_id as string;
    // The route authorized and locked the subject under this exact Hub. A concurrent section
    // move must not redirect the write into another (possibly restricted) Hub or evade that
    // subject lock. FOR UPDATE also keeps the section stable until the outer transaction ends.
    if (opts.expectedHubId && newHubId !== opts.expectedHubId) {
      if (!inheritedClient) await client.query("ROLLBACK");
      return null;
    }
    const previous = await client.query(
      `SELECT b.section_id, b.subject_ref, s.hub_id
         FROM blocks b JOIN sections s ON s.family_id=b.family_id AND s.id=b.section_id
        WHERE b.family_id=$1 AND b.id=$2`,
      [familyId, id],
    );
    const oldSectionId = previous.rows[0]?.section_id as string | undefined;
    const oldHubId = previous.rows[0]?.hub_id as string | undefined;
    const oldSubjectRef = previous.rows[0]?.subject_ref as string | undefined;
    if (opts.expectedOldSubjectRef !== undefined &&
      (oldSubjectRef ?? null) !== opts.expectedOldSubjectRef) {
      if (!inheritedClient) await client.query("ROLLBACK");
      return null;
    }
    // ADR 0064 — the suppression key, stamped server-side from the node path. Re-derived on
    // every write (not just the INSERT) so a block MOVED to another section re-keys.
    const subjectRef = buildBlockSubjectRef(newHubId, sectionId, id);
    const r = await client.query(
      `INSERT INTO blocks (id, family_id, section_id, type, payload, body_md, body_ref, provenance, triggers, actions, ord, created_by, temporal, subject_ref, version)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,1)
       ON CONFLICT (family_id, id) DO UPDATE SET
         section_id=EXCLUDED.section_id, type=EXCLUDED.type, payload=EXCLUDED.payload,
         body_md=EXCLUDED.body_md, body_ref=EXCLUDED.body_ref, provenance=EXCLUDED.provenance,
         triggers=EXCLUDED.triggers, actions=EXCLUDED.actions, ord=EXCLUDED.ord,
         temporal=EXCLUDED.temporal,
         subject_ref=EXCLUDED.subject_ref,
         version=blocks.version + 1, deleted_at=NULL
         ${allowResurrect ? "" : "WHERE blocks.deleted_at IS NULL"}
       RETURNING *`,
      [id, familyId, sectionId, b.type, J(b.payload), b.body_md ?? null, b.body_ref ?? null,
       J(b.provenance), J(b.triggers), J(b.actions), b.ord ?? 0, opts.createdBy ?? null, J(b.temporal), subjectRef],
    );
    if (r.rows[0] && oldSectionId && oldSectionId !== sectionId) {
      if (oldHubId) await touchResponsesForHub(client, familyId, oldHubId);
      if (newHubId !== oldHubId) await touchResponsesForHub(client, familyId, newHubId);
    }
    if (!inheritedClient) await client.query("COMMIT");
    return r.rows[0] ?? null;
  } catch (e) {
    if (!inheritedClient) await client.query("ROLLBACK");
    throw e;
  } finally {
    if (!inheritedClient) client.release();
  }
}
