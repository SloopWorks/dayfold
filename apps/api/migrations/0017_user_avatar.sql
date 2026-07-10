-- Delta A: self-profile avatar. avatar_ref = bundled avatar id (e.g. 'avatar:fox-01'),
-- NOT an object-storage key (ADR 0036 posture — no upload, no external fetch).
-- Forward-only plain SQL (ADR 0033). Additive, backward-compatible: NULL = unset.
ALTER TABLE users ADD COLUMN avatar_color text;
ALTER TABLE users ADD COLUMN avatar_ref   text;
