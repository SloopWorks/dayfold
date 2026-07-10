-- ADR 0053: per-hub participation role on the allow-list. viewer = today's read grant
-- (migration default → zero behavior change); contributor = read+write; co_owner = +manage.
ALTER TABLE resource_visibility ADD COLUMN role text NOT NULL DEFAULT 'viewer';

-- ADR 0053 item 5: a role change must also re-surface the hub as a tombstone to the
-- affected member. Extend the existing touch trigger (0009's touch_hub_from_visibility)
-- to fire on UPDATE too. Function body unchanged — COALESCE(NEW..., OLD...) already
-- resolves correctly for UPDATE (both NEW and OLD are set; NEW wins).
DROP TRIGGER IF EXISTS trg_resvis_touch_hub ON resource_visibility;
CREATE TRIGGER trg_resvis_touch_hub
  AFTER INSERT OR UPDATE OR DELETE ON resource_visibility
  FOR EACH ROW EXECUTE FUNCTION touch_hub_from_visibility();
