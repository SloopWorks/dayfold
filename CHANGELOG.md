# Changelog

All product, API, and feature changes. Follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Version tags: `cli-vX.Y.Z` (CLI), `android-vX.Y.Z` (Android app).

---

## [Unreleased — M1]

Features planned for the next milestone. See `backlog/next.md`.

---

## [M0 — Foundation + Content Wedge] — 2026-06-18 → 2026-06-26

The full MVP wedge is live end-to-end: **Content API + CLI + Client app + Claude skill**.
Google sign-in works on-device; the CLI authors content from a terminal or an AI agent;
the Android app renders it. Auth epic (S1–S6) complete. Block-payload schema canonical.

### App — What's new

- **Brand identity.** Outfit (headings) + Figtree (body) fonts bundled. Material Symbols
  icons for hubs, chrome (lock, countdown, datacenter warning), and bottom navigation
  with filled active state.
- **Now feed.** Briefing cards render full markdown (bold/italic, headings, tables,
  bullets, checkboxes, links, URL autolinks). Image markdown degrades to a 🖼 link
  (never inline-loaded). Card→Hub deep-link tap opens the relevant hub section.
- **Hubs surface.** Event/project containers (starting college, move, vacation, party,
  new baby, medical, school year) with a 3-level section/block tree. Hub blocks render
  all markdown types including tables. Empty sections are hidden (no bare headers).
- **Edge-to-edge layout.** Content stays clear of system bars on all Android targets.
- **Offline-first cache.** SQLDelight-backed local store; data wiped on logout and on
  session expiry (cross-session privacy guardrail, ADR 0014).

### Auth — What's new (S1–S6, ADRs 0011/0021/0023/0025/0027/0029)

- **Device-grant sign-in (RFC 8628).** Family owner scans a QR code or enters a short
  code on their phone to authorize a new device — no password flows, no shared secrets.
  Displays anti-phishing datacenter warning before the approve/deny choice.
- **Google Sign-In via Firebase Auth.** Real OAuth flow; Firebase ID token verified
  server-side. Secure keychain storage for the 45-day refresh token.
- **Per-member invites.** Owner generates an invite QR; new member scans and enters
  the device-grant flow; owner approves or denies from the app.
- **Family selector.** Multi-family grant routing — a device code is routed to the
  correct family when a user is a member of multiple families.
- **Resource-scoped grants.** Hub-level visibility and per-hub access grants; scope
  carried in the access token and enforced server-side.
- **Session management.** Transparent token refresh on 401 (no re-login for normal
  sessions). Session expiry wipes all local family data. Logout clears cache and
  revokes the server-side session.

### Content API — What's new

- **Cards.** REST endpoints for all 6 briefing card types: `file`, `link`, `invite`,
  `contact`, `geo`, `email`. Payload validated against the canonical JSON Schema
  (ADR 0035). Per-card visibility enforced at read time.
- **Hubs, Sections, Blocks.** Hierarchical event/project containers authored via the
  CLI and rendered in the Hubs surface. Block payloads validated server-side.
- **Sync.** `GET /families/:id/sync` keyset-paged endpoint for efficient offline sync.
- **Migration runner.** Tracked, idempotent SQL migration runner (ADR 0033). Manual
  CI workflow for operator-gated prod migrations.
- **Retention sweep.** Vercel Cron job cleans expired auth ephemera (device codes,
  pending grants) — constant-time secret verification prevents abuse.
- **Security policy.** Coordinated disclosure policy in `SECURITY.md` (ADR 0032).

### CLI — What's new

- **Full command set:** `login` · `logout` · `whoami` · `push` · `pull` · `template` · `version`
- **Device-grant login.** `dayfold login` starts the RFC 8628 flow; displays a QR code
  in interactive terminals; polls until the owner approves. `--allow-env-key` permits a
  0600-file fallback on headless hosts without an OS keychain.
- **Push with local validation.** `dayfold push <id> file.json --type <type>` runs
  structural validation against the generated schema before sending — catches wrong
  payload variant, unknown fields, type mismatches. Hub tree always-on pre-check.
- **Pull.** `dayfold pull` returns `{cards, hubs}`. `dayfold pull --hub <id>` returns
  the full section/block tree for one hub.
- **Templates.** `dayfold template <type>` prints a valid starter JSON to stdout for
  all 6 card types plus `hub`, `section`, `block`.
- **Block-payload validation.** Validates canonical schema field names and types before
  the network; the server remains the authority for semantic rules.
- **Homebrew distribution.** `brew install sloopworks/tap/dayfold`. Release pipeline
  via `cli-vX.Y.Z` tag → GitHub Release + formula auto-bump (ADR 0031).
- **Examples.** Ready-to-push content in `apps/cli/examples/` — a full "Starting
  College" hub tree + Now feed cards with a deep-link demo. Push everything with
  `bash push-all.sh`.
- **Claude Code skill.** `dayfold-curator` skill in `.claude/skills/` lets any Claude
  Code session analyze context, propose content, and push via the CLI (propose-confirm
  before every push, ADR guardrails enforced).

### Schema — What's new

- **Canonical block-payload schema (ADR 0035 Option C).** `content.schema.json` is the
  single source of truth for block payload field names. Client, CLI, and server all
  validate against it. Generated Kotlin + TypeScript types from codegen.

### CI / Release pipelines — What's new

- **CI suite:** API (vitest + Postgres), CLI (Kotlin build), Client (desktopTest),
  Android (assembleDebug smoke), debug-drawer, Firebase Auth Emulator integration test,
  naming-guard (ADR 0026 package enforcement).
- **Android release pipeline (ADR 0034).** Push to `main` → internal track; tag
  `android-beta-vX.Y.Z` → beta; tag `android-vX.Y.Z` → production draft (operator
  rolls out in Play Console). All secrets gated — safe to land before store setup.
- **Manual database migration workflow.** `workflow_dispatch` against a `production`
  GitHub Environment; dry-run safe by default (ADR 0033).
- **Secret scanning.** `gitleaks` scans every PR's new commits — catches secrets before
  they land in history.
