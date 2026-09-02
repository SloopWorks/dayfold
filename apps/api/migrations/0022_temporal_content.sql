-- ADR 0067: additive dark persistence for canonical temporal facts.
-- Nullable JSONB keeps old rows and old writers compatible. Route/repository code
-- supplies tri-state semantics: omit preserves, object replaces, null clears.
ALTER TABLE briefing_cards ADD COLUMN temporal jsonb;
ALTER TABLE blocks ADD COLUMN temporal jsonb;
