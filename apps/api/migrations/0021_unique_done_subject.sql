-- A family completion is one durable fact about one concrete subject. Two members may tap
-- Mark done concurrently, but that must converge on one Done row rather than duplicate history.
BEGIN;

-- 0020 allowed two devices to create different Done ids for the same subject. Preserve the
-- oldest completion as the canonical fact and tombstone the rest before adding uniqueness, so
-- an existing household with that legitimate pre-0021 race does not make deployment abort.
WITH ranked AS (
  SELECT family_id, id,
         row_number() OVER (
           PARTITION BY family_id, subject_ref
           ORDER BY created_at, id
         ) AS position
    FROM content_responses
   WHERE kind = 'done' AND deleted_at IS NULL
)
UPDATE content_responses AS response
   SET deleted_at = now(), updated_at = now(), version = response.version + 1
  FROM ranked
 WHERE response.family_id = ranked.family_id
   AND response.id = ranked.id
   AND ranked.position > 1;

CREATE UNIQUE INDEX content_responses_one_live_done_per_subject_idx
  ON content_responses (family_id, subject_ref)
  WHERE kind = 'done' AND deleted_at IS NULL;

COMMIT;
