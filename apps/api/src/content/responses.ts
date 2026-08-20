// ADR 0064 — Tier-1 response rows (mute + done).
//
// `matchesRule` is the ONE place suppression is decided, and it is deliberately trivial:
// three string equalities against columns the server already treats as opaque identifiers.
// It reads no title, body, label, or note. If this function ever needs to look at content,
// the design has drifted off the content-blindness the ADR promises — the fix is to change
// the design, not to widen the function.
//
// label/sublabel/note are carried for the client's benefit only. They are plaintext at M0,
// exactly as block payloads are, and follow block payloads under the same flip if ADR
// 0015/0017 activate.
import { currentDbClient, q, pool, withDbClient } from "../db.ts";
import type { PoolClient } from "pg";

export type ResponseKind = "mute" | "done";
export type MatchScope = "subject" | "kind" | "source";
export type AudienceScope = "personal" | "family";

export type ContentResponseRow = {
  id: string;
  kind: ResponseKind;
  subject_ref: string;
  match_scope: MatchScope;
  audience_scope: AudienceScope;
  /** Owner of a personal rule; null for family rules (enforced by a CHECK constraint). */
  user_id: string | null;
  /** Always attributed (decided Q2) — drives the Settings byline. */
  created_by: string;
  label: string;
  sublabel: string | null;
  note: string | null;
  created_at: string;
  version: number;
};

/** The three opaque identifiers a write offers up for matching. Never its content. */
export type WriteSubject = {
  subjectRef: string;
  kind: string | null;
  source: string | null;
};

export function matchesRule(row: WriteSubject, rule: ContentResponseRow): boolean {
  switch (rule.match_scope) {
    // EXACT equality, never prefix containment: a hub-level key is a prefix of every block
    // under that hub, so a prefix match would silently mute the whole hub.
    case "subject":
      return row.subjectRef === rule.subject_ref;
    case "kind":
      return row.kind != null && `kind:${row.kind}` === rule.subject_ref;
    case "source":
      return row.source != null && `source:${row.source}` === rule.subject_ref;
  }
}

const COLS = `id, kind, subject_ref, match_scope, audience_scope, user_id, created_by,
              label, sublabel, note, created_at, version`;

export async function listActive(familyId: string): Promise<ContentResponseRow[]> {
  const r = await q(
    `SELECT ${COLS} FROM content_responses WHERE family_id=$1 AND deleted_at IS NULL`,
    [familyId],
  );
  return r.rows as ContentResponseRow[];
}

/** One live row by id, or null when absent/tombstoned. Used by the op-replay path. */
export async function findResponse(
  familyId: string,
  id: string,
): Promise<ContentResponseRow | null> {
  const r = await q(
    `SELECT ${COLS} FROM content_responses
      WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`,
    [familyId, id],
  );
  return (r.rows[0] as ContentResponseRow) ?? null;
}

/** Any row by id, including a tombstone, for immutable-identity collision checks. */
export async function findResponseRecord(
  familyId: string,
  id: string,
): Promise<ContentResponseRow | null> {
  const r = await q(
    `SELECT ${COLS} FROM content_responses WHERE family_id=$1 AND id=$2`,
    [familyId, id],
  );
  return (r.rows[0] as ContentResponseRow) ?? null;
}

/** The canonical live completion for one concrete subject, if it already exists. */
export async function findDoneForSubject(
  familyId: string,
  subjectRef: string,
): Promise<ContentResponseRow | null> {
  const r = await q(
    `SELECT ${COLS} FROM content_responses
      WHERE family_id=$1 AND kind='done' AND subject_ref=$2 AND deleted_at IS NULL`,
    [familyId, subjectRef],
  );
  return (r.rows[0] as ContentResponseRow) ?? null;
}

/**
 * Serialize content creation and completion for one concrete subject.
 *
 * Every query in the action is pinned to one explicit transaction. The production connection
 * endpoint uses transaction pooling, so the advisory lock must be transaction-scoped: a
 * session-level lock could be acquired and released on different server sessions.
 */
export async function withSubjectWriteLock<T>(
  familyId: string,
  subjectRef: string,
  action: () => Promise<T>,
): Promise<T> {
  return withSubjectWriteLocks(familyId, [subjectRef], action);
}

/** Acquire every subject identity in deterministic order inside one pinned transaction. */
export async function withSubjectWriteLocks<T>(
  familyId: string,
  subjectRefs: string[],
  action: () => Promise<T>,
): Promise<T> {
  const refs = [...new Set(subjectRefs)].sort();
  const inheritedClient = currentDbClient();
  if (inheritedClient) {
    for (const ref of refs) {
      const key = JSON.stringify([familyId, ref]);
      await inheritedClient.query(`SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, [key]);
    }
    return action();
  }
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const result = await withDbClient(client, async () => {
      for (const ref of refs) {
        const key = JSON.stringify([familyId, ref]);
        await client.query(`SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, [key]);
      }
      return action();
    });
    await client.query("COMMIT");
    return result;
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}

