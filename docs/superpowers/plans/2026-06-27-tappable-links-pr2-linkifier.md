# Tappable Links PR2 — Author-Side Linkifier — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-linkify bare phone/email/URL entities in card & block `body_md` at author time (CLI `dayfold push`), producing canonical explicit markdown links, via a shared stdlib-only linkifier reused by client and CLI.

**Architecture:** A shared SOURCE DIR `packages/linkrules/` (not a Gradle module) is `srcDir`'d into both the client `commonMain` and the CLI `main` source sets — the exact precedent of `packages/schema/kotlin-gen`. It holds the existing scheme/vetting helpers (moved out of the client so there is ONE copy) plus a new idempotent `linkify(md, region)` using mask-then-scan. `dayfold push` runs `linkify` over every `body_md` in the payload JSON before PUT, with a diff preview, `--no-linkify` opt-out, and a post-expansion size check.

**Tech Stack:** Kotlin 2.3.20, KMP (client) + Kotlin/JVM (CLI), kotlinx-serialization-json (CLI side, for the JSON walk), JUnit-platform tests.

## Global Constraints

- Worktree: `/Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements` (branch `claude/link-improvements`, builds on PR1). Run all commands there.
- `packages/linkrules/` is **stdlib-only**: NO Compose, NO kotlinx, NO `java.*` — it compiles in client `commonMain` (android/desktop/iosArm64/iosSimulatorArm64) AND as plain JVM source in the CLI.
- Single allowlist source of truth stays `ALLOWED_SCHEMES = {https, mailto, tel, geo, sms}`; after the move it lives in `packages/linkrules/`.
- Server is content-blind (ADR 0015) — NO server changes. Linkification is author-side only; the client render-time allowlist remains the security boundary.
- `linkify` MUST be idempotent: `linkify(linkify(x)) == linkify(x)`.
- Addresses are NOT detected in prose (structured `geo` payload / `location` block only).
- Phone region default = US/NANP (`[pending-ratify]`); ambiguous numbers are left un-linked ("when in doubt, don't link").
- `body_md` cap = 1,048,576 chars (F8); the CLI fails clearly if linkify expansion crosses it.
- Commit after each task. Commit/PR messages written normally.

---

### Task 1: Stand up the shared `packages/linkrules/` source dir (move helpers, no behavior change)

De-risk the build topology FIRST with a pure move — no new logic. Moves the scheme + vetting helpers out of the client into the shared dir, wires both builds, proves everything still compiles and all existing tests pass.

**Files:**
- Create: `packages/linkrules/Schemes.kt` (package `com.sloopworks.dayfold.client`)
- Create: `packages/linkrules/Vetting.kt` (package `com.sloopworks.dayfold.client.cards`)
- Modify: `apps/client/build.gradle.kts` (srcDir into commonMain)
- Modify: `apps/cli/build.gradle.kts` (srcDir into main)
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/CardRender.kt` (remove moved defs)
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/PlatformActions.kt` (remove moved defs)

**Interfaces:**
- Produces (now from `packages/linkrules/`, same packages as before): `ALLOWED_SCHEMES: Set<String>`, `schemeOf(String): String` (package `…client`); `sanitizePhone(String): String?`, `vetMailto(String): String?`, `percentEncode(String): String` (package `…client.cards`).
- Consumes: nothing new. `vetMailto` changes from `private` → `internal` so the upcoming `linkify` (same package, Task 2) can call it; `cardActionUri`/`vettedOpenUri` keep calling it unchanged.

- [ ] **Step 1: Create `packages/linkrules/Schemes.kt`**

```kotlin
package com.sloopworks.dayfold.client

// SHARED (packages/linkrules, srcDir'd into client commonMain + the CLI). The ONE
// outbound-handoff scheme allowlist — the body-link renderer, the card action
// effect layer, AND the author-side linkifier all read this. Stdlib-only.
val ALLOWED_SCHEMES = setOf("https", "mailto", "tel", "geo", "sms")
fun schemeOf(url: String) = url.trim().substringBefore(":", "").lowercase()
```

- [ ] **Step 2: Create `packages/linkrules/Vetting.kt`** (move the bodies verbatim from `PlatformActions.kt`)

