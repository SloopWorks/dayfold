-- 0016_hub_timeline.sql — persist the authored Hub timeline (ADR 0045/0046).
-- The timeline column was the one missing link: HubSchema validates `timeline`
-- and the API structurally checks it (hubTimelineIssues), the CLI templates it,
-- and the client renders it — but upsertHub never had a column to store it into,
-- so a pushed timeline validated (200) then silently dropped (pull → timeline:false).
-- Wire shape = the `timeline` sub-object ({ tz, stops[] }); stored as jsonb, same
-- pattern as `media` (0013). Structural/content validation stays in the API write
-- path; the CHECK only guards the jsonb shape.
-- Forward-only plain SQL (ADR 0033). Additive, backward-compatible: NULL = no timeline.

ALTER TABLE hubs ADD COLUMN timeline jsonb;

ALTER TABLE hubs ADD CONSTRAINT hubs_timeline_chk
  CHECK (timeline IS NULL OR jsonb_typeof(timeline) = 'object');
