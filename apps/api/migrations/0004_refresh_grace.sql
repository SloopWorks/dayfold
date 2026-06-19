-- Track grace-rotation provenance so concurrent double-present collisions
-- can be distinguished from genuine replay-after-chain-advance reuse.
-- graced_from: token_hash of the consumed ancestor that triggered this grace rotation.
ALTER TABLE refresh_tokens ADD COLUMN graced_from text;
