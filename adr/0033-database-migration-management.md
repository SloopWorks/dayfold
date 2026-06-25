# ADR 0033: Database Migration Management — Tracked, Idempotent, Verified

## Status

**Proposed** 2026-06-25 (agent-drafted from the 2026-06-25 prod auth outage).
Operator-gated: this changes the deploy/migration **process** and adds a tracking
table → ADR-class (maintenance burden + customer-data handling). Composes with the
already-merged guard rails — PR #91 (`migrations.test.ts` drift guard: the full set
applies cleanly in CI) and PR #93 (`scripts/schema-drift.mjs`: report a live DB's
missing tables) — and with **ADR 0012** (agent-operated build). Does NOT supersede
any ADR. Immutable once Accepted — supersede, don't edit.

## Context

Migrations live in `apps/api/migrations/000N_*.sql` and are applied **by hand**
(`psql -f`), with three structural weaknesses:

1. **No record of what's applied.** Nothing tracks which migrations a given database
   has run. The only way to know is to inspect the schema.
2. **Not idempotent.** Plain `CREATE TABLE` / `CREATE TYPE` (no `IF NOT EXISTS`), so
   re-running the set errors on already-present objects — re-application is unsafe.
3. **No safe apply path.** There is no command that brings an arbitrary database to
   the current schema; the operator hand-runs each file.

This caused a production outage on **2026-06-25**: the entire AUTH-epic schema
(`0002`/`0003`/`0008`) had never been applied to prod (prod had only ever served the
legacy `HOUSEHOLD_SECRET` content path), so the first real Google sign-in and CLI
device-login both 500'd (`relation "user_identities"/"rate_limits" does not exist`).
Diagnosis required scraping serverless logs one missing relation at a time, and the
operator's first remediation `psql`'d the **wrong Neon branch** (passwordless
`pg.neon.tech` resolved a different project than the Vercel integration's DB).

PR #91 now proves the migration *set* is internally valid in CI, and PR #93 reports
drift for a *given* live DB — but there is still no tracked, idempotent, repeatable
**apply** mechanism. Deploy drift between `migrations/` and any environment remains
structurally possible.

## Decision

Adopt a **tracked migration runner** (Option A below).

1. **Tracking table.** A new migration adds `schema_migrations(version text primary
   key, applied_at timestamptz default now(), checksum text)`. Every applied
   migration records a row.

2. **Runner.** `apps/api/scripts/migrate.mjs` (in-repo, `pg`-only, no heavyweight
   dep — consistent with `provision.mjs`/`schema-drift.mjs`) that, against
   `DATABASE_URL`: reads `migrations/` in order, skips versions already in
   `schema_migrations`, applies each *pending* file inside its own transaction
   (DDL is transactional in Postgres), and records it. Re-running is a no-op. A
   `--dry-run` lists pending without applying. Exposed as `npm run db:migrate`.

3. **Backfill once.** Existing manually-applied environments get a one-time
   `schema_migrations` backfill (mark the versions already present as applied — keyed
   off `schema-drift.mjs`'s table check) so the runner doesn't re-apply them.

4. **Migrations stay as authored.** No retrofit to `IF NOT EXISTS` is required (the
   tracking table makes idempotency unnecessary), avoiding the `CREATE TYPE`
   no-`IF NOT EXISTS` complication. New migrations remain plain forward-only SQL.

5. **CI + deploy wiring.** The #91 drift-guard stays (set validity). Deploy runs
   `db:migrate` against prod before/with the function publish (agent-operated under
   ADR 0012 rails: test-green-before, verify-after via `schema-drift.mjs`, log the
   prod action). The manual `psql -f` path is retired.

**Alternatives considered.**
- **B — idempotent migrations + "run all":** make every file `IF NOT EXISTS`/`ADD
  COLUMN IF NOT EXISTS` and re-run the whole set each deploy. Simpler (no table), but
  re-runs everything, needs `DO`-block guards for `CREATE TYPE`, retrofits 11 files,
  and still records nothing. Rejected as the primary, but its idempotent SQL is a
  useful *emergency* artifact (see `scratchpad/apply_auth_schema_prod.sql`).
- **C — status quo + #93 as a manual pre-deploy gate:** cheapest, but relies on human
  discipline, which just failed. Keep #93 as a verify tool, not the mechanism.
- **Off-the-shelf (node-pg-migrate / dbmate / Drizzle):** mature, but adds a dep +
  conventions for a project with 11 hand-written SQL files; the in-repo runner is
  ~60 lines and matches existing tooling. Revisit if migrations outgrow plain SQL.

## Consequences

- **Drift becomes structurally impossible** between `migrations/` and any environment
  the runner targets — the recurrence class of the 2026-06-25 outage is closed.
- A new additive `schema_migrations` table; a `db:migrate` deploy step; the operator
  stops hand-running `psql`. One-time backfill needed for existing prod/preview DBs.
- Forward-only (no down-migrations) — matches the current convention; rollbacks are a
  new forward migration. Acceptable at this stage.
- The agent may run `db:migrate` on prod under ADR 0012 rails (it's the documented,
  tested mechanism), narrowing — not widening — the existing prod-DDL exposure.

## Revisit Trigger

- Migrations need data backfills / non-transactional steps (e.g. `CREATE INDEX
  CONCURRENTLY`) that one-transaction-per-file can't express.
- A second app/service starts sharing the DB and needs coordinated migration order.
- The team adopts an ORM/typed-schema tool whose own migration system supersedes this.
