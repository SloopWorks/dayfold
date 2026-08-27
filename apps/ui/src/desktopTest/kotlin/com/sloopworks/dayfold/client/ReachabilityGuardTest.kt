package com.sloopworks.dayfold.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// WI-462 — every part of a feature can be built and unit-tested in isolation while the SEAM that
// reaches it (a dispatch, a render call, a route registration) is missing. Unit tests on the
// parts are structurally incapable of catching that: they call the part directly. This guard
// text-scans production Kotlin source (comments/imports/tests stripped) for three failure shapes
// that have each shipped to `main` at least once (see the three real incidents in WI-462's body):
//   1. a `Route` enum member that no production reducer arm ever assigns
//   2. a `*Host`/`*Screen` composable under `features/*/` with no real call site anywhere in
//      production — same-file wiring (a private sub-screen called by its own top-level Host)
//      still counts; only a symbol with NO call site at all, own file included, is a violation
//   3. a `sealed interface *Action` member never referenced from production code anywhere except
//      the reducer arm that pattern-matches it (a reducer arm alone does not dispatch the action)
// It is NOT a compiler and NOT a full call-graph — see ROUTE_ALLOW_LIST / COMPOSABLE_ALLOW_LIST /
// ACTION_ALLOW_LIST below for genuine exceptions and the reasoning for each.

internal data class SourceFile(val path: String, val text: String)
internal data class DeclaredSymbol(val name: String, val file: String)

// ---------------------------------------------------------------------------
// Pure text-scanning logic — unit-tested below with synthetic fixtures so the
// detector's behavior is proven independent of the real repo's current state.
// ---------------------------------------------------------------------------

// Strips block and line comments (and skips over string/char literals, so a comment-opener
// sequence inside a string literal can't be mistaken for a real one) so a bare-name mention in
// prose can't fake a reference. A naive "regex block comment, then regex line comment" two-pass
// approach breaks on a line comment whose prose happens to contain a block-comment-opener
// substring (this repo has one, in AuthEngine.kt, describing an action-naming convention): a
// text-only block-comment regex applied first reads that substring as an unterminated block
// comment and swallows every real line until the next unrelated block-comment-closer — silently
// deleting genuine code from the scan. A single left-to-right pass that tracks string/comment
// state avoids that ordering trap.
internal fun stripComments(text: String): String {
  val sb = StringBuilder(text.length)
  val n = text.length
  var i = 0
  while (i < n) {
    when {
      text.startsWith("\"\"\"", i) -> {
        val end = text.indexOf("\"\"\"", i + 3)
        val stop = if (end < 0) n else end + 3
        sb.append(text, i, stop)
        i = stop
      }
      text[i] == '"' || text[i] == '\'' -> {
        val quote = text[i]
        var j = i + 1
        while (j < n && text[j] != quote) { if (text[j] == '\\' && j + 1 < n) j++; j++ }
        val stop = (j + 1).coerceAtMost(n)
        sb.append(text, i, stop)
        i = stop
      }
      text.startsWith("//", i) -> while (i < n && text[i] != '\n') i++
      text.startsWith("/*", i) -> {
        val end = text.indexOf("*/", i + 2)
        i = if (end < 0) n else end + 2
      }
      else -> { sb.append(text[i]); i++ }
    }
  }
  return sb.toString()
}

/** Strips `import` lines so an unused/leftover import can't fake a reference either. */
internal fun stripImports(text: String): String =
  text.lineSequence().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")

internal fun cleanForReferenceScan(text: String): String = stripImports(stripComments(text))

/** Joins newlines that fall inside unbalanced parens, so a multi-line `data class Foo(\n...\n) : Bar`
 * declaration reads as one logical line for the supertype regex below. */
internal fun collapseParenNewlines(text: String): String {
  val sb = StringBuilder(text.length)
  var depth = 0
  for (c in text) {
    when {
      c == '(' -> { depth++; sb.append(c) }
      c == ')' -> { depth = (depth - 1).coerceAtLeast(0); sb.append(c) }
      c == '\n' && depth > 0 -> sb.append(' ')
      else -> sb.append(c)
    }
  }
  return sb.toString()
}

// ---- Check 2: *Host / *Screen composables under features/*/ ----

private val COMPOSABLE_DECL = Regex("""fun\s+(\w*(?:Host|Screen))\s*\(""")

