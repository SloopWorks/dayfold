# Tappable links end-to-end — design

**Date:** 2026-06-27
**Branch:** `claude/link-improvements`
**Status:** Design — pending operator review

## Goal

Users tap content they expect to be actionable — URLs, phone numbers, email
addresses (and addresses, via structured payloads) — and the right app opens
(browser, dialer, mail, maps). End-to-end: authoring toolchain → all clients.

## Problem (what the review found)

Most link infrastructure already exists, but with two real defects and one
missing capability:

1. **Existing cross-platform bug.** Explicit `[label](url)` links already
   render as `LinkAnnotation.Url`, but tapping one routes to Compose's
   **default** `UriHandler`, *not* the vetted `PlatformActions`. Result today,
   for links that already ship:
   - Android: `tel:` / `geo:` with no handler app → uncaught
     `ActivityNotFoundException` → **activity crash** (the vetted path wraps
     `startActivity` in `runCatching`; the inline path does not).
   - iOS: `geo:` is not an iOS scheme → **silent no-op**.
   - Desktop: `tel:` / `sms:` / `geo:` ≈ dead.
2. **Missing capability.** Bare entities the author did not wrap — raw phone
   `555-123-4567`, bare email `a@b.com` — are not linkified. Only bare
   `https://…` autolinks today.
3. Addresses have a structured home already (`geo` card payload / `location`
   block) and are **out of scope for prose detection** (see Decisions).

## Decisions (operator-confirmed)

- **D1. Author-side detection.** Bare-entity detection/linkification happens in
  the authoring toolchain (CLI / Claude skill), never the client at render and
  never the server. Matches CLAUDE.md "clients render intelligence produced
  elsewhere."
- **D2. Structured-only addresses.** Prose linkifies phone / email / URL only.
  Addresses flow through the existing structured `geo` payload / `location`
  block; **no regex address scanning** (false-positive and wrong-pin risk).
- **D3. Shared `linkrules` module.** One pure-Kotlin source of truth for the
  scheme allowlist + sanitizers + the linkifier, reused by client render and
  CLI authoring so vetting cannot drift between the side that *produces* links
  and the side that *opens* them.
- **D4. Two PRs, handoff-fix first.** PR1 fixes the inline-link handoff bug
  (independently valuable); PR2 adds the linkifier on top of PR1's safe open
  path.
- **D5. No server-side content validation — ever.** The server is content-blind
  by design: ADR 0015 encrypts `body_md` at M1 (server stores ciphertext).
  Link-scheme safety is enforced **render-side** (client allowlist) and
  **author-side** (linkifier emits only allowlisted schemes). The client
  render-time allowlist is THE security boundary and is guarded by a test. See
  memory `server-content-blind-e2ee`.

## Security model (content-blind)

```
author-side (CLI/skill)        client render-side            server
─────────────────────          ──────────────────            ──────
linkify() emits ONLY    ──>     ALLOWED_SCHEMES gate   ──>    stores opaque
allowlisted schemes             at render; disallowed         body_md (cipher-
(tel/mailto/https/...)          → plain text                  text at M1).
                                = THE security boundary       NEVER parses it.
```

The non-CLI direct-API push (curl/script) that stores `[x](javascript:…)` is
rendered inert by the client allowlist — disallowed schemes degrade to plain
text and never become a `LinkAnnotation`. There is no server check because the
server cannot read content (D5). The allowlist's primacy is locked by a render
test asserting `javascript:` / `data:` never produce a link.

---

## PR1 — inline-link handoff fix

**Outcome:** tapping any inline link in rendered card/block text routes through
the vetted `PlatformActions` (crash-safe `runCatching`, scheme mapping,
per-platform fallbacks), identical to the action-button path.

**Approach:** provide a custom `LocalUriHandler` (CompositionLocal) around the
feed / hub / detail subtree. Its `openUri(uri)` maps the scheme to the existing
`CardAction` routing → `PlatformActions` actual. Inline taps and button taps
then share one guarded open path.