```kotlin
package com.sloopworks.dayfold.client.cards

// SHARED (packages/linkrules). Pure, stdlib-only URI-component vetting reused by
// the client effect layer (cardActionUri/vettedOpenUri) AND the author-side
// linkifier. Moved here from PlatformActions.kt so there is exactly one copy.

/**
 * mailto is authored content (untrusted) — vet the ADDRESS only, reject params
 * (`?subject=&body=`), multi-recipient (`,`), and any whitespace/CRLF header
 * injection; rebuild as a clean `mailto:<addr>`. Returns null if it doesn't vet.
 */
internal fun vetMailto(raw: String): String? {
  if (schemeOf(raw) != "mailto") return null
  val addr = raw.trim().removePrefix("mailto:").substringBefore("?")
  if (addr.isBlank()) return null
  if (addr.any { it.isWhitespace() || it == ',' || it == '<' || it == '>' || it == '%' }) return null
  if (!addr.contains('@')) return null
  return "mailto:$addr"
}

/** Keep a single leading '+' and digits only — drops spaces, DTMF/comma dialing,
 *  and any injection chars. Null if nothing dialable remains. */
fun sanitizePhone(raw: String): String? {
  val plus = if (raw.trimStart().startsWith("+")) "+" else ""
  val digits = raw.filter { it.isDigit() }
  return (plus + digits).takeIf { digits.isNotEmpty() }
}

/** Minimal RFC 3986 percent-encoding for a query value (no java.net in commonMain). */
fun percentEncode(s: String): String {
  val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
  val sb = StringBuilder()
  for (b in s.encodeToByteArray()) {
    val c = b.toInt() and 0xFF
    if (c.toChar() in unreserved) sb.append(c.toChar())
    else { sb.append('%'); sb.append("0123456789ABCDEF"[c shr 4]); sb.append("0123456789ABCDEF"[c and 0xF]) }
  }
  return sb.toString()
}
```

- [ ] **Step 3: Remove the moved defs from the client**

In `CardRender.kt`, delete the two lines (currently 44-45):
```kotlin
internal val ALLOWED_SCHEMES = setOf("https", "mailto", "tel", "geo", "sms")
internal fun schemeOf(url: String) = url.trim().substringBefore(":", "").lowercase()
```
(Keep the `// Single source of truth…` comment above them or move it to `Schemes.kt`.) The rest of `CardRender.kt` references `ALLOWED_SCHEMES`/`schemeOf` unchanged (same package).

In `PlatformActions.kt`, delete the `vetMailto` (was `private fun`), `sanitizePhone`, and `percentEncode` function bodies (currently lines ~40-69). Keep `cardActionUri` and `vettedOpenUri` — they still call `vetMailto`/`sanitizePhone`/`percentEncode` (now from `Vetting.kt`, same package).

- [ ] **Step 4: Wire both builds**

`apps/client/build.gradle.kts` — inside `commonMain by getting { … }` (after the `dependencies { }` block, still inside `commonMain`), add:
```kotlin
      kotlin.srcDir("../../packages/linkrules")
```
(Path is relative to `apps/client/`. Both `apps/cli` and `apps/client` sit 2 levels below the worktree root where `packages/` lives — review confirmed `../../packages/linkrules` resolves correctly for BOTH. Do NOT use `../../../` — that escapes the worktree.)

`apps/cli/build.gradle.kts` — after the existing schema srcDir line, add:
```kotlin
sourceSets["main"].kotlin.srcDir("../../packages/linkrules")
```

- [ ] **Step 5: Run the client desktop tests (no behavior change → all green)**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps && ./gradlew :client:desktopTest`
Expected: BUILD SUCCESSFUL — PlatformActionsTest/CardRenderTest/BlockMarkdownTest still pass against the moved-but-identical helpers.

- [ ] **Step 6: Run the CLI build + tests (proves the shared dir compiles as JVM source)**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps/cli && ./gradlew compileKotlin test`
Expected: BUILD SUCCESSFUL. If `Unresolved reference` for the srcDir, fix the relative path (Step 4) and re-run.

- [ ] **Step 7: Commit**

```bash
cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements
git add packages/linkrules apps/client apps/cli
git commit -m "refactor(linkrules): extract shared scheme+vetting helpers to packages/linkrules

Moves ALLOWED_SCHEMES/schemeOf/sanitizePhone/vetMailto/percentEncode into a
stdlib-only source dir srcDir'd into client commonMain + the CLI (the
kotlin-gen precedent). No behavior change. Enables the shared linkifier."
```

