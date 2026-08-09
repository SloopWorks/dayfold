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
import { q } from "../db.ts";

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
              label, sublabel, note, version`;

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
): Promise<ContentResponseRow> {
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
     RETURNING ${COLS}`,
    [
      id, familyId, input.kind, input.subjectRef, input.matchScope, input.audienceScope,
      input.userId, input.createdBy, input.label, input.sublabel, input.note,
    ],
  );
  return r.rows[0] as ContentResponseRow;
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
