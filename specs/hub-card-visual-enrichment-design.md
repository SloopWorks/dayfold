# Hub & Card Visual Enrichment — Design (reality-anchored)

**Date:** 2026-06-26
**Status:** Spec drafted; **blocked on hi-fi mockup (ADR 0008) + Proposed ADR**
before build. Branch: `claude/hub-card-enrichment` off `origin/main`.
**Surfaces:** content schema + API + Postgres, CLI, Compose Multiplatform client.
**Authoritative refs:** `specs/domain-model/schemas/content.schema.json`,
`specs/event-hubs-design.md`, `adr/0006` (hubs), `adr/0022` (typed content),
`adr/0029` (scope grants), `adr/0030` (visibility), `adr/0009` (design system),
`adr/0008` (design-first).

## Problem

Hubs and briefing cards are visually flat (title + chips + emoji glyphs, no
imagery). Add **hero images, thumbnails, a named icon, and an accent color** so
a family grasps a Hub's purpose at a glance — e.g. a "starting-college" Hub
shows the university logo/mascot as a hero in both the Hub list row and the Hub
detail header. Cards/blocks get lightweight cues (icon + accent, optional
thumbnail/avatar).

This is the **M1 "images on" work** that `event-hubs-design.md` deferred from M0
("images off at M0; Coil3 + allowlist in M1").

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Who fills enrichment | **Manual** — Claude skill sets explicit values via CLI PUT. API/CLI are dumb stores. | No auto-resolution; intelligence lives in the skill. |
| Field source of truth | **`content.schema.json` → `npm run codegen`** emits Zod (API) + Kotlin (CLI/client). Define fields once. | Repo's existing single-source pattern; URL/hex validation free from Zod. |
| Write semantics | **Full-document PUT upsert** (existing). Clearing a field = omit it from the PUT body. | API is already idempotent upsert; **no PATCH / tri-state / `--clear` needed**. |
| Storage | **`media jsonb` column** on `hubs` + `briefing_cards`; block/contact enrichment rides existing `payload jsonb`. One migration `0012`. | Matches repo's payload-jsonb convention; backward-compatible (NULL = today's look). |
| AuthZ | **Reuse existing** `authorizeTenant` + `requireScope` (ADR 0029) + visibility (ADR 0030) + server-stamped provenance. | Enrichment rides the already-authorized PUT path; no new authz surface. |
| Errors | **Reuse existing** RFC 9457 `problem()` / 422 + `issues[]`. | Already in `app.ts`. |
| Icons | **Named string, server-validated against a small curated set** (~12–20). Client maps name → bundled/drawn glyph. | Client deliberately ships **no icon font** (KMP friction). Don't import the full Material Symbols catalog. Grow the set over time. |
| Color | **Single accent hex.** MVP applies it to **decorative surfaces only** (border / chip / scrim tint); text keeps `DayfoldTheme` colors. | Sidesteps `material-color-utilities` KMP friction + contrast computation, and satisfies "color is decorative, never sole meaning" (a11y). Full HCT tonal = later. |
| Image variants | `heroUrl` + optional `thumbnailUrl` (falls back to hero, client-side); `*Fit: cover\|contain`; `*Alt` for a11y. | One field can't crop well for both list + header; logos need `contain`+padding, photos need `cover`. |
| URL safety | **https + host allowlist + hardened parser**, shipped as a shared generated constant (mirrors `ALLOWED_SCHEMES` in `CardRender.kt`). | MVP/prototype; images eventually self-hosted (Phase 2). |
| Constraints discovery | **Defer the runtime endpoint.** Skill reads the allowed-host constant + curated icon list from generated/bundled output + docs. | YAGNI at MVP (skill is sole author). |
| Concurrency | **Defer ETag/If-Match.** `version` column already bumps; skill is the only writer. | No concurrent-writer problem at MVP. |

## Data model (additive, nullable → backward compatible)

Add to `content.schema.json` (`additionalProperties:false` is enforced repo-wide,
so fields must be declared):

```
Hub.media           ?object  { heroUrl?, thumbnailUrl?, heroFit?(cover|contain),
                                imageAlt?, icon?, accentColor?(#RRGGBB) }
BriefingCard.media  ?object  { icon?, accentColor?, thumbnailUrl?, imageAlt?,
                                imageFit?(cover|contain) }
Block.payload (link|document).thumbnailUrl? , .thumbnailAlt?
Block.payload (contact).avatarUrl? , .accentColor?
```