---

### Task 2: `linkify` — mask-then-scan, idempotent, strict NANP + email

Add the linkifier to the shared dir. TDD against the must-have table; refine the regexes until every case passes.

**Files:**
- Create: `packages/linkrules/Linkify.kt` (package `com.sloopworks.dayfold.client.cards`)
- Test: `apps/cli/src/test/kotlin/LinkifyTest.kt` (JVM, device-free; the CLI compiles the shared dir so it can test `linkify` directly)

**Interfaces:**
- Consumes: `vetMailto`, `sanitizePhone` (Task 1, same package).
- Produces: `enum class PhoneRegion { US }`; `fun linkify(md: String, region: PhoneRegion = PhoneRegion.US): String`.

- [ ] **Step 1: Write the failing tests** (`LinkifyTest.kt`) — the full must-have table

```kotlin
import com.sloopworks.dayfold.client.cards.linkify
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkifyTest {
  private fun fixpoint(s: String) = assertEquals(linkify(s), linkify(linkify(s)), "not idempotent: $s")

  // ── phone true positives → tel:+1XXXXXXXXXX ──
  @Test fun phones_link() {
    assertEquals("[555-123-4567](tel:+15551234567)", linkify("555-123-4567"))
    assertEquals("[(555) 123-4567](tel:+15551234567)", linkify("(555) 123-4567"))
    assertEquals("[+1 555 123 4567](tel:+15551234567)", linkify("+1 555 123 4567"))
    assertEquals("[555.123.4567](tel:+15551234567)", linkify("555.123.4567"))
    // already 11-digit with country code → no double +1
    assertEquals("[1-555-123-4567](tel:+15551234567)", linkify("1-555-123-4567"))
  }

  // ── phone false positives → untouched ──
  @Test fun phone_false_positives_untouched() {
    for (s in listOf("2026-06-27", "94103-1234", "v2.3.4567", "\$1,200-1,500", "#5551234567", "1-800-FLOWERS")) {
      assertEquals(s, linkify(s), "should not link: $s")
    }
  }

  // ── email ──
  @Test fun emails_link() {
    assertEquals("[a@b.com](mailto:a@b.com)", linkify("a@b.com"))
    assertEquals("[a@b.com](mailto:a@b.com).", linkify("a@b.com."))       // trailing dot stays outside
    assertEquals("see https://a@b.com/p", linkify("see https://a@b.com/p")) // userinfo in URL → not an email
  }

  // ── protected spans → untouched + idempotent ──
  @Test fun protected_spans() {
    val cases = listOf(
      "[555-123-4567](tel:+15551234567)",          // already linked
      "`call 555-123-4567 or a@b.com`",            // inline code
      "```\ncall 555-123-4567\n```",               // fenced code
      "![call 555-123-4567](https://x.com)",       // image alt
      "https://x.com/o/5551234567",                // bare url with phone-like path
      "<a@b.com>",                                  // angle autolink
      "\\@notmail and \\[x\\]",                     // escaped
    )
    for (s in cases) { assertEquals(s, linkify(s), "should be untouched: $s"); fixpoint(s) }
  }

  // ── table cell phone links, structure intact ──
  @Test fun table_cell() {
    assertEquals("| [555-123-4567](tel:+15551234567) | note |", linkify("| 555-123-4567 | note |"))
  }

  @Test fun idempotent_on_mixed() {
    fixpoint("Call 555-123-4567 or email a@b.com about https://x.com/5551234567")
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps/cli && ./gradlew test --tests "LinkifyTest"`
Expected: FAIL — `Unresolved reference: linkify`.

- [ ] **Step 3: Implement `linkify` (mask-then-scan)**

```kotlin
package com.sloopworks.dayfold.client.cards

enum class PhoneRegion { US }

// Protected spans — never modified, never scanned inside (priority order; fenced
// code first). Idempotency falls out: a 2nd pass finds new links inside protected
// spans. RegexOption.DOT_MATCHES_ALL lets ``` … ``` span newlines.
private val PROTECTED = Regex(
  "```[\\s\\S]*?```" +              // fenced code block
    "|`[^`]*`" +                    // inline code
    "|^\\[[^\\]]+]:\\s*\\S+" +      // reference-style link def `[ref]: url` (MULTILINE)
    "|!\\[[^\\]]*]\\([^)]*\\)" +    // image ![alt](url)
    "|\\[[^\\]]*]\\([^)]*\\)" +     // link [label](url)
    "|<[^>\\s]+>" +                 // angle autolink <…>
    "|https?://[^\\s)]+" +          // bare URL (phone/email-like substrings inside left alone)
    "|\\\\.",                       // backslash-escaped char
  setOf(RegexOption.MULTILINE),     // `^` matches each line start (for the ref-def alt)
)