- iOS `geo:` gets a real home here (map to Apple Maps / `maps:` fallback in the
  iOS actual) instead of a silent no-op.
- Android no longer crashes on a missing handler (inherits `runCatching`).
- No change to what renders — only where the tap goes.

**Affected (verify exact paths in-worktree; main has moved):**
`client/.../cards/PlatformActions.kt` (+ android/ios/desktop actuals),
`FeedApp.kt` (`routeCardAction`), the screens hosting `Text(AnnotatedString)`
(`FeedScreen.kt`, `HubScreens.kt`, `cards/DetailScreen.kt`).

**Tests:** a tapped `tel:` with no handler does not crash (Android actual
`runCatching` covered); disallowed scheme never reaches `PlatformActions`;
inline link routes to the same `CardAction` a button would.

**Verification:** run the app per `processes/agent-dev-loop.md`; tap a `tel:`
and a `geo:` inline link on Android + desktop; confirm no crash and correct
handoff (snapshot / action log).

---

## PR2 — author-side linkifier + `linkrules` module

### `linkrules` module (D3)

- **KMP multiplatform**, **stdlib-only** — no Compose, no kotlinx, no `java.*`
  (must compile in client `commonMain` across android / desktop-jvm /
  iosArm64 / iosSimulatorArm64, AND as plain JVM source in the CLI).
- **Target list mirrors the client's real targets** — there is **no wasmJs
  target** in the client build today; web is out of scope this worktree.
- **Moves in** (today `internal` in client → made `public`): `ALLOWED_SCHEMES`,
  `schemeOf` (from `CardRender.kt`); `vetMailto`, `sanitizePhone`,
  `percentEncode` (from `cards/PlatformActions.kt`). **Stays in client:**
  `cardActionUri` (depends on client `CardAction`), all `AnnotatedString` /
  `LinkAnnotation` rendering.
- **New:** `linkify(markdown: String, region: PhoneRegion = US): String`.

**Build wiring (per feasibility review):**
- `apps/settings.gradle.kts` → `include(":linkrules")`; client commonMain
  `implementation(project(":linkrules"))`.
- CLI is a **separate Gradle build** → cannot `project(":linkrules")`. Share via
  `srcDir("../client/linkrules/src/commonMain/kotlin")` — the existing
  `packages/schema/kotlin-gen` precedent. linkrules being stdlib-only makes this
  safe.

### `linkify` algorithm — mask-then-scan (BLOCKER fix)

Not a flat regex. Tokenize into **protected spans**, linkify only the gaps:

Protected (never modified, never scanned inside):
- fenced code blocks ```` ``` ```` and inline code `` `…` ``
- existing links `[label](url)` and images `![alt](url)`
- angle autolinks `<https://…>` / `<a@b.com>`
- bare URL runs `https?://…` (so phone/email-shaped substrings in a URL
  path/query/userinfo are left alone)
- reference-style link defs `[ref]: …`
- backslash-escaped `\[`, `\@`, etc.

Gaps are scanned for phone / email and wrapped. **Idempotency falls out for
free**: a second pass finds everything already inside a protected span, so
`linkify(linkify(x)) == linkify(x)` (fixpoint — required for stable re-push and
no spurious `version` churn).

### Phone rules (BLOCKER fix — strict NANP)

- Match a **strict NANP shape only**: optional `+1`, optional `(`, exactly
  3-3-4 with separators from `[ .-]`, word-boundary anchored.
- **Reject** if preceded by `$` / `#` / `v` / a letter, or followed by `-`+more
  digits. Kills: dates `2026-06-27`, ZIP+4 `94103-1234`, versions `v2.3.4567`,
  prices `$1,200-1,500`, IDs `#5551234567`, vanity `1-800-FLOWERS`, ranges.
- **Round-trip:** parse → format E.164 → the displayed label digits must equal
  the dialed digits (no silent wrong-country). Never double-prefix `+1` on an
  already-11-digit `1XXXXXXXXXX`.
- **When in doubt, do not link.** A missed link is calm; a wrong `tel:` is a
  broken/dangerous handoff.
