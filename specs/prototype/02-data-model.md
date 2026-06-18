# 02 — Data Model & DB Schema (Postgres DDL)

> Status: **reviewed (1 agent) → fixes applied**. Concrete DDL from `01-architecture.md` +
> `specs/auth-and-family-design.md` + `specs/event-hubs-design.md` (hardened).
> Postgres. Milestone tags: **[M0]** content/prototype · **[M1]** auth. IDs are
> `text` (client-supplied stable IDs for content; ULID/uuid for auth rows).
> All mutable tables carry `created_at timestamptz`, `updated_at timestamptz`,
> and content/family tables `deleted_at timestamptz` (soft-delete).

## Enums

```sql
CREATE TYPE role             AS ENUM ('owner','adult');           -- 'teen' deferred (ADR 0005)
CREATE TYPE membership_status AS ENUM ('pending','active','removed');
CREATE TYPE invite_status    AS ENUM ('active','revoked','exhausted','expired');
CREATE TYPE invite_mode      AS ENUM ('qr','link');
CREATE TYPE device_status    AS ENUM ('pending','approved','denied','expired');
CREATE TYPE credential_kind  AS ENUM ('app','cli');
CREATE TYPE auth_provider    AS ENUM ('google','apple','phone');
CREATE TYPE hub_status       AS ENUM ('planning','active','archived');
CREATE TYPE block_type       AS ENUM ('text','markdown','link','checklist',
                                      'document','milestone','contact','location','budget');
CREATE TYPE card_kind        AS ENUM ('action','info','weather','countdown');
```

## [M0] Families & content

```sql
CREATE TABLE families (
  id          text PRIMARY KEY,
  name        text NOT NULL,
  created_by  text,                         -- FK users(id) added at M1 (nullable at M0)
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz
);

CREATE TABLE hubs (
  id           text NOT NULL,
  family_id    text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  type         text NOT NULL,               -- template-catalog key (ADR 0006)
  title        text NOT NULL,
  status       hub_status NOT NULL DEFAULT 'active',
  start_at     timestamptz,                 -- promoted from JSON: indexable
  end_at       timestamptz,
  countdown_to timestamptz,
  version      bigint NOT NULL DEFAULT 1,   -- optimistic concurrency (If-Match)
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  deleted_at   timestamptz,
  PRIMARY KEY (family_id, id)               -- client IDs unique within family
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
  payload     jsonb,                         -- structured fields (link/checklist/contact/…)
  body_md     text,                          -- long-form markdown (text/markdown blocks) — inline at M0
  body_ref    text,                          -- [M1] object-storage key when body spilled (>~1–few MB)
  provenance  jsonb NOT NULL,                -- { source, at, credential_id }
  triggers    jsonb,                          -- [ADR 0014] geo/when/activity; matched on-device only
  ord         int NOT NULL DEFAULT 0,
  version     bigint NOT NULL DEFAULT 1,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  PRIMARY KEY (family_id, id),
  FOREIGN KEY (family_id, section_id) REFERENCES sections(family_id, id) ON DELETE CASCADE,
  CHECK (body_md IS NULL OR body_ref IS NULL)  -- one-of: inline OR spilled, never both
);

CREATE TABLE briefing_cards (              -- the "Now" surface
  id          text NOT NULL,
  family_id   text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  kind        card_kind NOT NULL DEFAULT 'info',
  title       text NOT NULL,
  body_md     text,                          -- limited inline markdown only
  -- deep-link target into a Hub (nullable; resolved nearest-ancestor client-side):
  target_hub_id     text,
  target_section_id text,
  target_block_id   text,
  provenance  jsonb NOT NULL,
  triggers    jsonb,                          -- [ADR 0014] geo/when/activity; matched on-device only
  not_before  timestamptz,                   -- when the card should surface
  expires_at  timestamptz,
  version     bigint NOT NULL DEFAULT 1,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  PRIMARY KEY (family_id, id)
  -- deep-link targets (hub/section/block) are intentionally UNVALIDATED text:
  -- no FK (composite SET NULL on a NOT-NULL family_id won't compile, and we
  -- don't want a write-time cross-row dependency). They resolve ONLY against
  -- the requester's own tenant-scoped cache, nearest-ancestor fallback. The
  -- server never dereferences them cross-row, so no IDOR surface.
);
```

