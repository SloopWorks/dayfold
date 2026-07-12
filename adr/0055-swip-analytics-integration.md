# ADR 0055: SWIP Product Analytics in Debug Builds — Live PostHog EU, Count-Only, Zero Release Footprint

## Status

**Proposed** 2026-07-11 (agent-drafted with the `swip-analytics-integration`
PR; accept on merge). Composes with ADR 0054 (bug reporter — shares the
`:swip-wiring` module + the `createAppStore(extraEnhancer)` slot), ADR 0015
(vendor accounts / restricted-scope + LLM data handling), ADR 0014 (on-device
location, content-blind), ADR 0019 (in-app devtools).

## Context

SloopWorks provisioned the ADR-0015 vendor account (a PostHog EU project).
Dayfold instruments product analytics via the published SWIP KMP SDK using
the swip-rk redux mapper-table pattern (`docs/superpowers/specs/2026-07-11-
swip-analytics-integration-design.md`, the design this ADR realizes). SWIP
companion: `works.sloop.swip:{swip-core,schema-dayfold}:0.1.2`.

## Decision

1. **Live PostHog EU transport** (`https://eu.i.posthog.com`, `phc_` project
   key from Infisical `/dayfold` → **debug-only** BuildConfig field).
   **Analytics-only** — no Sentry keys, so the SWIP errors/config/telemetry
   facades are wired as NoOp (`swipTimingEnhancer` omitted — it targets the
   NoOp telemetry facade).
2. **Variant scope = DEBUG / internal-only.** Analytics + keys ship in
   debug/dogfood builds only; the public **release APK carries zero
   swip-analytics bytes** (inert `src/release` glue, verified `javap` shows
   no `works/sloop/swip` refs). Widening to release for real users is a
   **future ADR** gated on a privacy-policy disclosure + a consent surface
   wired to `CollectionMode` (hard guardrail #3/#4).
3. **Count-only slice 1 — 8 events:** `account_signed_in`, `signed_out`,
   `family_created`, `invite_redeemed`, `invite_rejected` (single `reason`
   closed-enum field), `hub_opened`, `card_opened`, `sync_failed` — plus the
   three free swip-lifecycle events (`app_foregrounded`/`app_backgrounded`/
   `screen_view`, route-driven). **No identifiers, no free-text, no PII** in
   any event: session, family name, sync error message, hub/card ids are all
   dropped at the mapper. `checklist_item_toggled` **deferred** — the toggle
   runs through a mutation-outbox effect (`HubEngine.toggleItem`→
   `ContentStore.enqueueBlockToggle`), not a redux action, so it isn't
   mappable without a new thin action (a follow-up).
4. **Privacy hardening:** PostHog server-side **geoip disabled at the
   transport** (`$geoip_disable:true` + `$ip:"0.0.0.0"` in every event's
   properties — a SWIP-side change in swip-core 0.1.2) so device location
   never leaks. `distinct_id` stays the **opaque SWIP installation id**;
   Dayfold **never calls `identify()`** with `userId`/email (no
   behavior-to-account linkage). `screen_view` name = the id-free `Route`
   enum name.
5. **Boundary:** the mapper table (the tracking spec) + `NoOpErrors` live in
   `:swip-wiring`; `Swip.init` + PostHog transport + lifecycle live in the
   androidApp **debug glue**; **`:client` imports no `works.sloop.swip`**;
   reducers never call SWIP (purity/replay).
6. **Scope:** ratified **only for the operator's dogfooded household** (the
   sole data subject at slice 1).

## Rationale

- Reuses ADR 0054's proven debug-only, inert-release-mirror idiom rather than
  inventing a second wiring pattern — analytics and the bug recorder share
  one `extraEnhancer` slot, composed together in the debug glue.
- The mapper table doubling as the tracking spec (unmapped actions emit
  nothing) keeps the privacy floor structural rather than review-dependent:
  what's tracked is exactly what's registered.
- Count-only slice 1 (no hub/card ids) is the calmer floor given no consent
  surface exists yet — id-bearing funnels are an explicit, additive,
  operator-approved follow-up, not a default.
- Alternatives rejected: shipping analytics in release now (opens guardrail
  #4 with no disclosure surface); sending opaque hub/card ids (count-only is
  the calmer slice-1 floor); a hand-written event type (SWIP codegen is the
  source of truth); leaving geoip on (location leak).

## Consequences

Positive:
- Product analytics flow to PostHog in dogfood builds; behavioral data to a
  third-party processor (guardrail #3) is operator-ratified for the
  operator's own household.
- The SWIP ADR-0019 Kotlin event-codegen gap was closed (event types now
  emitted for manifest products).

Negative:
- A follow-up ADR + consent/disclosure surface is required before measuring
  any other user.
- Consent→`CollectionMode` UI wiring and on-device smoke are deferred
  follow-ups.

## Revisit Trigger

Flipping analytics into the release APK for real (non-operator) users,
adding `checklist_item_toggled` (needs a new thin dispatched action), or
widening the event slice to carry hub/card ids — each requires revisiting
the consent/disclosure posture or the count-only floor.
