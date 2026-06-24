-- ADR 0030: per-member visibility. Hubs carry visibility + a resolved created_by
-- author; a hubs-only resource_visibility allow-list names additional permitted
-- members. Cards carry visibility + an author-stamped audience[] (no inheritance/
-- materialization — round-2). Read paths filter by these; the household/M0 legacy
-- token is visibility-EXEMPT (decided in middleware by the `legacy` flag, NOT by
-- user_id IS NULL — so no non-legacy NULL-user credential can reach god-mode).
ALTER TABLE hubs           ADD COLUMN visibility text NOT NULL DEFAULT 'family'
  CHECK (visibility IN ('family','restricted'));
ALTER TABLE hubs           ADD COLUMN created_by text REFERENCES users(id);  -- NULL = legacy/M0 author (no implicit grant)

ALTER TABLE briefing_cards ADD COLUMN visibility text NOT NULL DEFAULT 'family'
  CHECK (visibility IN ('family','restricted'));
ALTER TABLE briefing_cards ADD COLUMN audience   text[];   -- permitted user ids when restricted

-- Hubs-only allow-list. Rows are IMMUTABLE (insert/delete to grant/revoke), so the
-- touch-trigger covers INSERT+DELETE only.
CREATE TABLE resource_visibility (
  family_id  text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  hub_id     text NOT NULL,
  user_id    text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (family_id, hub_id, user_id),
  FOREIGN KEY (family_id, hub_id) REFERENCES hubs(family_id, id) ON DELETE CASCADE
);
CREATE INDEX ON resource_visibility (family_id, user_id);

-- LOAD-BEARING (round-1 P0-1): an allow-list mutation lives in a separate table, so
-- it would NOT advance the hub's (family_id, updated_at, id) cursor — a dropped
-- member would never receive the hub as a tombstone. Touch the hub on every
-- allow-list change so the keyset sync re-surfaces it (once hub-sync exists).
CREATE FUNCTION touch_hub_from_visibility() RETURNS trigger AS $$
BEGIN
  UPDATE hubs SET updated_at = now()
   WHERE id = COALESCE(NEW.hub_id, OLD.hub_id)
     AND family_id = COALESCE(NEW.family_id, OLD.family_id);
  RETURN NULL;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_resvis_touch_hub
  AFTER INSERT OR DELETE ON resource_visibility
  FOR EACH ROW EXECUTE FUNCTION touch_hub_from_visibility();
