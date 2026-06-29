-- ADR 0038/0039 — two-way mutation engine: RESERVE the shape, no behavior change.
-- This migration only reserves columns/tables/types the member-write slices fill in
-- later (Slice 2 wires op_log idempotency + the author columns; Slice 5 the delete
-- author-gate). Nothing here is read or enforced yet — the API behaves identically.
--
-- Forward-only plain SQL, one txn (ADR 0033). No IF NOT EXISTS.

BEGIN;

-- 1) Cleartext provenance columns on blocks AND briefing_cards (only `hubs` has
--    created_by today, added in 0009). The server may stamp these in cleartext even
--    when the payload is ciphertext (M1) — provenance is a render-side, content-blind
--    fact ("Added by Claude" / a member byline), never reasoned over.
--    NULL = legacy / loop-authored (no implicit grant), matching the hubs convention.
ALTER TABLE blocks         ADD COLUMN created_by      text REFERENCES users(id);
ALTER TABLE blocks         ADD COLUMN author_kind     text CHECK (author_kind IN ('loop','member'));
ALTER TABLE blocks         ADD COLUMN writer_user_id  text REFERENCES users(id);  -- single-writer-per-block (W2)

ALTER TABLE briefing_cards ADD COLUMN created_by      text REFERENCES users(id);
ALTER TABLE briefing_cards ADD COLUMN author_kind     text CHECK (author_kind IN ('loop','member'));
ALTER TABLE briefing_cards ADD COLUMN writer_user_id  text REFERENCES users(id);

-- 2) op_log — generalized idempotency table (ADR 0039 §6.5). An op_id (client-minted
--    ULID) records the result of applying a mutation, so a retried/echoed op returns
--    the same result instead of re-applying. Reserved now; Slice 2 wires the read/write
--    + a TTL sweep arm on /cron/sweep. created_at indexed for that sweep.
CREATE TABLE op_log (
  family_id      text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  op_id          text NOT NULL,           -- client-minted ULID; the idempotency + echo key
  result_kind    text,                    -- target_kind of the mutated entity (block|card|hub|…)
  result_ref     text,                    -- id of the mutated entity
  result_version bigint,                  -- version after applying (drives echo-suppress)
  created_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (family_id, op_id)
);
CREATE INDEX ON op_log (created_at);      -- TTL sweep (Slice 2)

-- 3) block_type / card_kind ENUM → text. The member-write features (W1–W5) add many
--    new block/card types over time; a Postgres ENUM needs ALTER TYPE … ADD VALUE
--    (awkward, non-transactional pre-PG12) for each. Relaxing the column to `text`
--    moves type validation to the app layer — which already happens: every write is
--    zod-validated against the generated discriminated-union schema (the enum still
--    lives in content.schema.json), so the API boundary is unchanged; only the DB
--    column constraint relaxes. Drop the now-unreferenced enum types.
ALTER TABLE blocks ALTER COLUMN type TYPE text USING type::text;

ALTER TABLE briefing_cards ALTER COLUMN kind DROP DEFAULT;
ALTER TABLE briefing_cards ALTER COLUMN kind TYPE text USING kind::text;
ALTER TABLE briefing_cards ALTER COLUMN kind SET DEFAULT 'info';

DROP TYPE block_type;
DROP TYPE card_kind;

-- 4) RESERVED, no DDL needed here (recorded so the next slice has one place to look):
--    * `content:delete` scope — scopes are free text (credentials.scopes[] /
--      credential_grants.scope); the new action is reserved in src/auth/scope.ts.
--      Slice 5 grants + gates it on the delete path.
--    * The client `outbox` typed-op envelope (`type` discriminator + `target_kind` +
--      nullable `depends_on`) lives in the client SQLDelight DB (egress-only, ADR 0020)
--      — built in Slice 3 where its sender loop lives, not in this server migration.
--    * The `x-e2e` per-field annotation is reserved in content.schema.json
--      (ChecklistPayload carries `"x-e2e": "ciphertext-at-M1"`); it is a codegen/spec
--      annotation, not a DB column.

COMMIT;
