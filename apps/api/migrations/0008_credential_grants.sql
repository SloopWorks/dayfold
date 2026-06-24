-- ADR 0029: resource-scoped credential grants. A credential's authority is a set
-- of grant rows resolved PER REQUEST from this table (never from the token,
-- ADR 0011 §8). Replaces the flat `credentials.scopes[]` reads and closes the
-- read-enforcement gap (read routes now require an explicit grant). Scope strings:
-- global `content:read` / `content:write`, or resource-qualified
-- `hub:<hub_id>:read` / `hub:<hub_id>:write` (the per-hub picker lands later;
-- this slice writes the global grants).
CREATE TABLE credential_grants (
  credential_id text NOT NULL REFERENCES credentials(id) ON DELETE CASCADE,
  scope         text NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (credential_id, scope)
);
CREATE INDEX ON credential_grants (credential_id);

-- Backfill: mirror each LIVE credential's ACTUAL scopes (not a blanket grant), so a
-- write-only credential stays write-only. Revoked credentials are excluded (their
-- authority is dead; resolution also re-checks revoked_at upstream).
INSERT INTO credential_grants (credential_id, scope)
  SELECT id, unnest(scopes)
    FROM credentials
   WHERE revoked_at IS NULL AND scopes IS NOT NULL
  ON CONFLICT DO NOTHING;

-- Guard (same migration/tx): every live credential that carried >=1 scope MUST now
-- have >=1 grant, else the gate would silently lock out a live credential on cutover.
DO $$
DECLARE missing int;
BEGIN
  SELECT count(*) INTO missing
    FROM credentials c
   WHERE c.revoked_at IS NULL
     AND coalesce(array_length(c.scopes, 1), 0) > 0
     AND NOT EXISTS (SELECT 1 FROM credential_grants g WHERE g.credential_id = c.id);
  IF missing > 0 THEN
    RAISE EXCEPTION 'credential_grants backfill incomplete: % live credential(s) without grants', missing;
  END IF;
END $$;
