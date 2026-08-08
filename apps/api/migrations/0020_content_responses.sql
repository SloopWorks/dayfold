-- ADR 0064 — smart-content responses. Two things, one txn:
--   1) subject_ref as a persisted, indexed key on authored content (the third job of the
--      ADR 0043 subjectRef: dedup key -> deep-link key -> SUPPRESSION key).
--   2) content_responses — the Tier-1 synced rows (mute + done). The server matches these
--      by ID string equality ONLY; label/sublabel/note are opaque display strings it never
--      reasons over (same posture as block payloads today, plaintext at M0).
-- Forward-only plain SQL, one txn (ADR 0033). No IF NOT EXISTS.

BEGIN;

ALTER TABLE briefing_cards ADD COLUMN subject_ref text;
ALTER TABLE blocks         ADD COLUMN subject_ref text;

-- Backfill every existing row, live AND tombstoned: a resurrect-by-PUT (the loop/CLI
-- authoring path deliberately re-creates a tombstoned id) must land on the same key, or a
-- mute set before the delete would stop matching after the resurrect.
UPDATE briefing_cards SET subject_ref = 'card:' || id WHERE subject_ref IS NULL;
UPDATE blocks b
   SET subject_ref = 'hub:' || s.hub_id || '/section:' || b.section_id || '/block:' || b.id
  FROM sections s
 WHERE s.family_id = b.family_id AND s.id = b.section_id AND b.subject_ref IS NULL;

CREATE INDEX briefing_cards_subject_ref_idx ON briefing_cards (family_id, subject_ref);
CREATE INDEX blocks_subject_ref_idx         ON blocks (family_id, subject_ref);

-- The Tier-1 rows. `kind` distinguishes a suppression rule from a completion record; they
-- share a table because they share a key, a lifecycle, a sync lane, and a Settings surface,
-- and because a done row IS a suppression for future runs (ADR 0064 §1).
CREATE TABLE content_responses (
  id             text NOT NULL,
  family_id      text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  kind           text NOT NULL CHECK (kind IN ('mute','done')),
  subject_ref    text NOT NULL,
  match_scope    text NOT NULL CHECK (match_scope IN ('subject','kind','source')),
  audience_scope text NOT NULL CHECK (audience_scope IN ('personal','family')),
  -- The member a personal rule belongs to. NULL iff audience_scope='family'.
  user_id        text REFERENCES users(id),
  -- Always attributed (ADR 0064 §4, decided Q2) — drives the Settings byline.
  created_by     text NOT NULL REFERENCES users(id),
  -- Opaque display strings for the client. The server NEVER branches on these.
  label          text NOT NULL,
  sublabel       text,
  note           text,
  version        bigint NOT NULL DEFAULT 1,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz,
  PRIMARY KEY (family_id, id),
  -- A personal rule needs an owner; a family rule must not have one.
  CONSTRAINT content_responses_audience_owner CHECK (
    (audience_scope = 'personal' AND user_id IS NOT NULL) OR
    (audience_scope = 'family'   AND user_id IS NULL)
  ),
  -- Done is always family-wide and always on a concrete subject, never a class (§5).
  CONSTRAINT content_responses_done_shape CHECK (
    kind <> 'done' OR (audience_scope = 'family' AND match_scope = 'subject')
  )
);

-- The hot path: "is this subject_ref suppressed for this family?" on every content write.
CREATE INDEX content_responses_lookup_idx
  ON content_responses (family_id, subject_ref) WHERE deleted_at IS NULL;
-- The /sync merged keyset scan (ADR 0040) orders by updated_at, id.
CREATE INDEX content_responses_sync_idx ON content_responses (family_id, updated_at, id);

COMMIT;