// Strict NANP: optional +1/1, 3-3-4 with separators from [ .-], NOT preceded by a
// word char / $ / # / - (kills $1,200-1,500, #555…, version tails) and NOT followed
// by more digits or a '-digit' (kills ZIP+4 94103-1234, dates 2026-06-27).
private val PHONE = Regex(
  "(?<![\\w\$#-])(?:\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]?\\d{3}[ .-]?\\d{4}(?![\\d-])",
)

// Conservative ASCII addr-spec with a dotted TLD. Trailing '.' excluded by the TLD
// class boundary. vetMailto re-checks the address (params/CRLF/%/<>).
private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

private fun isAsciiPrintable(s: String) = s.all { it.code in 0x20..0x7E }

private fun phoneLink(match: String): String? {
  val tel = sanitizePhone(match) ?: return null
  val digits = tel.removePrefix("+")
  val e164 = when {
    digits.length == 10 -> "+1$digits"            // bare NANP → +1
    digits.length == 11 && digits.startsWith("1") -> "+$digits" // already has country code
    else -> return null                            // not confidently NANP → don't link
  }
  return "[$match](tel:$e164)"
}

private fun emailLink(match: String): String? {
  if (!isAsciiPrintable(match)) return null         // reject unicode/homograph/RTL/zero-width
  val mailto = vetMailto("mailto:$match") ?: return null
  return "[$match]($mailto)"
}

// Linkify a gap (text known to be outside any protected span): wrap phones + emails.
private fun linkifyGap(text: String): String {
  // Single left-to-right pass over phone|email matches so they can't overlap.
  val combined = Regex("(${PHONE.pattern})|(${EMAIL.pattern})")
  val sb = StringBuilder()
  var i = 0
  for (m in combined.findAll(text)) {
    if (m.range.first < i) continue
    sb.append(text, i, m.range.first)
    val raw = m.value
    val replaced = (if (m.groupValues[1].isNotEmpty()) phoneLink(raw) else emailLink(raw)) ?: raw
    sb.append(replaced)
    i = m.range.last + 1
  }
  sb.append(text.substring(i))
  return sb.toString()
}

/** Author-side: wrap bare phone/email/URL entities in [md] as explicit allowlisted
 *  markdown links, leaving protected spans untouched. Idempotent. Addresses are NOT
 *  detected (structured geo payload only). Bare URLs are already protected (the
 *  renderer autolinks them), so they pass through unchanged. */
fun linkify(md: String, region: PhoneRegion = PhoneRegion.US): String {
  val sb = StringBuilder()
  var i = 0
  for (p in PROTECTED.findAll(md)) {
    if (p.range.first < i) continue
    if (i < p.range.first) sb.append(linkifyGap(md.substring(i, p.range.first)))
    sb.append(md.substring(p.range.first, p.range.last + 1)) // verbatim
    i = p.range.last + 1
  }
  if (i < md.length) sb.append(linkifyGap(md.substring(i)))
  return sb.toString()
}
```

- [ ] **Step 4: Run the tests; iterate the regexes until green**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps/cli && ./gradlew test --tests "LinkifyTest"`
Expected: PASS. If a case fails, adjust the specific regex (PHONE look-around for false positives; PROTECTED ordering for collisions) — do NOT loosen so far that a false-positive test regresses. Re-run until all pass.

- [ ] **Step 5: Confirm the shared dir still compiles for the client (KMP targets)**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps && ./gradlew :client:compileKotlinDesktop :client:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL (linkify is stdlib-only → compiles in commonMain too).

- [ ] **Step 6: Commit**

```bash
cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements
git add packages/linkrules apps/cli/src/test
git commit -m "feat(linkrules): linkify() — author-side mask-then-scan phone/email linkifier

Idempotent. Strict NANP phones (rejects dates/ZIP+4/versions/prices/IDs),
ASCII-only emails via vetMailto, protected spans (code/links/images/autolinks/
bare-urls/escapes) left verbatim. Addresses out of scope (structured payloads)."
```

