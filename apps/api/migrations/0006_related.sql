-- CL-8 (ADR 0022) — related-edges (cross-links) on the card. Extend
-- briefing_cards in place (D2). Nullable → back-compat. The edges are
-- family-scoped content (ride authorizeTenant); targetId is opaque, resolved
-- client-side vs the OWN family's cache (server never resolves it). Numbering:
-- 0001 m0, 0002-0004 auth, 0005 typed content, this = 0006.

BEGIN;

ALTER TABLE briefing_cards
  ADD COLUMN related        jsonb,   -- array of {relation,targetId,targetType,title?,sub?}
  ADD COLUMN related_kicker text;    -- RELATED section header (wire = relatedKicker)

COMMIT;
