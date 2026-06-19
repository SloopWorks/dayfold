-- S1 auth: identity + M:N tenancy + refresh lineage. New tables only (families/
-- credentials already carry created_by/timestamps/label/scopes/revoked_at).
CREATE TABLE users (
  id           text PRIMARY KEY,
  display_name text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  deleted_at   timestamptz
);

CREATE TABLE user_identities (
  id            text PRIMARY KEY,
  user_id       text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider      text NOT NULL,
  provider_uid  text NOT NULL,
  email_verified boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provider, provider_uid)
);

CREATE TABLE memberships (
  user_id    text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  family_id  text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  role       text NOT NULL CHECK (role IN ('owner','adult')),
  status     text NOT NULL DEFAULT 'active' CHECK (status IN ('pending','active','removed')),
  joined_at  timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, family_id)
);
CREATE INDEX ON memberships (family_id, status);

CREATE TABLE refresh_tokens (
  token_hash    text PRIMARY KEY,
  credential_id text NOT NULL REFERENCES credentials(id) ON DELETE CASCADE,
  superseded_by text,
  consumed_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz NOT NULL
);
CREATE INDEX ON refresh_tokens (credential_id);