- **Region** default = **US/NANP**, recorded `[pending-ratify]`, surfaced as CLI
  config (per-family later) — not hardcoded beyond the default. Numbers that
  aren't confidently NANP are left un-linked.
- Hand-rolled (no libphonenumber — it's JVM-only, won't cross to native/wasm).

### Email rules

- Conservative ASCII addr-spec with a dotted TLD; reuse `vetMailto`
  (promoted to `linkrules`, made `public`) — rejects whitespace / CRLF / `,` /
  `<` / `>` / `%` / params / multi-recipient.
- Strip trailing punctuation (mirror the renderer's autolink trim).
- **Reject** any candidate containing non-ASCII / homograph / RTL / zero-width /
  bidi / control chars (display-vs-target divergence). Punt IDN/unicode to an
  explicit author link.
- Do not link an email inside a URL's userinfo (covered by URL being a
  protected span).

### CLI integration

- `dayfold push` runs `linkify` on card `body_md` (and block `body_md`) before
  send, shows a **diff preview**; `--no-linkify` opt-out. Matches the curator
  propose-confirm ethos. Stored `body_md` is canonical-wrapped.
- **F8 cap:** linkify expands ~3× per phone. Check the linkified size against
  the 1 MB `body_md` cap **in the CLI** and fail with a clear message
  ("linkified body exceeds 1 MB — shorten") rather than letting the server
  413/422.

### Must-have tests (from gaps review)

Idempotency / protection (each also asserts the fixpoint):
1. `[555-123-4567](tel:+15551234567)` → unchanged.
2. inline code `` `call 555-123-4567 or a@b.com` `` → unchanged.
3. fenced block with phone+email → unchanged.
4. image alt `![call 555-123-4567](https://x.com)` → unchanged.
5. bare URL `https://x.com/o/5551234567` → unchanged (no phone link inside).
6. bare URL `https://a@b.com/p` → unchanged (no `mailto:` injected).
7. angle autolink `<a@b.com>` / `<https://x>` → unchanged.
8. table row `| 555-123-4567 | note |` → phone linked, `|` structure intact.
9. reference link `[t][r]` + `[r]: https://x` → defs untouched.
10. escaped `\@notmail` / `\[x\]` → untouched.

Phone false positives (must NOT link): `2026-06-27`, `94103-1234`,
`v2.3.4567`, `$1,200-1,500`, `#5551234567`, `1-800-FLOWERS`.

Phone true positives → `tel:+15551234567`: `555-123-4567`, `(555) 123-4567`,
`+1 555 123 4567`, `555.123.4567`; `15551234567` must not become `+115551234567`.

Email: `a@b.com` → `[a@b.com](mailto:a@b.com)`; `a@b.com.` trailing dot left
outside; `john@acme.co` inside a URL → not linked; unicode/RTL/zero-width
candidate → not linked.

Render security invariant (PR1/PR2): `[x](javascript:alert(1))` and
`[x](data:…)` never produce a `LinkAnnotation` (degrade to plain text).

Tests live in `linkrules/src/commonTest` (device-free `jvmTest`); CLI push
integration in `cli/src/test`.

---

## Out of scope

- Address detection in prose (D2 — structured payloads only).
- Server-side content validation (D5 — content-blind / E2EE).
- Structured 2-way card-action buttons (already RESERVED / ADR 0016).
- Changing the allowed-scheme set.
- Web/wasmJs target (not present in this worktree).

## Risks

- **Build topology** (YELLOW): two separate Gradle builds; the srcDir-share is
  the established precedent but the riskiest step is the new KMP module's
  target/metadata config under Kotlin 2.3.20 / CMP 1.11.1. Mitigation: stand up
  `:linkrules` + `:linkrules:jvmTest` green before wiring client/CLI.
- **Region correctness:** US-default could mis-dial non-US numbers. Mitigation:
  link only confident NANP; leave the rest plain; region is config.
- **main has moved** past the recon snapshot — re-verify all file paths in the
  worktree before editing.
