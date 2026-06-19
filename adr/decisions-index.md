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
| 0013 | Client Architecture — KMP/CMP Shared UI + redux-kotlin 1.0.0-alpha1 | Accepted 2026-06-18. **Build pin (INB-11): default `0.6.2` stable** (alpha01 verified 1-day-old) — alpha01 is the opt-in upgrade; code calls `fieldState` not `fieldStateOf`. |
| 0014 | Private On-Device Trigger Engine (geo/time/activity) | Accepted 2026-06-18 (triggers=metadata; device matches locally; live position never leaves) |
| 0015 | End-to-End Encryption | **Proposed — scoped to M1** (INB-10: M0 = PLAINTEXT; live E2EE is an M1 option gated by ADR 0017) |
| 0016 | Two-Way Interactive Pull-Loop (reserved, bounded-now) | **Proposed** 2026-06-18 (additive; reasoning stays in the key-holding loop; reserved actions[]/intents) |
| 0017 | E2E Key-Authenticity + Deploy Trust-Root Boundary | **Proposed** 2026-06-18 (M1 security gate; from the 6-agent review — fake-key MITM + trust-root concentration) |
