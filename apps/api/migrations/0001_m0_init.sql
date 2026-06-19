-- M0 migration — plaintext, feed-first. Content tables (hubs/sections/blocks
-- stay authorable though Hub render is deferred), places, families, and the
-- credentials row that backs the household token. Auth tables (users/
-- memberships/invites/device_authorizations) are M1. Derived from 02-data-model.

BEGIN;

-- enums (M0 subset) -------------------------------------------------------
CREATE TYPE hub_status      AS ENUM ('planning','active','archived');
CREATE TYPE block_type      AS ENUM ('text','markdown','link','checklist',
                                     'document','milestone','contact','location','budget');
CREATE TYPE card_kind       AS ENUM ('action','info','weather','countdown');
CREATE TYPE place_kind      AS ENUM ('home','school','store','other');
CREATE TYPE credential_kind AS ENUM ('app','cli');

-- families & content ------------------------------------------------------
CREATE TABLE families (
  id          text PRIMARY KEY,
  name        text NOT NULL,
  created_by  text,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz
);

CREATE TABLE hubs (
  id           text NOT NULL,
  family_id    text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  type         text NOT NULL,
  title        text NOT NULL,
  status       hub_status NOT NULL DEFAULT 'active',
  start_at     timestamptz, end_at timestamptz, countdown_to timestamptz,
  version      bigint NOT NULL DEFAULT 1,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  deleted_at   timestamptz,
  PRIMARY KEY (family_id, id)
);

CREATE TABLE sections (
  id          text NOT NULL,
  family_id   text NOT NULL,
  hub_id      text NOT NULL,
  title       text,
  ord         int  NOT NULL DEFAULT 0,
  version     bigint NOT NULL DEFAULT 1,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  PRIMARY KEY (family_id, id),
  FOREIGN KEY (family_id, hub_id) REFERENCES hubs(family_id, id) ON DELETE CASCADE
);

CREATE TABLE blocks (
  id          text NOT NULL,
  family_id   text NOT NULL,
  section_id  text NOT NULL,
  type        block_type NOT NULL,
  payload     jsonb,
  body_md     text,
  body_ref    text,
  provenance  jsonb NOT NULL,
  triggers    jsonb,
  actions     jsonb,
  ord         int NOT NULL DEFAULT 0,
  version     bigint NOT NULL DEFAULT 1,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  PRIMARY KEY (family_id, id),
  FOREIGN KEY (family_id, section_id) REFERENCES sections(family_id, id) ON DELETE CASCADE,
  CHECK (body_md IS NULL OR body_ref IS NULL)
);

CREATE TABLE briefing_cards (
  id          text NOT NULL,
  family_id   text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  kind        card_kind NOT NULL DEFAULT 'info',
  title       text NOT NULL,
  body_md     text,
  target_hub_id text, target_section_id text, target_block_id text,
  provenance  jsonb NOT NULL,
  triggers    jsonb,
  actions     jsonb,
  not_before  timestamptz, expires_at timestamptz,
  version     bigint NOT NULL DEFAULT 1,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  PRIMARY KEY (family_id, id)
);

CREATE TABLE places (
  id          text NOT NULL,
  family_id   text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  label       text NOT NULL,
  kind        place_kind NOT NULL DEFAULT 'other',
  lat         double precision NOT NULL,
  lng         double precision NOT NULL,
  radius_m    int NOT NULL DEFAULT 150,
  version     bigint NOT NULL DEFAULT 1,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  PRIMARY KEY (family_id, id)
);

-- credentials (M0: the household-token row; kind='cli', user_id NULL) ------
CREATE TABLE credentials (
  id            text PRIMARY KEY,
  user_id       text,
  family_scope  text REFERENCES families(id),
  kind          credential_kind NOT NULL,
  scopes        text[] NOT NULL DEFAULT '{content:read,content:write}',
  label         text,
  last_used_at  timestamptz, last_used_ip text, created_ua text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  revoked_at    timestamptz,
  CHECK (kind <> 'cli' OR family_scope IS NOT NULL)
);

-- indexes -----------------------------------------------------------------
-- sync keyset (covers live + tombstoned), per content table:
CREATE INDEX ON hubs           (family_id, updated_at, id);
CREATE INDEX ON sections       (family_id, updated_at, id);
CREATE INDEX ON blocks         (family_id, updated_at, id);
CREATE INDEX ON briefing_cards (family_id, updated_at, id);
CREATE INDEX ON places         (family_id, updated_at, id);
-- render/list:
CREATE INDEX ON hubs (family_id, status) WHERE deleted_at IS NULL;
CREATE INDEX ON sections (family_id, hub_id, ord);
CREATE INDEX ON blocks (family_id, section_id, ord);
CREATE INDEX ON briefing_cards (family_id, not_before) WHERE deleted_at IS NULL;
CREATE INDEX ON credentials (user_id) WHERE revoked_at IS NULL;
-- FTS (kept at M0, plaintext):
CREATE INDEX blocks_body_fts ON blocks USING gin (to_tsvector('english', coalesce(body_md,'')))
  WHERE deleted_at IS NULL;

-- updated_at auto-touch (LOAD-BEARING for sync: a soft-delete UPDATE must bump
-- updated_at so the tombstone sorts PAST the keyset cursor, else deletes are
-- silently never synced — 02 §version-ownership, 03 §sync).
CREATE FUNCTION touch_updated_at() RETURNS trigger AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_touch BEFORE UPDATE ON families        FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_touch BEFORE UPDATE ON hubs            FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_touch BEFORE UPDATE ON sections        FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_touch BEFORE UPDATE ON blocks          FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_touch BEFORE UPDATE ON briefing_cards  FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_touch BEFORE UPDATE ON places          FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- NOTE: intentionally hardens 02 — places.version added (sync needs it; matches
-- the generated PlaceSchema) and *_kind/status use enums not free text.

COMMIT;