- All optional. NULL → current appearance (no regression).
- Wire = `media` sub-object; DB = `media jsonb` (Hub, card) / existing `payload`
  (blocks). Decouples wire shape from storage; Phase-2 variants migrate underneath.

### Validation (generated Zod + Kotlin from schema; server authoritative)
- `accentColor` ∈ `^#[0-9A-Fa-f]{6}$` (lowercased on write).
- `*Fit` ∈ {`cover`,`contain`}.
- `icon` ∈ curated set (generated constant).
- **URLs — hardened (shared rule, same parser server + client + Coil):**
  https only (pinned at write → blocks `data:`/`javascript:`/`http`); reject
  userinfo `@`; normalize host lowercase + punycode `toASCII` + strip trailing
  dot; port empty/443; **exact registrable-host match** vs allowlist (not
  `endsWith`); length-cap. Reject `image/svg+xml` at render (SVG = XSS).
- Errors: existing RFC 9457; per-field issue path (e.g. `["media","heroUrl"]`).

## Migration `0013_visual_enrichment.sql`
```sql
ALTER TABLE hubs           ADD COLUMN media jsonb;
ALTER TABLE briefing_cards ADD COLUMN media jsonb;
ALTER TABLE hubs           ADD CONSTRAINT hubs_media_chk
  CHECK (media IS NULL OR jsonb_typeof(media)='object');
ALTER TABLE briefing_cards ADD CONSTRAINT cards_media_chk
  CHECK (media IS NULL OR jsonb_typeof(media)='object');
```
Expand-only, additive, safe to deploy anytime. Blocks need no DDL (`payload jsonb`).

## CLI (`apps/cli`)
- No new command. Enrichment flows through existing `dayfold push <id> <file.json> --hub|--block|--section` (cards default). New fields validated by the regenerated Kotlin schema in `Validate.kt`.
- Update `templates/hub.json`, `templates/block.json` with `media` examples.
- Bundle the allowed-host constant + curated icon list (from codegen) so the skill/operator can see valid values; document in CLI help + the skill doc.

## Mobile (`apps/client`, Compose Multiplatform)
- **Add Coil3** (`coil-compose` KMP) — the lib `event-hubs-design.md` already names.
- **`HubScreens.kt`**
  - `HubRow`: fixed-aspect leading slot (e.g. 1:1 56dp) → `thumbnailUrl` → `heroUrl` → **designed icon+accent tile**. Placeholder/error/no-image all render the same tile (consistent rhythm, invisible failures).
  - `HubDetailScreen`: collapsing top-app-bar hero, height-capped (`min(16:9,240dp)`), edge-to-edge under status bar (light icons), bottom gradient scrim strong enough for white-on-busy text; collapses to accent-tinted bar.
  - `HubBlockCard`: `link`/`document` → optional `thumbnailUrl` (fixed-aspect, Coil); `contact` → `avatarUrl` overrides initials avatar.
- **`FeedScreen.kt`/`CardRender.kt`**: card `media.icon` + accent on the kind chip; optional `thumbnailUrl` leading.
- **Fit:** `cover` = `ContentScale.Crop`; `contain` = `ContentScale.Fit` on accent-tinted bg + 8–12dp padding (logos/mascots).
- **Coil:** `crossfade(true)`, target size set (downsample → no OOM bomb), `diskCacheKey` from ETag (or versioned URLs) to dodge stale same-URL cache.
- **Color (MVP):** accent on border/chip/scrim only; never on body text. Honors a11y (WCAG 1.4.1) and avoids HCT/material-color-utilities friction.
- **Icons:** name → curated mapped glyph; unknown name → accent tile.
- **a11y:** images get `contentDescription` from `*Alt` (or derived from title); icons `null` (decorative) when redundant with a label. RTL mirrors leading slot; dynamic-type bounds maxLines; reduced-motion gates parallax.
- **SQLDelight:** sync carries `media` through existing jsonb payloads; cache columns as needed.

### Fallback ladder (every surface)
`image → designed icon+accent tile → plain default`. Never blank, never broken-image. Bounded decode (no infinite retry on a bad host).