CREATE TABLE places (                       -- [ADR 0014] reusable named places; family content
  id          text NOT NULL,
  family_id   text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  label       text NOT NULL,
  lat         double precision NOT NULL,     -- encrypted at rest; never logged; never live position
  lng         double precision NOT NULL,
  radius_m    int NOT NULL DEFAULT 150,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  PRIMARY KEY (family_id, id)
);

## [M1] Identity, tenancy, credentials

```sql
CREATE TABLE users (
  id           text PRIMARY KEY,
  display_name text,                         -- nullable: phone-only first-run has no name (fallback derived)
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  deleted_at   timestamptz
);

CREATE TABLE user_identities (
  id            text PRIMARY KEY,
  user_id       text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider      auth_provider NOT NULL,
  provider_uid  text NOT NULL,               -- Apple: sub. Join key — never dedupe on relay email
  email_verified text,
  phone_verified text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provider, provider_uid)
);

CREATE TABLE memberships (
  user_id    text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  family_id  text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  role       role NOT NULL DEFAULT 'adult',
  status     membership_status NOT NULL DEFAULT 'pending',
  joined_at  timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, family_id)
);
-- Invariant (app + trigger): >=1 active owner per family; block remove/demote of last owner.

CREATE TABLE invites (
  id          text PRIMARY KEY,
  family_id   text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  role        role NOT NULL DEFAULT 'adult',
  token_hash  text NOT NULL UNIQUE,          -- SHA-256 of high-entropy token; raw shown once
  mode        invite_mode NOT NULL,
  expires_at  timestamptz NOT NULL,
  max_uses    int NOT NULL DEFAULT 1,
  used_count  int NOT NULL DEFAULT 0,
  status      invite_status NOT NULL DEFAULT 'active',
  created_by  text NOT NULL REFERENCES users(id),
  created_at  timestamptz NOT NULL DEFAULT now()
);
-- Atomic claim guard:
-- UPDATE invites SET used_count=used_count+1
--  WHERE id=$1 AND status='active' AND used_count<max_uses AND expires_at>now()
--  RETURNING *;   -- 0 rows ⇒ reject (expired/exhausted/revoked)

CREATE TABLE device_authorizations (
  device_code text PRIMARY KEY,             -- high-entropy, one-time
  user_code   text NOT NULL,                -- >=8 ch, unambiguous alphabet
  user_id     text REFERENCES users(id),    -- bound at approval
  family_id   text REFERENCES families(id), -- bound at approval (selector)
  client      text NOT NULL DEFAULT 'cli',
  scope       text NOT NULL DEFAULT 'content:write',
  status      device_status NOT NULL DEFAULT 'pending',
  expires_at  timestamptz NOT NULL,         -- ~10 min
  interval_s  int NOT NULL DEFAULT 5,
  approved_at timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX device_user_code_pending
  ON device_authorizations(user_code) WHERE status='pending';

CREATE TABLE credentials (                  -- "Connected devices & apps"; also M0 household token row
  id            text PRIMARY KEY,
  user_id       text REFERENCES users(id) ON DELETE CASCADE,  -- NULL for the M0 household token
  family_scope  text REFERENCES families(id),                 -- single family for kind='cli'
  kind          credential_kind NOT NULL,
  scopes        text[] NOT NULL DEFAULT '{content:write}',
  refresh_hash  text UNIQUE,
  label         text,
  last_used_at  timestamptz,
  last_used_ip  text,
  created_ua    text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  revoked_at    timestamptz,
  CHECK (kind <> 'cli' OR family_scope IS NOT NULL)   -- cli creds (incl. M0 token) MUST be family-scoped
);
```

> **M0 note:** the household token is a `credentials` row (`kind='cli'`,
> `user_id NULL`, `family_scope` = the one family, `scopes='{content:write}'`,
> revocable via `revoked_at`). The same middleware resolves it to that family —
> no skip-authz path. Secret itself lives in the platform secret store, not the DB.

## Indexes (hot paths)

