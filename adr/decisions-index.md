# Decisions Index

Index of decision records. The ADRs themselves live in `adr/`. Once
accepted, an ADR is immutable — supersede it with a new ADR rather than
editing it.

| ADR | Title | Status |
|-----|-------|--------|
| 0000 | ADR Template | Template |
| 0001 | Repo Markdown as Source of Truth; Memory Systems as Working Memory | Accepted 2026-06-18 |
| 0002 | Execution & Tracking Model — Phase-Gated, Agent-Operated, Operator-Escalated | Accepted 2026-06-18 |
| 0003 | Planning-Loop Operating Model — Autonomous Waterfall Deepening with Confidence-Gated Decisions | Accepted 2026-06-18 |
| 0004 | Product Framing — Calm Family Briefing Surface, Content-API-Fed, Adults-Only MVP | Accepted 2026-06-18 (adults-only clause: supersession proposed in 0005) |
| 0005 | Minor Accounts (14+) Permitted at MVP, No Email Integration for Minor Profiles | Proposed 2026-06-18 (operator-gated; pending counsel) |
| 0006 | Event Hubs — Co-Equal Curated-Dossier Surface, App-Owned, Push-Curated | Accepted 2026-06-18 |
| 0007 | Prototype Scope — Operator-Driven Dumb Renderer | Accepted 2026-06-18 |
| 0008 | Design-First — Hi-Fi UI/UX Mockups Precede Deep Planning and Build | Accepted 2026-06-18 |
| 0009 | Design System — Material 3 Expressive, Adaptive, Compose | Accepted 2026-06-18 |
| 0010 | Auth & Family-Tenancy Architecture (Firebase Auth, M:N membership, RFC 8628 device grant) | **Superseded by 0011** (2026-06-18) |
| 0011 | Auth & Family-Tenancy Architecture (Hardened) | Accepted 2026-06-18 (supersedes 0010; post-5-agent-review) |
| 0012 | Agent-Operated Build & Deploy — Autonomy Boundaries | Accepted 2026-06-18 (operator-set: full prod autonomy + safety rails, budget cap, browser-after-login) |
| 0013 | Client Architecture — KMP/CMP Shared UI + redux-kotlin 1.0.0-alpha1 | Accepted 2026-06-18. **Build pin → `1.0.0-alpha01`** (INB-11 superseded 2026-06-19: operator owns/maintains reduxkotlin, so alpha-churn risk is moot; directive: latest APIs + `f(store.state)→UI`). All modules on Kotlin 2.3.20; `store.selectorState{}` (f(store.state)→UI) + devtools wired + verified on Pixel/emulator. |
| 0014 | Private On-Device Trigger Engine (geo/time/activity) | Accepted 2026-06-18 (triggers=metadata; device matches locally; live position never leaves) |
| 0015 | End-to-End Encryption | **Proposed — scoped to M1** (INB-10: M0 = PLAINTEXT; live E2EE is an M1 option gated by ADR 0017) |
| 0016 | Two-Way Interactive Pull-Loop (reserved, bounded-now) | **Proposed** 2026-06-18 (additive; reasoning stays in the key-holding loop; reserved actions[]/intents) |
| 0017 | E2E Key-Authenticity + Deploy Trust-Root Boundary | **Proposed** 2026-06-18 (M1 security gate; from the 6-agent review — fake-key MITM + trust-root concentration) |
| 0018 | API Host — TypeScript on Vercel | Accepted 2026-06-18 (INB-9; CLI/client stay Kotlin; types codegen from schema; Postgres via pooler) |
| 0019 | Client Observability & Tooling (devtools/snapshots/CLI) | **Accepted** 2026-06-19 (operator-directed) — redux-kotlin-devtools enhancer + in-app drawer LIVE (verified on-device) + Compose snapshots; golden-diff/remote/CLI remaining |
| 0020 | Offline-First Client Data + Freshness (DB-as-source-of-truth) | **Proposed** 2026-06-19 — network→DB(SQLDelight)→store→UI unidirectional; instant offline cold-start; foreground poll ~30–60s (push later); background WorkManager/BGTask. Build slice pending (shipped client is in-memory) |
| 0021 | Auth Pulled Into Active Build — S1–S6 Decomposition + Dev-Token Posture | **Accepted** 2026-06-19 (extends 0011; re-confirms its sequencing trigger). Build order S1→S3→S2→S4→(S5,S6); non-breaking household-token coexistence (cutover at S3); dev-token local-only. S1 build in progress. |
| 0022 | Typed Content Library, Detail View & the Fold Gesture | **Accepted** 2026-06-19 (operator; INB-15/16/17/18) — 6 typed content types (one renderer → Now/Hub/Detail), container-transform fold gesture, first-class provenance+privacy, **Dayfold** M3 theme. **D2 = extend `briefing_cards` in place** (unify→M1); phone mockups signed off (ADR 0008 cleared; adaptive queued); M0 ships all 6 types. Fills the gap the designs mis-cite as "ADR 0015". Epic: `planning/content-detail-epic.md` |
| 0023 | S2 Firebase Identity — Google + Apple, Phone-OTP deferred | **Accepted** 2026-06-20 (operator-directed) — extends 0011/0021, narrows the S2 provider set to **Google + Apple** (free tier, no Blaze). **Phone-OTP deferred** → removes the SMS spend ceiling + the SMS-fraud/SIM-swap surface; clears the S2 vendor/cost gate. ADR 0011 architecture intact (backend-minted tokens, app-driven link, per-device revoke, Apple `revokeToken`). S5/S6 build renders Google+Apple only (phone/OTP screens stay designed-not-built). |
| 0024 | Settings Privacy Posture — Tiering Boundary, Never-Syncs Rule, Telemetry Consent & E2EE Line | **Proposed** 2026-06-21 (operator-gated; guardrail-class #3/#4). Privacy posture of the settings surface only (storage shape = HIGH-confidence in `specs/account-and-settings-design.md` §6, not ADR). Never-syncs list (location-permission state, theme, a11y, devtools); telemetry emission gated on a device-local OFF default; E2EE line (only named places encrypted; rest plaintext). Composes with 0014/0015/0019/0020. Spec: `specs/account-and-settings-design.md` |
| 0025 | Auth Abuse-Control — Rate Limits & Lockout Constants | **Accepted** 2026-06-21 (operator-directed) — records the S3/S4 anti-abuse constants as a single source of truth: device authorize 10/600s·IP, token poll 600/600s·IP, owner-mint 20/600s, invite caps ≤10 active/≤20 pending, and the **invite-redeem lockout = 5 failed / 15 min → 15-min lock** (the `invitelocked` screen's cooldown). Composes with 0011/0021. |
| 0026 | Package & App-ID Naming — `com.sloopworks.*` Umbrella | **Accepted** 2026-06-21, revised post-4-agent review (operator-gated; branding/vendor convention) — `com.familyai.*` → store id **`com.sloopworks.dayfold`**; namespaces `…dayfold.android` (app) / `…dayfold.client` / `…dayfold.cli` / `…dayfold.schema`. Full sweep incl. env (`DAYFOLD_API`), npm (`@sloopworks/*`), CLI binary `dayfold`, client-id `dayfold-cli`, runtime paths (`~/.dayfold`, re-login no-migrate), bundled `api/index.js` redeploy, CI guard. **No Firebase/OAuth/iOS/web exist yet** → review corrected the "OAuth re-register" cost to a *future* trigger. **Timing gate: after `auth-account-delete-soft`, STRICTLY before S2 identity provisioning**; carry `cl-integrate` worktree + `auth-signout-account` across via merge. |
