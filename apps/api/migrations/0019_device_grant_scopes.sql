-- ADR 0029: per-hub device-grant scoping. NULL granted_scopes = blanket default
-- (content:read/write/delete) — preserves every unscoped/in-flight grant. A non-null
-- array is the exact grant set the redeemed CLI credential receives.
ALTER TABLE device_authorizations ADD COLUMN granted_scopes text[];
