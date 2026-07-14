# Decisions Index

Index of decision records. The ADRs themselves live in `adr/`. Once
accepted, an ADR is immutable — supersede it with a new ADR rather than
editing it.

**This table is a short index, not a duplicate.** Each row is a one-line
summary + status; the full rationale, composed ADRs, and rejected
alternatives for a decision live in its own `adr/NNNN-*.md` file — read that
file when you need the detail. (Trimmed from long-form per-row summaries to
this format 2026-07-13, repo-maintenance pass, for agentic context-usage —
nothing lost, every full write-up is unchanged in its own file.)

| ADR | Title | Status | Summary |
|-----|-------|--------|---------|
| 0000 | ADR Template | Template | — |
| 0001 | Repo Markdown as Source of Truth; Memory Systems as Working Memory | Accepted 2026-06-18 | Repo Markdown is the reviewed source of truth; any connected memory system is working memory only. |
| 0002 | Execution & Tracking Model — Phase-Gated, Agent-Operated, Operator-Escalated | Accepted 2026-06-18 | Phase-gated execution; agents operate day-to-day, operator is escalated for gated decisions. |
| 0003 | Planning-Loop Operating Model — Autonomous Waterfall Deepening with Confidence-Gated Decisions | Accepted 2026-06-18 | Defines the autonomous planning loop + the confidence protocol (HIGH/MEDIUM/LOW decision routing). |
| 0004 | Product Framing — Calm Family Briefing Surface, Content-API-Fed, Adults-Only MVP | Accepted 2026-06-18 (adults-only clause: supersession proposed in 0005) | What Dayfold is/isn't; content-API-fed MVP; adults-only accounts. |
| 0005 | Minor Accounts (14+) Permitted at MVP, No Email Integration for Minor Profiles | Proposed 2026-06-18 (operator-gated; pending counsel) | Would permit 14+ minor accounts without email integration; blocked on legal/COPPA counsel review. |
| 0006 | Event Hubs — Co-Equal Curated-Dossier Surface, App-Owned, Push-Curated | Accepted 2026-06-18 | Hubs = a co-equal curated-dossier surface alongside the briefing feed. |
| 0007 | Prototype Scope — Operator-Driven Dumb Renderer | Accepted 2026-06-18 | M0 prototype = a dumb renderer; intelligence is authored externally via the content API. |
| 0008 | Design-First — Hi-Fi UI/UX Mockups Precede Deep Planning and Build | Accepted 2026-06-18 | No deep planning/build of a new surface before a hi-fi mockup + operator sign-off exists. |
| 0009 | Design System — Material 3 Expressive, Adaptive, Compose | Accepted 2026-06-18 | Material 3 Expressive design system, adaptive layouts, Compose Multiplatform. |
| 0010 | Auth & Family-Tenancy Architecture (Firebase Auth, M:N membership, RFC 8628 device grant) | Superseded by 0011 (2026-06-18) | Superseded — see ADR 0011. |
| 0011 | Auth & Family-Tenancy Architecture (Hardened) | Accepted 2026-06-18 (supersedes 0010; post-5-agent-review) | Hardened auth architecture: Firebase Auth, M:N family membership, RFC 8628 CLI device grant. |
| 0012 | Agent-Operated Build & Deploy — Autonomy Boundaries | Accepted 2026-06-18 | Agents may configure/deploy (incl. prod) + take bounded cost actions under safety rails (test-green-before, verify-rollback-after, log every prod/cost action). |
| 0013 | Client Architecture — KMP/CMP Shared UI + redux-kotlin 1.0.0-alpha1 | Accepted 2026-06-18 (build pin → 1.0.0-alpha01) | KMP/Compose Multiplatform shared UI; redux-kotlin store as single state source (`f(store.state)→UI`). |
| 0014 | Private On-Device Trigger Engine (geo/time/activity) | Accepted 2026-06-18 | Triggers are metadata; matching happens on-device; live position never leaves the device. |
| 0015 | End-to-End Encryption | Proposed — scoped to M1 | M0 ships plaintext; live E2EE reserved as an M1 option, gated by ADR 0017. |
| 0016 | Two-Way Interactive Pull-Loop (reserved, bounded-now) | Proposed 2026-06-18 | Reserves a bounded intents/actions channel for future member→AI interaction; additive, not yet activated. |
| 0017 | E2E Key-Authenticity + Deploy Trust-Root Boundary | Proposed 2026-06-18 | M1 security gate: fake-key MITM + trust-root concentration risks for a future E2EE flip. |
| 0018 | API Host — TypeScript on Vercel | Accepted 2026-06-18 | Content API in TypeScript/Hono on Vercel; CLI/client stay Kotlin; types codegen from schema. |
| 0019 | Client Observability & Tooling (devtools/snapshots/CLI) | Accepted 2026-06-19 | redux-kotlin devtools + in-app debug drawer + Compose snapshot testing toolchain. |
| 0020 | Offline-First Client Data + Freshness (DB-as-source-of-truth) | Proposed 2026-06-19 | network→DB(SQLDelight)→store→UI unidirectional flow; instant offline cold start; foreground poll. |
| 0021 | Auth Pulled Into Active Build — S1–S6 Decomposition + Dev-Token Posture | Accepted 2026-06-19 | Sequences the auth build (S1 tenancy → S3 CLI device-grant → S2 Firebase → S4 invites → S5/S6 UI). |
| 0022 | Typed Content Library, Detail View & the Fold Gesture | Accepted 2026-06-19 | 6 typed content types with one renderer, container-transform detail gesture, provenance+privacy chips, Dayfold M3 theme. |
| 0023 | S2 Firebase Identity — Google + Apple, Phone-OTP deferred | Accepted 2026-06-20 | Firebase sign-in narrowed to Google + Apple (free tier); phone/OTP deferred. |
| 0024 | Settings Privacy Posture — Tiering Boundary, Never-Syncs Rule, Telemetry Consent & E2EE Line | Proposed 2026-06-21 | Defines what never syncs (location-permission state, theme, a11y, devtools) + telemetry-consent default OFF. |
| 0025 | Auth Abuse-Control — Rate Limits & Lockout Constants | Accepted 2026-06-21 | Single source of truth for auth rate-limit/lockout constants (device-authorize, invite-mint, invite-redeem lockout). |
| 0026 | Package & App-ID Naming — `com.sloopworks.*` Umbrella | Accepted 2026-06-21 | Store id `com.sloopworks.dayfold`; namespaces `…dayfold.android`/`client`/`cli`/`schema`; full env/npm/binary/path rename. |
| 0027 | S2 Firebase Verify — Direct JWKS, Emulator-in-CI, Android-First | Proposed 2026-06-22 | Verify Firebase ID tokens via direct JWKS (no Admin SDK); Firebase Auth Emulator in CI; Android-only first. |
| 0028 | Geocoding Strategy & Location-Privacy Tiers | Accepted 2026-06-22 | Geocoding never server-side; author-supplied/CLI-offline coordinates only at M0; third-party geocoder deferred. |
| 0029 | CLI / Token Resource-Scoped Grants — Per-Hub Read/Write Scoping | Accepted 2026-06-23 | `credential_grants` table + per-hub/resource-qualified scope strings, resolved per request via a central `requireScope` gate. |
| 0030 | Per-Member Hub & Card Visibility — Restricted Resources at MVP | Accepted 2026-06-23 | Hubs get `visibility` (family/restricted) + `created_by` + a `resource_visibility` allow-list; cards get a flat author-stamped `audience[]`. Owner NOT auto-permitted on restricted content. |
| 0031 | CLI Distribution — First-Party Homebrew Tap (Zero-Config JVM) | Accepted 2026-06-25 | `brew install sloopworks/tap/dayfold`; tag-driven release pipeline auto-bumps the tap formula. Operator setup gates still open. |
| 0032 | Licensing & Open-Source Posture — Apache Client/CLI + AGPL Server, Hosted-SaaS Monetization | Proposed 2026-06-25 (operator-gated + pending-counsel) | Apache-2.0 client/CLI/schema + AGPL-3.0 server; monetize via hosted SaaS only; DCO contributions. |
| 0033 | Database Migration Management — Tracked, Idempotent, Verified | Accepted 2026-06-26 | `schema_migrations` tracking table + `scripts/migrate.mjs` runner (dry-run, backfill, re-run-safe). |
| 0034 | Mobile Release Pipeline — Alpha/Beta/Prod to Google Play | Accepted 2026-06-26 | 3-track Android release train (main→internal, beta tag→beta, prod tag→production draft) via fastlane; inert until operator store-account gates close. |
| 0035 | Block-Payload Schema Reconciliation — One Source of Truth | Accepted 2026-06-26 | Aligned `content.schema.json`'s block payload to the client render model + added block-payload validation (CLI+server). |
| 0036 | External-Image-URL Privacy Posture — HTTPS Host-Allowlist + Hardened Parser (Phase 1) | Accepted 2026-06-26 | Author-supplied image URLs validated against a shared hardened rule; Phase-1 allowlist = exactly `upload.wikimedia.org`. |
| 0037 | CLI Continuous "Edge" Channel + `dayfold update` | Accepted 2026-06-26 | A `cli-edge` GitHub pre-release rebuilt on every `main` push touching `apps/cli/**`; `dayfold update` delegates to `brew upgrade`. |
| 0038 | Two-Way Collaborative Content — Direct Member Mutation Primitive (interactive to-do) | Accepted 2026-06-29 | First two-way data-flow primitive: members toggle checklist items; client-side per-item LWW over a server-opaque relay. |
| 0039 | Two-Way Mutation Engine — Typed-Op Spine, Two Channels, Lifecycle Ops | Accepted 2026-06-29 | Generalizes ADR 0038 into a typed-op outbox → `POST /mutations`, with a content-delta channel (server relays) and an intent channel (key-holder AI reasons). |
| 0040 | Freshness Spectrum & Tombstone Retention — Daily-Poll through Realtime on One Cursor | Accepted 2026-06-29 | One keyset+tombstone `/sync` cursor serves any client cadence (poll/background/push/realtime); stale-cursor full-resync directive + tombstone-retention floor. |
| 0041 | Constitution Amendment — Bounded Member-Authored AI Commands | Accepted 2026-06-29 | Narrows the "not a chatbot" constitution line to permit bounded, async, no-reply member commands to the key-holding AI loop (W3, EXPERIMENTAL/flagged). |
| 0042 | Activate the Intents Channel for W3 — Bounded Member Commands to the Key-Holder Loop | Accepted 2026-06-29 | Concretizes ADR 0039's intent channel: `intents` table + endpoints, bounded-to-submitter safety core, key-holder-only placement. |
| 0043 | Now Content Model — Derived + Authored Two-Lane Surfacing with an On-Device Priority Engine | Accepted 2026-06-30 | Splits Now into Derived (client-synthesized, no server persistence) + Authored lanes, ranked by one on-device Priority & Ordering Engine. |
| 0044 | Phase B — Background-Location & Local-Notification Surfacing Posture | Accepted 2026-06-30 | Background geofence + LOCAL-only notifications (no FCM/APNs) for the ADR 0043 engine; "Always" location opt-in/reversible/never-up-front. |
| 0045 | Hub Timeline — Authored Hub Property with On-Device Presentation | Accepted 2026-06-30 | A new authored `Hub.timeline` property (author writes stops; client computes status/scale/grouping as a pure presenter). |
| 0046 | Client-Derived Timeline Fallback — a Second On-Device Projection over Hub Content | Accepted 2026-07-01 | When a hub has no authored timeline, client derives one from already-synced dated blocks; render-only, "Built from this hub" provenance. |
| 0047 | Client `:ui` Module Extraction — Incremental Split of the Monolithic `:client` | Accepted 2026-07-02 | Extracted a Compose `:ui` KMP module from `:client` to shrink the UI-edit recompile unit (mitigates a Kotlin incremental-compile fallback). |
| 0048 | Invite Deep-Links — Android App Links now, iOS Universal Links deferred | Accepted 2026-07-07 | Android App Links handle invite URLs now; iOS Universal Links deferred until an Apple Developer account exists. |
| 0049 | Content-Authored Geofences — proximity posture for authored triggers | Accepted 2026-07-08 | Authored (Claude/CLI) geo triggers get foreground-only surfacing; background geofences stay user-curated via saved places only. |
| 0050 | Card↔Detail Container Transform Uses Plain `AnimatedContent`, Not `SeekableTransitionState` | Accepted 2026-07-08 | Fixed the card→detail morph regressing to a flat crossfade; predictive back is now commit-animated, not finger-scrubbed. |
| 0051 | Navigation Transition System — Taxonomy, Tokens, Central Route-Motion Host | Accepted 2026-07-08 | A central motion taxonomy (tab/push/modal/wizard/gate/hero) + a route-spec resolver so every new route animates by default. |
| 0052 | DB-First Cold-Start Route Gate — Cache Memberships So the Splash Doesn't Wait on the Network | Proposed 2026-07-09 (agent-drafted from a cold-start investigation; operator-gated, not built) | Caches last-known memberships locally so cold start routes straight to Feed instead of waiting on a network `whoami`. |
| 0053 | Per-Hub Participation Roles (Viewer / Contributor / Co-owner) & Delegated Hub Management | Accepted 2026-07-10 (operator-directed in-session — "ADR 0053 is accepted") | Adds a per-hub role column (viewer/contributor/co_owner) so hub owners can delegate contribution + people-management; family-owner-not-auto-permitted preserved. **Built + shipped** (write-guard + migration `0018` live; the ADR body's own "not built" milestone note is stale — see `backlog/operator-inbox.md`). |
| 0054 | SWIP Bug Reporter in Debug Builds — Shake → Capture → Annotate → Review, Zero Release Footprint | Proposed 2026-07-10 (agent-drafted; "accept on merge" — merged to `main`; status text not yet flipped, see `backlog/operator-inbox.md` INB-32) | Shake-to-report bug capture wired into debug builds only via a privacy allowlist + sanitizer + mandatory leak test. |
| 0055 | SWIP Product Analytics in Debug Builds — Live PostHog EU, Count-Only, Zero Release Footprint | Proposed 2026-07-11 (agent-drafted; "accept on merge" — merged to `main`; status text not yet flipped, see `backlog/operator-inbox.md` INB-32) | Debug-only PostHog EU analytics, count-only 8-event slice, geoip disabled, never-identify; scoped to the operator's own dogfooded household. |
| 0056 | SloopLogging Integration — Leveled, Scrubbed, On-Device Logging | Proposed 2026-07-12 (agent-drafted; "accept on merge" — merged to `main`; status text not yet flipped, see `backlog/operator-inbox.md` INB-32) | A leveled `Log` front-door in `:client`, bound to SWIP `SloopLogging` in debug builds; PII scrubber runs ahead of every writer. |
| 0057 | SWIP Debug Inspector Plugin — Live Analytics Timeline in the Debug Drawer, Mask-by-Default | Proposed 2026-07-12 (agent-drafted; "accept on merge" — merged to `main`; status text not yet flipped, see `backlog/operator-inbox.md` INB-32) | A debug-drawer panel showing a live, mask-by-default analytics timeline from the SWIP capture engine. |
| 0058 | The API Reports Errors Through SWIP — Owned Stream + Sentry, Joined on a Fingerprint, Flushed Before the Function Freezes | Proposed 2026-07-14 (agent-drafted with the `feat/api-swip-errors` PR; accept on merge — **blocked on SWIP PR #68 publishing the npm packages**) | Hono middleware records through the `SloopErrors` facade and awaits a bounded flush in a `finally` (a Vercel container freezes at response time); errors land in PostHog + Sentry, joined on `swip.fingerprint`. Server-side consent is granted, with the argument written down. |
