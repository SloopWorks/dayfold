-- 0010_hub_sync_fanout.sql — ADR 0030 revocation for the hub SUBTREE.
-- Extends touch_hub_from_visibility (defined in 0009) so a resource_visibility
-- INSERT/DELETE also bumps sections.updated_at + blocks.updated_at for the hub.
-- This ensures that when Task 10 streams sections/blocks by keyset, a revoked
-- hub's children re-surface and tombstone for the dropped member.
--
-- NOTE: the AFTER UPDATE OF visibility ON hubs child fan-out trigger is DEFERRED
-- (no M0 actor flips hub visibility; authoring is ADR-0016/0029-deferred).
-- Add it with the visibility-toggle authoring slice (see backlog/next.md).
CREATE OR REPLACE FUNCTION touch_hub_from_visibility() RETURNS trigger AS $$
DECLARE h text := COALESCE(NEW.hub_id, OLD.hub_id);
        f text := COALESCE(NEW.family_id, OLD.family_id);
BEGIN
  UPDATE hubs     SET updated_at = now() WHERE family_id = f AND id = h;
  UPDATE sections SET updated_at = now() WHERE family_id = f AND hub_id = h;
  UPDATE blocks   SET updated_at = now() WHERE family_id = f AND section_id IN
         (SELECT id FROM sections WHERE family_id = f AND hub_id = h);
  RETURN NULL;
END $$ LANGUAGE plpgsql;
-- trigger trg_resvis_touch_hub (0009) already binds this function on resource_visibility.