---

### Task 3: CLI `dayfold push` — linkify `body_md`, diff preview, opt-out, size cap

Run `linkify` over every `body_md` in the payload JSON before PUT.

**Files:**
- Create: `apps/cli/src/main/kotlin/Linkify.kt` (CLI-side JSON walk: `linkifyPayload`)
- Modify: `apps/cli/src/main/kotlin/Main.kt` (call it in the `"push"` branch, ~line 184-190; add `--no-linkify` to usage)
- Test: `apps/cli/src/test/kotlin/LinkifyPayloadTest.kt`

**Interfaces:**
- Consumes: `com.sloopworks.dayfold.client.cards.linkify`; `kotlinx.serialization.json.*` (already a CLI dep).
- Produces: `fun linkifyPayload(json: String): LinkifyResult` where `data class LinkifyResult(val json: String, val diffs: List<Pair<String,String>>, val maxBodyLen: Int)` — rewritten JSON, (before,after) per changed `body_md`, and the longest linkified `body_md` field length (for the per-field F8 cap check, matching the server's per-field rule); `const val BODY_MD_CAP = 1_048_576`.

- [ ] **Step 1: Write the failing test** (`LinkifyPayloadTest.kt`)

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkifyPayloadTest {
  @Test fun rewrites_card_body_md_and_reports_diff() {
    val (out, diffs) = linkifyPayload("""{"id":"x","kind":"info","body_md":"call 555-123-4567"}""")
    assertTrue(out.contains("[555-123-4567](tel:+15551234567)"))
    assertEquals(1, diffs.size)
  }

  @Test fun rewrites_nested_block_body_md() {
    val (out, _) = linkifyPayload("""{"sections":[{"blocks":[{"body_md":"a@b.com"}]}]}""")
    assertTrue(out.contains("[a@b.com](mailto:a@b.com)"))
  }

  @Test fun no_change_returns_empty_diff() {
    val (_, diffs) = linkifyPayload("""{"body_md":"nothing here"}""")
    assertTrue(diffs.isEmpty())
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps/cli && ./gradlew test --tests "LinkifyPayloadTest"`
Expected: FAIL — `Unresolved reference: linkifyPayload`.

- [ ] **Step 3: Implement `apps/cli/src/main/kotlin/Linkify.kt`**

```kotlin
package com.sloopworks.dayfold.cli

import com.sloopworks.dayfold.client.cards.linkify
import kotlinx.serialization.json.*

const val BODY_MD_CAP = 1_048_576

data class LinkifyResult(val json: String, val diffs: List<Pair<String, String>>, val maxBodyLen: Int)

private val J = Json { prettyPrint = false }

/** Recursively rewrite every "body_md" string in the payload via linkify().
 *  Returns the new JSON, a (before, after) pair per changed body, and the longest
 *  linkified body_md length (for the per-field F8 cap check — the server caps each
 *  body_md field, not the whole payload). */
fun linkifyPayload(json: String): LinkifyResult {
  val diffs = mutableListOf<Pair<String, String>>()
  var maxBodyLen = 0
  fun walk(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.mapValues { (k, v) ->
      if (k == "body_md" && v is JsonPrimitive && v.isString) {
        val before = v.content; val after = linkify(before)
        if (after != before) diffs += before to after
        if (after.length > maxBodyLen) maxBodyLen = after.length
        JsonPrimitive(after)
      } else walk(v)
    })
    is JsonArray -> JsonArray(e.map { walk(it) })
    else -> e
  }
  val out = J.encodeToString(JsonElement.serializer(), walk(J.parseToJsonElement(json)))
  return LinkifyResult(out, diffs, maxBodyLen)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps/cli && ./gradlew test --tests "LinkifyPayloadTest"`
Expected: PASS.

- [ ] **Step 5: Integrate into the `push` branch of `Main.kt`**

After `val payload = …` (line ~184-185) and before the `pushResource`/validation block, insert:

```kotlin
      // Author-side linkify (CL-LINK): wrap bare phone/email entities in body_md
      // into explicit allowlisted links before storing (server is content-blind).
      // --no-linkify opts out; the result is the canonical stored body.
      val payload = if ("--no-linkify" in args) rawPayload else {
        val r = linkifyPayload(rawPayload)
        if (r.diffs.isNotEmpty()) {
          System.err.println("linkified ${r.diffs.size} body_md field(s):")
          r.diffs.forEach { (b, a) -> System.err.println("  - $b\n  + $a") }
        }
        if (r.maxBodyLen > BODY_MD_CAP) {
          System.err.println("a linkified body_md exceeds ${BODY_MD_CAP} chars — shorten"); exitProcess(1)
        }
        r.json
      }
```

NOTE: `val payload` is already declared at line 184 (`val payload = try { … }`). Rename the original to `val rawPayload = try { … }` and make this block produce `val payload`, so the rest of the branch (validation, PUT) uses the linkified `payload` unchanged. Adjust the `withId(payload, id)` validation call to run on the linkified payload (correct — we validate what we store).

Add `[--no-linkify]` to the `push` usage string (line ~365).

- [ ] **Step 6: Run the full CLI build + tests**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps/cli && ./gradlew build`
Expected: BUILD SUCCESSFUL; all CLI tests pass.

- [ ] **Step 7: Commit**

```bash
cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements
git add apps/cli/src
git commit -m "feat(cli): dayfold push linkifies body_md (diff preview, --no-linkify, size cap)"
```

---

### Task 4: End-to-end verification

**Files:** create a throwaway fixture (do NOT commit).

- [ ] **Step 1: Build the CLI distribution**

Run: `cd /Users/patrick/workspace/dayfold/.claude/worktrees/link-improvements/apps/cli && ./gradlew installDist`

- [ ] **Step 2: Linkify-preview a sample card via a dry path**

Write `/tmp/card.json`: `{"id":"01J000000000000000000TEST","kind":"info","title":"t","body_md":"Call 555-123-4567 or email coach@school.edu. Field: see https://maps.x/o/5551234567","provenance":{"source":"user","at":"2026-06-27T00:00:00Z"}}`
Run the push in offline/preview mode if available, OR run a tiny Kotlin/JVM main that calls `linkifyPayload(File("/tmp/card.json").readText())` and prints the result. Confirm:
- `555-123-4567` → `[555-123-4567](tel:+15551234567)`
- `coach@school.edu` → `[coach@school.edu](mailto:coach@school.edu)`
- the `5551234567` inside the `https://maps.x/...` URL is NOT linked.

- [ ] **Step 3: Confirm idempotency on the real fixture**

Run linkify twice over the same body; assert byte-identical output (no double-wrap, no version churn on re-push).

- [ ] **Step 4: Record results** in the PR description (what was linkified, what was correctly skipped, idempotency confirmed).

---

## Self-Review

**Spec coverage** (PR2 section of `2026-06-27-tappable-links-design.md`):
- Shared stdlib-only `linkrules` → Task 1 (source dir, both builds). ✓
- mask-then-scan + idempotency → Task 2 (PROTECTED ranges + fixpoint tests). ✓
- strict NANP + email rules → Task 2 (PHONE look-around, EMAIL ascii+vetMailto). ✓
- CLI push linkify + diff preview + --no-linkify + F8 cap → Task 3. ✓
- No server change / content-blind → honored (author-side only). ✓
- Addresses out of scope → honored (no address regex). ✓

**Placeholder scan:** No TBD/TODO. Real code in every code step. Two VERIFY notes (srcDir relative-path depth in Task 1 Step 4; `val payload` rename in Task 3 Step 5) are explicit, concrete instructions — not placeholders.

**Type consistency:** `linkify(String, PhoneRegion): String`, `linkifyPayload(String): Pair<String, List<Pair<String,String>>>`, `BODY_MD_CAP`, `PhoneRegion.US` — consistent across Tasks 2–3. `vetMailto` visibility `private`→`internal` is the only signature change, noted in Task 1.

**Risks:**
- Regex precision (phone false positives / protected-span collisions) — Task 2 is TDD against the exact failing-input table; iterate until green.
- srcDir relative-path depth — verified empirically in Task 1 Step 6 before any logic depends on it.
- The CLI `Json` re-encode may reorder keys / drop formatting vs the author's file — acceptable (server is the authority on shape; body content is preserved). If key-order stability matters for diffs, note it; not a correctness issue.