```sql
CREATE INDEX ON memberships (family_id, status);
CREATE INDEX ON hubs (family_id, status) WHERE deleted_at IS NULL;
CREATE INDEX ON sections (family_id, hub_id, ord);
CREATE INDEX ON blocks (family_id, section_id, ord);
CREATE INDEX ON briefing_cards (family_id, not_before) WHERE deleted_at IS NULL;
CREATE INDEX ON invites (family_id, status);
CREATE INDEX ON credentials (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ON device_authorizations (expires_at) WHERE status='pending';  -- reaper/sweep
-- FTS (event-hubs §Markdown): GIN over raw body_md, live rows only
CREATE INDEX blocks_body_fts ON blocks USING gin (to_tsvector('english', coalesce(body_md,'')))
  WHERE deleted_at IS NULL;
```

## Integrity rules (enforced in app + DB where possible)

- **Tenancy:** every content row carries `family_id`; all queries filter it
  (the middleware supplies it from the path). Composite PKs `(family_id, id)`
  keep client IDs unique per family and make the FKs family-scoped.
- **Last-owner invariant:** trigger/app guard rejects removing/demoting the
  only `active` `owner`; ownership transfer required first.
- **Account deletion:** cascade users→identities/memberships/credentials;
  reassign or archive owned families (honor last-owner); Apple `revokeToken`.
- **Idempotent upsert:** `PUT` by `(family_id, id)`; **parent must exist**
  (else 409/404); `version` bumped per write; `If-Match` for optimistic
  concurrency when multi-writer arrives (M0 = single-writer LWW).
- **Soft-delete** content (`deleted_at`) so deep-link "that item moved"
  resolves gracefully; hard-delete auth ephemera (device codes swept on expiry).

## Review fixes (applied / to honor in migration)

- **Deletion model is soft-delete-authoritative.** Routine family/user/content
  deletion = `UPDATE deleted_at`, cascaded **in app**. The `ON DELETE CASCADE`
  on the content tree (family→hub→section→block/card) is a **hard-purge
  backstop only**; destructive parents at M1 (`users`, and `families.created_by`)
  use **`ON DELETE RESTRICT`/`SET NULL`** to prevent accidental hard cascades.
  Define `families.created_by text REFERENCES users(id) ON DELETE SET NULL` at M1.
  Do not mix routine deletes with hard cascade.
- **Last-owner invariant needs a lock** (not a bare `COUNT`): the demote/remove
  trigger/transaction takes `pg_advisory_xact_lock(hashtext(family_id))` (or
  `SELECT … FOR UPDATE` the family's owner rows) before checking ≥1 active
  owner — else concurrent demotions race to zero owners.
- **Invite claim** (single transaction): the atomic `used_count` bump ALSO flips
  `status = CASE WHEN used_count+1 >= max_uses THEN 'exhausted' ELSE status END`,
  **and inserts the `pending` membership in the same transaction**. A separate
  sweep sets `status='expired'` where `expires_at < now()`.
- **`hubs.type`** is validated against the bounded template catalog (ADR
  0004/0006) at the **app layer** (or a `hub_types` lookup table + FK); not free
  text in practice.
- **`updated_at` + `version` ownership:** `updated_at` auto-touched by a DB
  trigger; `version` bumped by the app per write (drives `If-Match`). State this
  so optimistic-concurrency semantics are reliable.
- **Empty `text`/`markdown` blocks** are permitted (drafts); add
  `CHECK (type NOT IN ('text','markdown') OR body_md IS NOT NULL OR body_ref IS NOT NULL)`
  only if empties should be rejected — deferred.
- **Scope representation** differs deliberately: `device_authorizations.scope`
  is single-scope (scalar) for the grant; `credentials.scopes text[]` is
  multi-scope for the issued credential.
- **`provenance.credential_id`** stays in `provenance jsonb` for MVP (audit via
  jsonb); promote to a typed FK column only if credential-level audit queries
  become hot.

## Open questions
- ID format for client-supplied content IDs (ULID recommended) — confirm in 03-api.
- Whether `briefing_cards` need sections/ordering or a flat priority field (lean flat: `not_before` + `kind`).
- Migration tool (Drizzle/Prisma/raw SQL) — decide with the TS host (03/C3).