internal fun findHostScreenDeclarations(files: List<SourceFile>): List<DeclaredSymbol> {
  val out = mutableListOf<DeclaredSymbol>()
  for (f in files) {
    if ("/features/" !in f.path.replace('\\', '/')) continue
    val lines = stripComments(f.text).lines()
    for (i in lines.indices) {
      val m = COMPOSABLE_DECL.find(lines[i]) ?: continue
      val windowStart = (i - 3).coerceAtLeast(0)
      val isComposable = (windowStart..i).any { "@Composable" in lines[it] }
      if (isComposable) out += DeclaredSymbol(m.groupValues[1], f.path)
    }
  }
  return out
}

// ---- Check 3: sealed interface *Action members ----

private val ACTION_FAMILY_DECL = Regex("""sealed interface\s+(\w*Action)\b""")
// Tolerates an optional single-line annotation (`@Serializable`) and visibility/data modifiers
// in any order (`internal data class`, `private data object`, ...) before the `class`/`object`
// keyword — an implementer declared with any of these was previously invisible to this scanner
// entirely (not flagged, not even discovered), the exact "found nothing wrong" failure mode this
// guard exists to close.
private val IMPLEMENTER_DECL_START =
  Regex("""^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:data|internal|private|public|protected)\s+)*(object|class)\s+(\w+)""")
private val SUPERTYPE_AFTER_PARAMS = Regex("""^\s*:\s*(\w+)""")
private val NON_LEAF_PREFIX = Regex("""^\s*(?:sealed\s+)?(?:interface|abstract\s+class)\b""")
private val REDUCER_FILE = Regex("""(?:^|/)\w*Reducer\w*\.kt$""")

/** Every `sealed interface *Action` name in production — the root `Action` marker included. */
internal fun findActionFamilies(files: List<SourceFile>): Set<String> {
  val out = mutableSetOf<String>()
  for (f in files) ACTION_FAMILY_DECL.findAll(stripComments(f.text)).forEach { out += it.groupValues[1] }
  return out
}

// Advances past an optional `<...>` generic parameter list starting at [from] (spaces allowed
// before it) — so `data class OpenFoo<T>(val payload: T) : FooAction<T>` doesn't leave the `<T>`
// sitting between the name and `(`, which would otherwise make skipBalancedParams below think
// there's no parameter list to skip at all (its blank-gap check would see `<T>`, not blank).
private fun skipGenericParams(line: String, from: Int): Int {
  var i = from
  while (i < line.length && line[i] == ' ') i++
  if (i >= line.length || line[i] != '<') return from
  var depth = 0
  while (i < line.length) {
    when (line[i]) {
      '<' -> depth++
      '>' -> { depth--; if (depth == 0) return i + 1 }
    }
    i++
  }
  return from
}

// Advances past a balanced `(...)` parameter list starting at [from] IF the next non-space
// character there is '(' — so a plain `data object Foo : Bar` (no params) is left untouched.
// A naive "supertype = word after the next colon" regex breaks on `data class Foo(val id:
// String) : Bar`: the FIRST colon it finds is `id:`, not the real supertype separator. Skipping
// the whole parameter span first (respecting nested parens, e.g. a default value like `f(1, 2)`)
// is what a plain regex can't express without this manual scan.
private fun skipBalancedParams(line: String, from: Int): Int {
  val openParen = line.indexOf('(', from)
  if (openParen < 0 || !line.substring(from, openParen).isBlank()) return from
  var depth = 0
  var i = openParen
  while (i < line.length) {
    when (line[i]) {
      '(' -> depth++
      ')' -> { depth--; if (depth == 0) return i + 1 }
    }
    i++
  }
  return line.length
}

/** Every concrete `data object`/`data class` (top-level or nested) implementing one of [families]. */
internal fun findActionImplementers(files: List<SourceFile>, families: Set<String>): List<DeclaredSymbol> {
  if (families.isEmpty()) return emptyList()
  val out = mutableListOf<DeclaredSymbol>()
  for (f in files) {
    val collapsed = collapseParenNewlines(stripComments(f.text))
    for (line in collapsed.lines()) {
      if (NON_LEAF_PREFIX.containsMatchIn(line)) continue
      val start = IMPLEMENTER_DECL_START.find(line) ?: continue
      val name = start.groupValues[2]
      val afterGeneric = skipGenericParams(line, start.range.last + 1)
      val afterParams = skipBalancedParams(line, afterGeneric)
      val superMatch = SUPERTYPE_AFTER_PARAMS.find(line.substring(afterParams)) ?: continue
      if (superMatch.groupValues[1] in families) out += DeclaredSymbol(name, f.path)
    }
  }
  return out.distinctBy { it.name to it.file }
}

