-- 0011_hub_visibility_fanout.sql — ADR 0030 revocation completeness (subtree).
--
-- 0010 DEFERRED the "AFTER UPDATE OF visibility ON hubs" child fan-out, on the
-- premise that "no M0 actor flips hub visibility." That premise is FALSE:
-- PUT /families/:fid/hubs/:id (the content API) updates hubs.visibility on
-- conflict (upsertHub). When a hub flips family→restricted with an empty /
-- unchanged allow-list, NO resource_visibility row changes, so the 0009/0010
-- trg_resvis_touch_hub trigger never fires — the hub's sections + blocks keep
-- their old updated_at and never re-surface in the keyset /sync. A member who
-- already synced the subtree therefore never receives a tombstone, leaving
-- now-forbidden content (e.g. medical block bodies) lingering in their local
-- cache. The hub ROW itself still tombstones (BEFORE UPDATE touch_updated_at
-- bumps hubs.updated_at), so only the subtree leaks — the worse half.
--
-- Fix: bump the subtree's updated_at whenever a hub's visibility actually
-- changes, so sections + blocks re-surface in /sync and tombstone for any
-- member the new visibility excludes. Idempotent (CREATE OR REPLACE + DROP IF).
CREATE OR REPLACE FUNCTION touch_subtree_on_hub_visibility() RETURNS trigger AS $$
BEGIN
  UPDATE sections SET updated_at = now() WHERE family_id = NEW.family_id AND hub_id = NEW.id;
  UPDATE blocks   SET updated_at = now() WHERE family_id = NEW.family_id AND section_id IN
         (SELECT id FROM sections WHERE family_id = NEW.family_id AND hub_id = NEW.id);
  RETURN NULL;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_hub_visibility_fanout ON hubs;
CREATE TRIGGER trg_hub_visibility_fanout
  AFTER UPDATE OF visibility ON hubs
  FOR EACH ROW WHEN (OLD.visibility IS DISTINCT FROM NEW.visibility)
  EXECUTE FUNCTION touch_subtree_on_hub_visibility();
