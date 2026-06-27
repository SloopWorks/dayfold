-- 0013_visual_enrichment.sql — Hub & card visual enrichment (ADR 0036)
-- Additive, expand-only, backward-compatible: NULL media = today's unenriched look.
-- Wire shape = the `media` sub-object (Hub.media / BriefingCard.media); stored as
-- jsonb here. Block enrichment (thumbnailUrl/avatarUrl) rides existing `payload jsonb`
-- and needs no DDL. URL/host/icon/hex validation is enforced in the API write path
-- (shared hardened validator), not in SQL — the CHECK only guards the jsonb shape.
-- Forward-only plain SQL (ADR 0033). Safe to deploy anytime.

ALTER TABLE hubs           ADD COLUMN media jsonb;
ALTER TABLE briefing_cards ADD COLUMN media jsonb;

ALTER TABLE hubs           ADD CONSTRAINT hubs_media_chk
  CHECK (media IS NULL OR jsonb_typeof(media) = 'object');
ALTER TABLE briefing_cards ADD CONSTRAINT cards_media_chk
  CHECK (media IS NULL OR jsonb_typeof(media) = 'object');