// ---- Check 1: Route members ----

private val ROUTE_ENUM_DECL = Regex("""enum class Route\s*\{([^}]*)\}""")
// The `=` alternative must NOT match the trailing `=` of a comparison (`==`, `!=`, `<=`, `>=`) —
// `state.navigation.route == Route.Feed` (real code in AppShellSelectors.kt/BackNav.kt) is a
// read, not a production dispatcher; a regex that can't tell the two apart would credit every
// Route ever merely COMPARED against as "reachable," defeating this check's entire purpose.
private val ROUTE_ASSIGNMENT = Regex("""(?:(?<![=!<>+\-*/])=(?!=)|->)\s*Route\.(\w+)\b""")

internal fun findRouteMembers(modelFileText: String): List<String> {
  val m = ROUTE_ENUM_DECL.find(modelFileText) ?: return emptyList()
  return m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Route names that appear as the target of an assignment/branch-result — i.e. something in
 * production actually produces that route, as opposed to only matching on it in a `when`. */
internal fun findRoutesAssignedInProduction(files: List<SourceFile>): Set<String> {
  val out = mutableSetOf<String>()
  for (f in files) ROUTE_ASSIGNMENT.findAll(cleanForReferenceScan(f.text)).forEach { out += it.groupValues[1] }
  return out
}

// ---- shared: "declared here, never actually invoked anywhere in production" ----

/**
 * [declared] symbols whose name occurs at most once across [evidenceFiles] (comments/imports
 * already stripped by the caller) — i.e. only the declaration line itself, nothing else.
 *
 * Deliberately NOT "referenced outside its own file": a private sub-composable called by its
 * sibling in the same file, or a screen-local action dispatched by a button in the same file it's
 * declared in, is legitimately wired — same-file wiring is still wiring. What makes a symbol a
 * violation is having NO real call/dispatch site anywhere, own file included. [evidenceFiles]
 * lets the caller pre-exclude categories that can never be a real call site (a reducer's `is X ->`
 * pattern-match doesn't dispatch X — see the Action check below).
 */
internal fun findUnreferenced(
  declared: List<DeclaredSymbol>,
  evidenceFiles: List<SourceFile>,
  allowList: Set<String>,
): List<DeclaredSymbol> = declared.filter { sym ->
  sym.name !in allowList && run {
    val pattern = Regex("""\b${Regex.escape(sym.name)}\b""")
    val occurrences = evidenceFiles.sumOf { f -> pattern.findAll(f.text).count() }
    occurrences <= 1
  }
}

// ---------------------------------------------------------------------------
// The recorded exceptions. Every entry must say WHY — a deliberately dark
// surface, a preview-only route, or a dated pointer to the WI actively wiring
// it (remove the entry once that WI lands and the guard would pass without it).
//
// Three SEPARATE maps, not one shared one: Route names, composable names, and action names are
// different namespaces that can coincidentally collide (e.g. a Route and an unrelated Action both
// named the same thing) — a single flat allow-list would let an entry meant to excuse one kind of
// violation silently also excuse an unrelated one sharing its name, with zero visibility.
// ---------------------------------------------------------------------------

private const val CALENDAR_CHECK_STAGED_EPIC_NOTE =
  "ADR 0063 (Calendar Check, still Proposed) — staged incrementally; same posture as the other " +
    "unwired Calendar Check surfaces in this list, revisit together. "

internal val ROUTE_ALLOW_LIST: Map<String, String> = emptyMap()

internal val COMPOSABLE_ALLOW_LIST: Map<String, String> = mapOf(
  "CalendarSettingsHost" to
    "WI-461 (in review as of 2026-08-10) wires this from Account > On this device. Remove once merged.",
  "CalendarReviewHost" to
    "WI-461 (in review as of 2026-08-10) wires this from the Now card's Review tap. Remove once merged.",
  "CalendarImportHost" to
    "WI-461 audited this as still-unreached and explicitly deferred (needs " +
      "CalendarImportEngine.eligibleDestinationHubs() plumbing, out of that WI's scope) — a recorded " +
      "decision, not an accident. Revisit when that plumbing lands.",
  "CalendarAlertOverrideHost" to
    CALENDAR_CHECK_STAGED_EPIC_NOTE + "Its own doc comment says it's opened from a matched review " +
      "item, and that caller isn't wired yet either.",
  "CalendarMatchedSummaryScreen" to
    CALENDAR_CHECK_STAGED_EPIC_NOTE + "Its own doc comment: 'A future WI supplies real values from " +
      "wherever this screen is entered ... same deferred-nav posture as the rest of this epic " +
      "pending ADR 0063 acceptance.'",
  "CalendarReturnScreen" to
    CALENDAR_CHECK_STAGED_EPIC_NOTE + "The native-calendar-handoff return screen (WI-447), not yet " +
      "mounted anywhere production reaches.",
)

private const val ROUTINE_PREVIEW_FIXTURE_REASON =
  "RoutinePreviewAction is a closed, local-only fixture demonstrating the deferred (G1 content-" +
    "authoring loop) Smart Briefings preview — see RoutineActions.kt's own doc comment. " +
    "smartBriefingsPreviewActions() doesn't map every declared preview transition yet; no real " +
    "provider/effect is ever invoked by this family. Found 2026-08-10; low-risk completeness gap. " +
    "Re-verified 2026-08-27 (WI-462 triage): smartBriefingsPreviewActions() still emits a different " +
    "subset (RoutineContinue/NavigateBack/ProviderSelected/SourcesSelected/HubSelected/" +
    "ScheduleSelected/PrivacyAcknowledged/OperationStarted/DraftPresented/DraftDecisionPreviewed/" +
    "RunFinished/RevokeFinished), so these six stay unreachable. Keep allow-listed until the G1 " +
    "content-authoring loop makes this a real feature rather than a fixture."

internal val ACTION_ALLOW_LIST: Map<String, String> = mapOf(
  // --- found by this guard's first real run (2026-08-10); TRIAGED 2026-08-27 (WI-462 follow-up).
  // Each is either dead code from a superseded path or a real gap. Wire-vs-delete is a product call
  // outside WI-462's scope (ADR governance: scope/product decisions aren't agent-autonomous), so the
  // three below are escalated as INB-42 rather than decided here. Remove each entry once that answer
  // lands and the symbol is either dispatched or deleted.
  // The triage also retired one entry: ResponseStepBack, allow-listed 2026-08-10 as "no back
  // affordance wired", has been dispatched from FeedApp.kt since 01ec785b (2026-08-19) — the guard
  // now enforces it. Re-verify entries on each pass; a stale allow-list is a silent guard. ---
  "HubsFailed" to
    "Found 2026-08-10: HubsLoaded (its success sibling) is dispatched from HubEngine/SyncEngine/" +
      "ContentBridge, but nothing dispatches HubsFailed — HubEngine.kt:258's comment suggests the " +
      "hub-list-load path it belonged to was superseded by DB-driven sync. May be dead code. Needs " +
      "product triage, not an autonomous fix. " +
      "Re-verified 2026-08-27: still dispatched from nowhere. → INB-42.",
  "HubNotFound" to
    "Found 2026-08-10: the 404/restricted-hub reducer arm exists but nothing dispatches it. Needs " +
      "triage: wire a real 404 handler, or remove as dead code. Needs product triage. " +
      "Re-verified 2026-08-27: still dispatched from nowhere. → INB-42.",
  "CalendarSettingsLoaded" to
    "Found 2026-08-10: the reducer arm exists but nothing in CalendarCheckEngine/ContentStore " +
      "dispatches it — calendar settings may already be read a different way. Needs product triage. " +
      "Re-verified 2026-08-27: still dispatched from nowhere. → INB-42.",
  "RestoreSmartBriefings" to ROUTINE_PREVIEW_FIXTURE_REASON,
  "RoutineHubFixtureSelected" to ROUTINE_PREVIEW_FIXTURE_REASON,
  "RoutinePreparationCompleted" to ROUTINE_PREVIEW_FIXTURE_REASON,
  "RoutinePreparationFailed" to ROUTINE_PREVIEW_FIXTURE_REASON,
  "RoutineProviderReturnCompleted" to ROUTINE_PREVIEW_FIXTURE_REASON,
  "RoutineOfflineChanged" to ROUTINE_PREVIEW_FIXTURE_REASON,
)

// ---------------------------------------------------------------------------
// Repo/file-system plumbing for the integration checks below.
// ---------------------------------------------------------------------------

private fun findAppsRoot(): File {
  var dir = File(System.getProperty("user.dir")).absoluteFile
  repeat(8) {
    if (File(dir, "settings.gradle.kts").isFile && File(dir, "client").isDirectory && File(dir, "ui").isDirectory) {
      return dir
    }
    dir = dir.parentFile ?: error(
      "ReachabilityGuardTest: walked past the filesystem root looking for apps/ from " +
        System.getProperty("user.dir"),
    )
  }
  error("ReachabilityGuardTest: could not find the apps/ Gradle root within 8 levels of ${System.getProperty("user.dir")}")
}

// Every production (non-test) Kotlin source set across the two KMP modules + the thin Android app.
private val PRODUCTION_SOURCE_DIRS = listOf(
  "client/src/commonMain", "client/src/androidMain", "client/src/iosMain", "client/src/desktopMain",
  "ui/src/commonMain", "ui/src/androidMain", "ui/src/iosMain", "ui/src/desktopMain",
  "androidApp/src/main", "androidApp/src/debug", "androidApp/src/release",
)

private fun loadProductionSourceFiles(appsRoot: File): List<SourceFile> =
  PRODUCTION_SOURCE_DIRS.mapNotNull { rel -> File(appsRoot, rel).takeIf { it.isDirectory } }
    .flatMap { dir ->
      dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { file ->
        SourceFile(file.relativeTo(appsRoot).path.replace('\\', '/'), file.readText())
      }
    }.toList()

// ===========================================================================
// Unit tests — prove the detector logic itself, independent of the real repo.
// ===========================================================================

class ReachabilityGuardDetectorTest {

  @Test
  fun aComposableWithNoOtherCallerIsFlagged() {
    val decl = SourceFile("ui/features/foo/FooHost.kt", "@Composable\nfun FooHost() { Text(\"hi\") }")
    val declared = findHostScreenDeclarations(listOf(decl))
    assertEquals(listOf(DeclaredSymbol("FooHost", decl.path)), declared)
    val evidence = listOf(decl).map { it.copy(text = cleanForReferenceScan(it.text)) }
    assertEquals(1, findUnreferenced(declared, evidence, emptySet()).size)
  }

  @Test
  fun aComposableCalledFromAnotherProductionFileIsNotFlagged() {
    val decl = SourceFile("ui/features/foo/FooHost.kt", "@Composable\nfun FooHost() { Text(\"hi\") }")
    val caller = SourceFile("ui/App.kt", "fun App() { FooHost() }")
    val declared = findHostScreenDeclarations(listOf(decl, caller))
    val evidence = listOf(decl, caller).map { it.copy(text = cleanForReferenceScan(it.text)) }
    assertTrue(findUnreferenced(declared, evidence, emptySet()).isEmpty())
  }

  @Test
  fun aMentionOnlyInACommentDoesNotCountAsWired() {
    // This is the real bug this guard exists for: CalendarImportHost.kt mentioned
    // "CalendarReviewHost.kt" in prose, with nothing ever actually calling it.
    val decl = SourceFile("ui/features/foo/FooHost.kt", "@Composable\nfun FooHost() {}")
    val commentOnly = SourceFile("ui/features/foo/BarHost.kt", "// mirrors FooHost, see WI-1\n@Composable\nfun BarHost() {}")
    val declared = findHostScreenDeclarations(listOf(decl, commentOnly))
    val evidence = listOf(decl, commentOnly).map { it.copy(text = cleanForReferenceScan(it.text)) }
    val flagged = findUnreferenced(declared, evidence, emptySet()).map { it.name }
    assertTrue("FooHost" in flagged, "a bare-word comment mention must not count as a real call")
  }

  @Test
  fun aMentionOnlyInAnUnusedImportDoesNotCountAsWired() {
    val decl = SourceFile("ui/features/foo/FooHost.kt", "@Composable\nfun FooHost() {}")
    val importOnly = SourceFile("ui/App.kt", "import com.sloopworks.dayfold.client.features.foo.FooHost\nfun App() {}")
    val declared = findHostScreenDeclarations(listOf(decl, importOnly))
    val evidence = listOf(decl, importOnly).map { it.copy(text = cleanForReferenceScan(it.text)) }
    assertEquals(1, findUnreferenced(declared, evidence, emptySet()).size)
  }

  @Test
  fun anAllowListedComposableIsNotFlaggedEvenIfUnreferenced() {
    val decl = SourceFile("ui/features/foo/FooHost.kt", "@Composable\nfun FooHost() {}")
    val declared = findHostScreenDeclarations(listOf(decl))
    val evidence = listOf(decl).map { it.copy(text = cleanForReferenceScan(it.text)) }
    assertTrue(findUnreferenced(declared, evidence, setOf("FooHost")).isEmpty())
  }

  @Test
  fun aNonComposableFunctionNamedHostIsIgnored() {
    val decl = SourceFile("ui/features/foo/FooHost.kt", "fun FooHost() {}")
    assertTrue(findHostScreenDeclarations(listOf(decl)).isEmpty())
  }

  @Test
  fun aLineCommentContainingAStrayBlockCommentOpenerDoesNotSwallowFollowingCode() {
    // Regression: AuthEngine.kt has `// no *Requested/*Failed noise` — the embedded "/*" in that
    // LINE comment must not be read as an unterminated block comment that deletes real code
    // (ProfileLoaded(profile)) up to the next unrelated "*/" later in the file.
    val text = "// no *Requested/*Failed noise\nfun x() { dispatch(ProfileLoaded(p)) }\n/* quiet */"
    assertTrue("ProfileLoaded" in stripComments(text))
  }

  @Test
  fun aPrivateComposableCalledOnlyByItsSiblingInTheSameFileIsNotFlagged() {
    // Matches CalendarSettingsHost.kt: a private *OnHost sub-screen switched in by its own
    // top-level Host via local state. Same-file wiring is still real wiring — only the OUTER
    // Host (never called from anywhere, not even its own file) should end up flagged.
    val file = SourceFile(
      "ui/features/foo/FooHost.kt",
      "@Composable\nfun FooHost() { if (on) BarHost() }\n@Composable\nprivate fun BarHost() {}",
    )
    val declared = findHostScreenDeclarations(listOf(file))
    val evidence = listOf(file).map { it.copy(text = cleanForReferenceScan(it.text)) }
    val flagged = findUnreferenced(declared, evidence, emptySet()).map { it.name }
    assertEquals(listOf("FooHost"), flagged)
  }

  @Test
  fun anActionNeverReferencedOutsideItsOwnFileIsFlagged() {
    val file = SourceFile("client/features/foo/FooActions.kt", "sealed interface FooAction : Action\ndata object OpenFoo : FooAction")
    val families = findActionFamilies(listOf(file))
    val declared = findActionImplementers(listOf(file), families)
    assertEquals(listOf(DeclaredSymbol("OpenFoo", file.path)), declared)
    val evidence = listOf(file).map { it.copy(text = cleanForReferenceScan(it.text)) }
    assertEquals(1, findUnreferenced(declared, evidence, emptySet()).size)
  }

  @Test
  fun aMultiLineDataClassActionIsFoundAndItsDispatchCounts() {
    val decl = SourceFile(
      "client/features/foo/FooActions.kt",
      "sealed interface FooAction : Action\n" +
        "data class OpenFoo(\n  val id: String,\n  val step: Int = 0,\n) : FooAction",
    )
    val caller = SourceFile("ui/FooHost.kt", "fun onTap() { store.dispatch(OpenFoo(id = \"x\")) }")
    val families = findActionFamilies(listOf(decl))
    val declared = findActionImplementers(listOf(decl), families)
    assertEquals(listOf(DeclaredSymbol("OpenFoo", decl.path)), declared)
    val evidence = listOf(decl, caller).map { it.copy(text = cleanForReferenceScan(it.text)) }
    assertTrue(findUnreferenced(declared, evidence, emptySet()).isEmpty())
  }

  @Test
  fun aReducerArmAloneDoesNotCountAsDispatchingTheAction() {
    // The exact shape of the bug WI-462 describes: the reducer had an arm for
    // OpenResponseSheet from day one — nothing in the UI ever called dispatch(OpenResponseSheet).
    val decl = SourceFile("client/features/foo/FooActions.kt", "sealed interface FooAction : Action\ndata object OpenFoo : FooAction")
    val reducerOnly = SourceFile("client/FooReducer.kt", "fun reduce(a: Action) = when (a) { is OpenFoo -> state.copy(open = true) }")
    val families = findActionFamilies(listOf(decl))
    val declared = findActionImplementers(listOf(decl), families)
    val nonReducerEvidence = listOf(decl, reducerOnly)
      .filterNot { REDUCER_FILE.containsMatchIn(it.path) }
      .map { it.copy(text = cleanForReferenceScan(it.text)) }
    assertEquals(1, findUnreferenced(declared, nonReducerEvidence, emptySet()).size)
  }

  @Test
  fun anActionConstructedInAHelperThatReturnsActionsCountsAsReferenced() {
    // Mirrors smartBriefingsPreviewActions(): actions built inside a plain (non-reducer,
    // non-"dispatch(") mapping function, dispatched later by its caller via a loop.
    val decl = SourceFile("client/features/foo/FooActions.kt", "sealed interface FooAction : Action\ndata object OpenFoo : FooAction")
    val mapper = SourceFile("ui/FooMapper.kt", "internal fun mapEvent(e: Event): List<Action> = listOf(OpenFoo)")
    val families = findActionFamilies(listOf(decl))
    val declared = findActionImplementers(listOf(decl), families)
    val evidence = listOf(decl, mapper).map { it.copy(text = cleanForReferenceScan(it.text)) }
    assertTrue(findUnreferenced(declared, evidence, emptySet()).isEmpty())
  }

  @Test
  fun aVisibilityModifierBeforeDataClassDoesNotHideTheImplementerFromDiscovery() {
    // Regression: `internal`/`private` (a common Kotlin idiom this repo already uses for DTOs)
    // before `data class`/`data object` used to make IMPLEMENTER_DECL_START fail to match at
    // all — the implementer wasn't flagged as unreferenced, it was never even discovered.
    val decl = SourceFile(
      "client/features/foo/FooActions.kt",
      "sealed interface FooAction : Action\ninternal data class OpenFoo(val id: String) : FooAction",
    )
    val families = findActionFamilies(listOf(decl))
    val declared = findActionImplementers(listOf(decl), families)
    assertEquals(listOf(DeclaredSymbol("OpenFoo", decl.path)), declared)
  }

  @Test
  fun anAnnotatedSingleLineDataClassImplementerIsDiscovered() {
    val decl = SourceFile(
      "client/features/foo/FooActions.kt",
      "sealed interface FooAction : Action\n@Serializable private data class OpenFoo(val id: String) : FooAction",
    )
    val families = findActionFamilies(listOf(decl))
    val declared = findActionImplementers(listOf(decl), families)
    assertEquals(listOf(DeclaredSymbol("OpenFoo", decl.path)), declared)
  }

  @Test
  fun aGenericActionImplementerIsDiscoveredAndItsSupertypeCorrectlyRead() {
    // Regression: `<T>` between the class name and `(` used to make skipBalancedParams think
    // there was no parameter list to skip, so the supertype regex ran against `<T>(val
    // payload: T) : FooAction` instead of `: FooAction` and never matched.
    val decl = SourceFile(
      "client/features/foo/FooActions.kt",
      "sealed interface FooAction : Action\ndata class OpenFoo<T>(val payload: T) : FooAction",
    )
    val families = findActionFamilies(listOf(decl))
    val declared = findActionImplementers(listOf(decl), families)
    assertEquals(listOf(DeclaredSymbol("OpenFoo", decl.path)), declared)
  }

  @Test
  fun routeAssignedViaEqualsIsRecognized() {
    val f = SourceFile("client/Reducer.kt", "state.copy(route = Route.Account)")
    assertEquals(setOf("Account"), findRoutesAssignedInProduction(listOf(f)))
  }

  @Test
  fun routeAssignedViaArrowIsRecognized() {
    val f = SourceFile("client/Reducer.kt", "val next = when (x) { else -> Route.CreateFamily }")
    assertEquals(setOf("CreateFamily"), findRoutesAssignedInProduction(listOf(f)))
  }

  @Test
  fun routeOnlyMatchedInAWhenBranchIsNotCountedAsAssigned() {
    // RouteHost's `when (route) { Route.Account -> ... }` branches on the route; it does not
    // produce one. A route reachable only from that branch is a route nothing ever navigates to.
    val f = SourceFile("ui/RouteHost.kt", "when (route) { Route.Account -> AccountScreen() }")
    assertTrue(findRoutesAssignedInProduction(listOf(f)).isEmpty())
  }

  @Test
  fun aComparisonAgainstARouteIsNotMistakenForAssigningIt() {
    // Regression: real code (AppShellSelectors.kt, BackNav.kt) reads
    // `state.navigation.route == Route.Feed`. The trailing "=" of "==" must not satisfy the
    // assignment regex — a route only ever COMPARED against, never produced, would otherwise
    // look reachable and the whole check would defeat its own purpose.
    val f = SourceFile("ui/AppShellSelectors.kt", "val onFeed = state.navigation.route == Route.Feed")
    assertTrue(findRoutesAssignedInProduction(listOf(f)).isEmpty())
  }

  @Test
  fun aNotEqualsComparisonAgainstARouteIsNotMistakenForAssigningIt() {
    val f = SourceFile("ui/BackNav.kt", "if (state.navigation.route != Route.SmartBriefings) return")
    assertTrue(findRoutesAssignedInProduction(listOf(f)).isEmpty())
  }

  @Test
  fun routeMembersParseFromTheEnumDeclaration() {
    val text = "enum class Route { Loading, SignIn, Account }"
    assertEquals(listOf("Loading", "SignIn", "Account"), findRouteMembers(text))
  }
}

// ===========================================================================
// Integration tests — run the same detectors against the real repo tree.
// ===========================================================================

class ReachabilityGuardTest {

  private val appsRoot = findAppsRoot()
  private val prodFiles = loadProductionSourceFiles(appsRoot)
  private val evidenceFiles = prodFiles.map { it.copy(text = cleanForReferenceScan(it.text)) }
  private val nonReducerEvidenceFiles = evidenceFiles.filterNot { REDUCER_FILE.containsMatchIn(it.path) }

  private fun assertNoViolations(violations: List<String>, header: String, fixHint: String) {
    assertTrue(
      violations.isEmpty(),
      buildString {
        appendLine(header)
        for (v in violations) {
          appendLine(" - $v")
          appendLine("   $fixHint")
        }
      },
    )
  }

  @Test
  fun everyHostAndScreenComposableIsCalledFromProductionCode() {
    val declared = findHostScreenDeclarations(prodFiles)
    assertTrue(declared.isNotEmpty(), "found zero *Host/*Screen composables under features/ — the scan is broken")
    val violations = findUnreferenced(declared, evidenceFiles, COMPOSABLE_ALLOW_LIST.keys)
    assertNoViolations(
      violations.map { "${it.name} (${it.file})" },
      "Unreachable composable(s) — declared under features/ with no real call site anywhere in " +
        "production (only tests, comments, or imports mention them):",
      "Fix: wire it from a real host/route call site, or add it to COMPOSABLE_ALLOW_LIST in " +
        "ReachabilityGuardTest.kt with a dated reason (see WI-462).",
    )
  }

  @Test
  fun everyActionFamilyMemberIsReferencedOutsideItsReducer() {
    val families = findActionFamilies(prodFiles)
    val declared = findActionImplementers(prodFiles, families)
    assertTrue(declared.isNotEmpty(), "found zero sealed-interface *Action members — the scan is broken")
    val violations = findUnreferenced(declared, nonReducerEvidenceFiles, ACTION_ALLOW_LIST.keys)
    assertNoViolations(
      violations.map { "${it.name} (${it.file})" },
      "Unreachable action(s) — declared as a *Action-family member but has no real call/dispatch " +
        "site in production outside its own declaration (a reducer's `is X ->` pattern-match, " +
        "where the family is reducer-driven, does not count as dispatching it):",
      "Fix: dispatch/construct it from a real UI/command call site, or add it to " +
        "ACTION_ALLOW_LIST in ReachabilityGuardTest.kt with a dated reason (see WI-462).",
    )
  }

  @Test
  fun everyRouteIsAssignedByProductionCodeNotOnlyMatchedOnInRouteHost() {
    val modelFile = prodFiles.singleOrNull { it.path.endsWith("client/Model.kt") }
      ?: error("could not locate client/.../Model.kt to read the Route enum from")
    val routes = findRouteMembers(modelFile.text)
    assertTrue(routes.size > 5, "found suspiciously few Route members (${routes.size}) — the scan is broken")
    val assigned = findRoutesAssignedInProduction(evidenceFiles)
    val violations = routes.filter { it !in assigned && it !in ROUTE_ALLOW_LIST.keys }
    assertNoViolations(
      violations.map { "Route.$it" },
      "Route(s) with no production code that ever assigns state.route to them — they exist in the " +
        "enum and RouteHost can render them, but nothing in production navigates there:",
      "Fix: add the reducer arm that produces this route from a real action, or add it to " +
        "ROUTE_ALLOW_LIST in ReachabilityGuardTest.kt with a dated reason (see WI-462).",
    )
  }
}