export type ResponseInput = {
  kind: ResponseKind;
  subjectRef: string;
  matchScope: MatchScope;
  audienceScope: AudienceScope;
  userId: string | null;
  createdBy: string;
  label: string;
  sublabel: string | null;
  note: string | null;
};

export async function upsertResponse(
  familyId: string,
  id: string,
  input: ResponseInput,
): Promise<ContentResponseRow | null> {
  // The identity columns (kind/subject_ref/match_scope/audience_scope/user_id/created_by)
  // are set once at creation and NOT touched by the DO UPDATE — re-PUTing an id must not
  // let a member silently widen a personal rule to family-wide, or re-attribute someone
  // else's rule to themselves. Editing those means deleting and re-creating.
  const r = await q(
    `INSERT INTO content_responses
       (id, family_id, kind, subject_ref, match_scope, audience_scope, user_id, created_by,
        label, sublabel, note)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
     ON CONFLICT (family_id, id) DO UPDATE SET
       label = EXCLUDED.label, sublabel = EXCLUDED.sublabel, note = EXCLUDED.note,
       deleted_at = NULL,
       version = content_responses.version + 1,
       updated_at = now()
     WHERE content_responses.kind = EXCLUDED.kind
       AND content_responses.subject_ref = EXCLUDED.subject_ref
       AND content_responses.match_scope = EXCLUDED.match_scope
       AND content_responses.audience_scope = EXCLUDED.audience_scope
       AND content_responses.user_id IS NOT DISTINCT FROM EXCLUDED.user_id
       AND content_responses.created_by = EXCLUDED.created_by
     RETURNING ${COLS}`,
    [
      id, familyId, input.kind, input.subjectRef, input.matchScope, input.audienceScope,
      input.userId, input.createdBy, input.label, input.sublabel, input.note,
    ],
  );
  return (r.rows[0] as ContentResponseRow) ?? null;
}

export type CompleteResponseResult = {
  row: ContentResponseRow;
  /** False means another member already completed this exact subject. */
  created: boolean;
};

/**
 * Create one family Done record and tombstone its subject atomically.
 *
 * The caller holds [withSubjectWriteLock] across visibility resolution and this transaction.
 * The partial unique index added in 0021 makes a Done idempotent per concrete subject, even
 * when two devices race with different response ids. The loser receives the canonical existing
 * row and never creates a duplicate. An id already owned by somebody else (or used for another
 * response identity) is a conflict rather than a display-field overwrite with a forged byline.
 */
export async function completeResponse(
  familyId: string,
  id: string,
  input: ResponseInput,
): Promise<CompleteResponseResult | null> {
  const run = async (client: PoolClient): Promise<CompleteResponseResult | null> => {
    const inserted = await client.query(
      `INSERT INTO content_responses
         (id, family_id, kind, subject_ref, match_scope, audience_scope, user_id, created_by,
          label, sublabel, note)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
       ON CONFLICT DO NOTHING
       RETURNING ${COLS}`,
      [
        id, familyId, input.kind, input.subjectRef, input.matchScope, input.audienceScope,
        input.userId, input.createdBy, input.label, input.sublabel, input.note,
      ],
    );
    let row = inserted.rows[0] as ContentResponseRow | undefined;
    const created = !!row;
    if (!row) {
      const canonical = await client.query(
        `SELECT ${COLS} FROM content_responses
          WHERE family_id=$1 AND kind='done' AND subject_ref=$2 AND deleted_at IS NULL
          FOR UPDATE`,
        [familyId, input.subjectRef],
      );
      row = canonical.rows[0] as ContentResponseRow | undefined;
      if (!row) {
        const collision = await client.query(
          `SELECT ${COLS} FROM content_responses
            WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL FOR UPDATE`,
          [familyId, id],
        );
        const existing = collision.rows[0] as ContentResponseRow | undefined;
        if (!existing || existing.created_by !== input.createdBy ||
          existing.kind !== "done" || existing.subject_ref !== input.subjectRef) {
          return null;
        }
        row = existing;
      }
    }

    await client.query(
      `UPDATE briefing_cards SET deleted_at=now(), version=version+1, updated_at=now()
        WHERE family_id=$1 AND subject_ref=$2 AND deleted_at IS NULL`,
      [familyId, input.subjectRef],
    );
    await client.query(
      `UPDATE blocks SET deleted_at=now(), version=version+1, updated_at=now()
        WHERE family_id=$1 AND subject_ref=$2 AND deleted_at IS NULL`,
      [familyId, input.subjectRef],
    );
    return { row, created };
  };

  const inheritedClient = currentDbClient();
  if (inheritedClient) return run(inheritedClient);

  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const result = await run(client);
    await client.query("COMMIT");
    return result;
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}

/**
 * Soft delete so /sync can emit the tombstone (ADR 0040) and every device drops the rule.
 * Returns false when the row is absent or already gone — the caller answers 204 either way,
 * so a drained/retried delete op is idempotent.
 */
export async function softDeleteResponse(familyId: string, id: string): Promise<boolean> {
  const r = await q(
    `UPDATE content_responses SET deleted_at = now(), version = version + 1, updated_at = now()
      WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`,
    [familyId, id],
  );
  return (r.rowCount ?? 0) > 0;
}
