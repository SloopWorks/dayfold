# ADR 0028: Geocoding Strategy & Location-Privacy Tiers

## Status

**Accepted 2026-06-22 (operator-directed)** — the load-bearing principle is
locked: **geocoding is never server-side; the server stays zero-knowledge**
(§1). The M0 author-side tiers (T1 author-supplied / T2 CLI offline) stand. The
**deferred tiers (T3/T4/T5) and any third-party provider choice remain
deferred** and are to be **revisited once CLI encoding (the ADR 0015
encrypt-before-upload path) lands and we are post-prototype/MVP** — not before.
Claim wording stays `[pending-counsel]` (does not block this acceptance).
Immutable now — supersede, don't edit.

Refines/operationalizes ADR 0014 (the "how is the place coordinate produced?"
question 0014 left implicit); composes with ADR 0015 (E2EE column split) and
ADR 0024 (settings privacy posture / E2EE line). Spike evidence:
`docs/superpowers/specs/2026-06-21-cl-9-map-render-spike.md` + session research
2026-06-22.

## Context

The platform needs a **lat/lng coordinate** on authored "place" content for two
consumers:
1. **On-device geofencing** (ADR 0014's deferred geo-trigger milestone) — the
   OS geofencing APIs (Android `GeofencingClient`, iOS Core Location region
   monitoring) take a **lat/lng + radius, not an address** `[fact:developer.android.com,
   developer.apple.com]`, so a coordinate is *functionally required*.
2. Optional static map render (CL-9b) — also needs a center coordinate.

ADR 0014 already establishes that place coordinates are **authored content**
(go to our server, encrypted at rest, family-scoped) distinct from the device's
**live position** (never leaves the device), that matching is **on-device**,
and that **Claude reasons / sets the trigger metadata**. What it did *not*
settle: **how an address becomes a coordinate** — and that operation
(geocoding) is where a third party can enter, colliding with the
privacy-by-architecture wedge and with E2EE.

Findings that constrain the decision (session research 2026-06-21/22):

- **LLM-emitted coordinates are unfit for geofencing.** Published LLM-geocoding
  benchmarks put error at ~hundreds of meters, unbounded for obscure local
  addresses (well above a 100–200 m geofence). `[estimate — multiple 2025–26
  arXiv LLM-geocoding evaluations; directional, not pinned to one ID]` The
  author/loop must emit the *address string*; an **authoritative geocoder**
  produces the coordinate.
- **Storage permission eliminates the big vendors.** The coordinate must be
  **stored permanently** (it feeds a geofence forever). **Google** Maps
  Platform ToS prohibits storing the lat/lng (temporary cache only, tied to use
  with a Google map) `[fact:cloud.google.com/maps-platform/terms]`; **HERE** and
  **Mapbox** default terms similarly restrict permanent storage `[fact:per-
  provider ToS]`. Viable storage-permitting providers are OSM/ODbL-based:
  **OpenCage** (store forever, even post-cancellation) and **Geoapify** (store
  indefinitely + attribution).
- **E2EE would foreclose server-side geocoding.** Under ADR 0015 (Proposed,
  M1; M0 is plaintext), the column split makes `places.lat/lng/label` and
  `payload` **ciphertext the server never reads** — so at M1 a zero-knowledge
  server could not geocode an address it cannot see. To stay forward-compatible
  with that, geocoding must be **author-side, before encryption** — never on
  our server — from M0 onward. *(Note: ADR 0024's row summarizes the E2EE line
  as "only named places encrypted; rest plaintext," which is narrower than
  0015 §1's ciphertext set; 0028 follows **0015 §1**. The 0024-vs-0015 wording
  gap is upstream — flagged, not resolved here.)*
- **OS "on-device" geocoders are off-device network calls.** Android
  `Geocoder` *"requires a backend service…obtained by means of a network
  lookup"* (→ Google on GMS devices — our auth IdP); iOS `CLGeocoder`
  *"geocoders rely on a network service"* (→ Apple, rate-limited).
  `[fact:AOSP/Apple docs]` They send the address to the platform vendor → **no
  structural "never leaves device" guarantee.**
- **Offline street-address forward geocoding is infeasible on a phone**
  (OSM/Nominatim-scale data, hundreds of GB) but **feasible on the CLI** (a
  capable machine can run/point at a local geocoder + a regional OSM extract).
  `[fact:Nominatim reqs / offline-geocoder]`
- **On-device GPS-capture needs no geocoder at all.** Capturing the device's
  own current position ("save this place while I'm here") yields a coordinate
  that **never leaves the device** and sends **no address anywhere** — fully
  structural, limited to places physically visited.

## Decision (proposed)

### 1. Geocoding is NEVER server-side. Coordinates are produced author-side.
The server is and stays a zero-knowledge store for place data (forward-
compatible with ADR 0015). The coordinate is produced before it reaches our
server. This holds in plaintext-M0 and E2EE-M1 identically — the only M1
addition is the generic encrypt-before-upload step (no geo-specific rework).

### 2. The coordinate comes from an authoritative source, never the LLM.
Claude / the authoring loop emits the **address string**; a real geocoder (or a
device GPS fix, or a human-supplied coord) produces the lat/lng. LLM-guessed
coordinates are not accepted as geofence inputs.

**Floor / graceful degradation:** if a place has **no coordinate and no
resolvable address** and was never physically visited (e.g. a named venue the
loop knows by name only), the card **degrades to no geo-trigger** (renders
fine; time-only or un-geofenced) and surfaces the missing-coordinate state. It
**must not** fall back to an LLM-guessed coordinate. A geofence is added only
when an authoritative coordinate exists.

### 3. Location-privacy tiers (ordered strongest→weakest claim; default to the strongest available).

**M0-live tiers:**

| # | Tier | What leaves, to whom | Honest claim |
|---|---|---|---|
| **T1** | **Author-supplied lat/lng** (CLI/API take a coord in; or a resolved `place_ref`) | nothing geocoded | structural — no geocoding occurs |
| **T2** | **CLI-side offline geocoder** (local Photon/Nominatim + regional extract) | nothing — resolved on the author's machine | **structural: "your address never leaves your device"** |

T1/T2 are the **M0 default**, and the only tiers built now.

**Deferred tiers (not built; recorded so the model is complete):**
- **T3 — On-phone GPS-capture** ("save this place while I'm here"): structural
  (uses the device's own position, ADR 0014). **Reserved for M1** phone
  authoring (ADR 0016); design the picker UX then.
- **T4/T5 — third-party geocoding** (only if T1/T2 ever prove insufficient):
  **BYO-key (T4) is strongly preferred** over a first-party service — the
  customer's provider sees the address under the customer's terms; we stay out
  of the loop ("we never see your addresses"). A **first-party opt-in service
  (T5)** is a last resort only, **never default**, and carries a policy-claim +
  service-walling + counsel burden (sketched in the revisit note). If any third
  party is used: provider = **OpenCage or Geoapify, never Google/HERE/Mapbox-
  default** (storage-permission, §Context); geocode **once per place**, cache,
  reference via **`place_ref`** (ADR 0014's reusable family-scoped `places`) —
  minimizing calls/cost/disclosure and respecting the **iOS 20-region geofence
  cap** (Android 100) by geofencing the reusable place-set, not per-card.

**T5 sees the address — a *different* promise than E2EE.** A first-party
geocoding service would see the plaintext **address** (home/school), which
ADR 0014 calls the most sensitive family content — even though it never sees
the encrypted *content payload*. So "we can't read your content" (ADR 0015)
and "we never see your addresses" (T1–T4) are **two distinct promises**: T5
preserves the first but **breaks the second**. The operator must sign off on
that distinction knowingly before T5 is ever built.

### 4. Claim discipline: structural over policy.
Privacy-by-architecture is the wedge (validation round 1). T1–T4 need **no
"we don't sell/share" promise** — that is their advantage: *a claim you never
have to make is one you can't break*. Policy claims (T5) are
counsel-gated and a strategic last resort.

## Honest claim (per ADR 0014/0015 honesty rule — no claim without an enforcing boundary)

- **T1/T2/T3:** "Your address never leaves your device." (Structurally true —
  no network geocoding.)
- **T4:** "We never see your addresses; you bring your own geocoder." (True —
  our server/infra is out of the loop; the customer's provider sees it under
  the customer's terms.)
- **T5:** `[pending-counsel]` — at most "geocoding is not tied to your account
  and not stored," and only if the service is built per the revisit-note spec.
- **Never claim** "private/on-device" for the OS geocoders (Android `Geocoder`
  / iOS `CLGeocoder`) — they transmit the address to Google/Apple.
- Metadata still leaks under E2EE (ADR 0015): the server learns **place count**,
  family, dates, ciphertext sizes — not the places. Chip copy reflects this.

## Consequences

**Positive:** the strongest privacy claims (T1–T4) are structural and
E2EE-forward by construction; no server-side geocoding to rip out at M1; aligns
with the constitution ("render intelligence produced elsewhere; never the
system of record") via BYO/integration; reinforces the only defensible wedge.

**Negative / costs:** T1 (author-supplied) has manual friction; T2 (CLI offline
geocoder) is real author-side setup (regional extract, a local geocoder
process); T4 (BYO) has config friction; the convenience path (T5) is the one
that carries policy-claim + walling + counsel burden. Accuracy depends on the
chosen geocoder. The content `geo` payload must gain a `place_ref` (it has
`lat/lng` but no place_ref today; only the *trigger* geo does).

## Confidence & gating

- **Operator-gated (this ADR):** the tier set + defaults (scope/posture),
  vendor choice (OpenCage/Geoapify), whether to ever build T5.
- **Counsel-gated:** all marketing/UX **claim wording**, especially any T5
  "not sold/shared/not linked" language (FTC + state privacy laws police "we
  don't sell" against real flows). Marked `[pending-counsel]`.
- **Not agent-decided.** Drafted for accept/reject.

## Revisit triggers

- **Scheduled (operator, 2026-06-22):** revisit the full tier/provider model
  **once CLI encoding (ADR 0015 encrypt-before-upload) is built + post-
  prototype/MVP.** Until then: T1/T2 author-side only, never server-side. This
  acceptance locks only the never-server-side / zero-knowledge principle.
- If zero-third-party becomes a hard wedge requirement → promote **self-hosted
  geocoder** (CLI T2 / a self-run Photon) from option to default; reassess T4/T5.
- If phone authoring (ADR 0016 two-way) ships → T3 GPS-capture becomes live;
  design the place-picker UX then.
- If a family exceeds the iOS 20-region cap in practice → revisit the
  nearest-N/soonest-N geofence selection policy (ADR 0014 §5).
- **If T5 (first-party opt-in geocoding service) is ever proposed** → it must be
  engineered toward structural, not promised: a service **walled from the
  content store** (so our content server stays zero-knowledge),
  **unauthenticated / no account token**, **no logs**, **not identity-linked**,
  **nothing retained**; explicit opt-in, off by default; claim wording
  `[pending-counsel]`. Even so it sees the plaintext address (see §3) — a new
  ADR + operator + counsel sign-off, not a tweak under this one.

## If accepted — downstream changes

- **Schema (`content.schema.json`):** content `geo` payload gains `place_ref`;
  formalize a family-scoped `places` record (ADR 0014) carrying the geocoded
  coord, marked `"x-e2e":"ciphertext"` (ADR 0015) for the M1 column split.
- **CLI (`dayfold`):** accept author-supplied lat/lng (T1) and add an optional
  offline-geocode path (T2); never call our server to geocode.
- **Client (M1):** GPS-capture place authoring (T3) when ADR 0016 lands.
- **Specs:** record the tier model in the location/privacy spec; add the two
  counsel items to `context/open-questions.md`.
