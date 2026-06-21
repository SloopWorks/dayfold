# ADR 0024: Settings Privacy Posture — Tiering Boundary, Never-Syncs Rule, Telemetry Consent & E2EE Line

## Status

**Proposed** 2026-06-21 (operator-gated — ADR-class per CLAUDE.md hard
guardrail #3, customer-data handling, and #4, customer-relationship line).
Scoped to the **privacy posture** of the settings surface only; the plain
storage-shape choice (typed columns vs EAV) is a HIGH-confidence build
decision recorded in `specs/account-and-settings-design.md` §6, **not** in this
ADR. Composes with ADR 0020 (offline-first, DB-as-source-of-truth), ADR 0015
(E2EE content/routing split, M1), ADR 0014 (live location never leaves device),
ADR 0019 (telemetry/observability), ADR 0011/0023 (family tenancy, ownership,
Google+Apple). Detail: `specs/account-and-settings-design.md` §2, §7.

## Context

Dayfold settings resolve to three scopes — per-device (theme, dynamic-color,
location-permission state, accessibility overrides), per-user (notification
categories, quiet hours, locale/format, telemetry preference), per-family
(family name, timezone, briefing anchor, named places). *Where* each class is
stored is an implementation call. But several boundaries are **guardrail-class**
and must be fixed before the settings build slice so they cannot drift:

- **Data minimization (guardrail #4 — never become the family's system of
  record).** Storing device/OS-shaped or behavioral signal server-side leaks it
  for no functional gain.
- **Telemetry consent (guardrail #4 + ADR 0019).** A synced opt-in flag creates
  a consent-ordering hazard: a client could emit telemetry against a stale or
  not-yet-synced default before the user's real choice is known.
- **Location boundary (ADR 0014).** Live position never leaves the device — but
  the *permission state* and the *location-feature opt-in* also need an explicit
  home, or they default into the server by omission.
- **E2EE line (ADR 0015).** Which prefs, if any, are sensitive enough to encrypt
  must be drawn deliberately, not left to per-field guesswork.

A round-2 review correctly flagged that an ADR covering plain storage shape
would be process-heavy for a solo learning lab. This ADR therefore covers **only
the privacy posture**, which genuinely is guardrail-class.

## Decision

**1. Tiering boundary (privacy view).** Each setting lands in exactly one tier
by a fixed rule, applied in order: (a) describes this device/OS install and is
meaningless elsewhere → **device-local, never synced**; (b) one member's
preference → **server, user-scoped, synced**; (c) shared family truth → **server,
family-scoped, owner-gated, synced**; (d) override — content-grade sensitivity
(coordinates, free text a person typed) → **encrypted content path (ADR 0015)**,
not a plain settings table. **Tie-break: default to device-local** when
ambiguous (minimization).

**2. The never-syncs list (hard rule — never written to `/sync`, Postgres,
telemetry, or logs):**
   1. Live device location, activity, and derived geofence/region state (ADR
      0014 — absolute).
   2. **Location-permission state** (when-in-use / "Always" grant + the
      "Always"-upgrade-accepted flag).
   3. Theme + dynamic-color selection.
   4. Accessibility overrides (font scale, contrast, reduce-motion).
   5. DevTools/debug-drawer state and in-process action/state recording (ADR
      0019 — `DevToolsHub` is in-process, never emitted in release).

   Location-permission state stays device-local because the OS is its real
   source of truth (re-queried at runtime), it is a property of *this* install,
   and it has zero server benefit since geofencing is on-device (ADR 0014).
   ADR 0014 makes *live position* never-leave absolute; this ADR adds that the
   *permission posture* also does not sync.

**3. Telemetry consent ordering.** `telemetry_pref` (per-user, synced) is the
**preference of record**, default **OFF**. **Emission is gated on a device-local
flag that also defaults OFF.** The app **never emits telemetry on the strength
of a not-yet-synced or stale row**: opt-OUT takes effect locally immediately;
opt-IN requires a positive *local* read. No telemetry/snapshot leaves the device
unless the local gate is true (ADR 0019). Per-device values are render inputs
only and are never attached to telemetry events.

**4. E2EE line (ADR 0015).** Only **named places** cross into encrypted content
(they carry coordinates; ADR 0015 §1 already lists `places.lat/lng/label` as
ciphertext) — the settings posture defers to ADR 0015 for them. **All other
prefs stay plaintext** (routing-grade scalars/enums/times/names): encrypting
e.g. quiet-hours times buys near-zero privacy and forfeits legitimate future
server use (quiet-hours-aware push). Rule: **typed free text or coordinates →
encrypted; structured enums/scalars/times/names → plaintext.** **M0 caveat:**
the "encrypted at rest + family-scoped + never logged" property of place coords
(02-data-model.md / ADR 0014) is *at-rest* DB encryption; the **E2EE FCK-wrap is
M1, gated on ADR 0017** — places follow the M0 plaintext-to-server posture until
then.

**5. Export/delete coverage (guardrail #4).** Server-side `user_prefs` and
`family_settings` rows are covered by the member/family soft-delete cascade
(delete on member-leave / account-delete). Per-device prefs hold nothing the
server is responsible for; the obligation is satisfied by their never egressing.
They are lost on reinstall/new-device and re-default — intended, not a bug.

## Rationale

These four boundaries are the parts of the settings design where a wrong call
leaks customer data, breaks a consent obligation, or weakens the honest "we
can't read your *content*" claim — i.e. guardrail-class, warranting an immutable
record. Keeping device/OS/behavioral signal off the server is minimization by
construction rather than policy. Deferring named places to ADR 0015 avoids a
parallel encryption surface; refusing to encrypt trivial scalars keeps the
honest-content claim accurate without machinery that buys nothing.

**Alternatives considered / rejected:**
- *Sync everything (incl. theme/permission state) to the server* — leaks
  device + behavioral signal; convergence is wrong (theme shouldn't follow a
  member across devices); violates minimization.
- *Synced telemetry flag gates emission directly* — consent-ordering hazard
  (emit against stale/unsynced default); replaced by the device-local emission
  gate.
- *Encrypt all prefs under the FCK (ADR 0015)* — near-zero privacy gain on
  enums/scalars, forfeits legitimate server use, per-member key-wrap overhead on
  trivial values.
- *A full ADR also fixing storage shape (EAV vs columns)* — disproportionate
  for a solo learning lab; storage shape is HIGH-confidence agent-recorded in
  the spec, not immutable-decision-class.

## Consequences

Positive:
- Device/OS-shaped and sensitive-behavioral values never egress — minimization
  is structural.
- Telemetry cannot fire ahead of a real, locally-confirmed opt-in.
- ADR 0014/0015 boundaries and export/delete are honored by construction.
- Scope kept tight: one guardrail-class ADR, no process weight on plain storage.

Negative:
- Per-device prefs (theme, a11y, permission state) are lost on reinstall/new
  device and re-default — accepted as intended.
- A synced location-feature opt-in plus a missing OS grant needs a cross-tier
  "blocked — fix permission" reconciliation in the UI (specified in the spec).
- Named places inherit ADR 0015's M1 key-distribution dependency; until then
  they follow the M0 plaintext posture.

## Revisit Trigger

A server-side feature needs a value currently on the never-syncs list (re-examine
that boundary explicitly); ADR 0015/0017 land and change the plaintext/ciphertext
split for any pref; a regulatory change alters telemetry-consent or data-export
obligations; or a setting class appears that does not fit cleanly in one tier.