## Phasing
- **Phase 1 (MVP):** schema+codegen, migration 0012, CLI templates, Coil + render, host-allowlist constant, curated icon set, a11y alt, decorative accent. Public image URLs on allowlisted hosts (Wikimedia, official logo CDNs). Accept third-party-host IP/usage exposure as explicitly temporary.
- **Phase 2 (self-host):** object storage + CDN; `assets` table (`id, owner_type, owner_id, role, url, width, height, mime, variant_of`) for responsive/format variants; `dayfold asset upload` (server opaque key, magic-byte sniff, byte+pixel caps, transcode/strip-meta, private bucket, CDN-only, content-addressed URLs); optional ingest-proxy **only with full SSRF guards** (resolve-then-pin DNS, IP denylist incl. `169.254.169.254`/RFC1918/IPv6-link-local, re-validate redirects, IMDSv2, egress deny). Self-host kills third-party IP leak. Full HCT tonal color + larger icon set candidates here.

## Security (for the dedicated security review)
- AuthZ already enforced (ADR 0029/0030) — enrichment rides authorized PUT.
- Hardened URL validation closes allowlist evasion (userinfo, punycode, port, suffix-match, parser differential); https pinned at write blocks `data:`/`javascript:`.
- Reject SVG; the moment any web/email surface renders these fields, apply the same validation + contextual output-encoding (treat `icon`/`color` as typed lookups, never raw interpolation).
- Image content: Coil downsample (bomb defense); P2 server magic-byte sniff + caps.
- Supply chain: pin + hash-verify Coil + curated icon assets; no wildcard allowlist entries; allowlist config access-controlled + audited.
- Trademark/correctness: allowlist constrains host, not rights/correctness; **skill must surface its chosen image for operator confirmation before commit**; logo-usage rights are the operator's responsibility.

## Claude skill workflow ("the brain")
Reads the bundled allowed-host + curated-icon constants (no guessing) → sources an image from an allowed host (e.g. Wikimedia file URLs) → picks icon + accent → **surfaces hero+icon+color for operator confirmation** (trademark/wrong-entity guard) → `dayfold push` → reads back via pull before edits.

## Observability & testing
- **Telemetry:** server count of validation rejects by host (which host to add); client image-load-failure → fallback-tile event (URL rot).
- **Tests:** Vitest API (`apps/api/test/`) — media round-trip on PUT, bad hex/URL → 422, sync carries media; Kotlin `kotlin-test` — `Validate.kt` URL/hex/icon accept-reject (incl. evasion vectors), Compose render per fallback rung × light/dark × RTL.

## Governance gates (dayfold rules — block BUILD, not this spec)
1. **ADR 0008 design-first** — hi-fi mockup in `designs/hub-card-enrichment/` + operator signoff before build. → see `designs/DESIGN-BRIEF-hub-card-enrichment.md`.
2. **Proposed ADR** — external-image-URL privacy/allowlist posture (customer-data-handling class) accepted by operator.
3. Branch from latest `main` (done: `claude/hub-card-enrichment`).
4. Two-round adversarial review (round 1 correctness + round 2 simplification) — done during brainstorming + reality-anchoring.

## Out of scope
Auto-resolution of entity→logo; multi-color palettes / full HCT at MVP; per-card hero parity with Hubs; CLI image editing; per-locale assets.

## Open items
- ~~Final curated icon list + render mechanism.~~ **RESOLVED:** 18 curated NAMES
  (school…list) → `material-icons-extended` ImageVectors via `CuratedIcons` (no icon
  font); unknown name → fallback tile. Binary tree-shaken on release.
- ~~Confirm Coil3 KMP version + engine parity.~~ **RESOLVED:** `coil3:3.2.0` +
  `coil-network-ktor3` over the project's ktor 3.5.0 (cio desktop / okhttp android /
  darwin iOS); `SingletonImageLoader` wired in `CoilSetup`. Desktop + Android compile;
  iOS target inherits commonMain (build pending the Xcode shell, as ever).
- Phase-2 `assets` table final shape + srcset strategy (deferred).

### Implementation deferrals (Phase-1 build, 2026-06-26)
- **Block media server round-trip** waits on **ADR 0035** (generated `Block.payload`
  is `[z.any()×7]` → any structured block PUT 422s today; live content is body_md-only).
  Block media is enforced + rendered at the CLI + client now; the API route wiring is
  forward-compatible.
- **Typed-card media** (`TypedCardItem`/`BaseCard`) not yet wired — only the legacy
  `CardItem` path carries card media. Follow-up.
- **Hero** = height-capped banner with scrim (not the full scroll-collapse
  `LargeTopAppBar` animation). Follow-up.
- **ETag `diskCacheKey`** not wired (no ETag/versioned URLs at MVP) — Coil default cache.
- **RTL** relies on Compose's automatic `Row` mirroring; no dedicated RTL snapshot.
