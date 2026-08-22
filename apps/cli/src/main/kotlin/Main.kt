package com.sloopworks.dayfold.cli

import com.sloopworks.dayfold.client.Ulid
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.json.*

// M0 CLI: the operator's (and Claude Code's) authoring side. JDK-only HTTP.
// Config from env (M0 household token; never a flag, never in the repo):
//   DAYFOLD_API   e.g. http://localhost:8787
//   FAMILY_ID      the provisioned family id
//   HOUSEHOLD_SECRET  the provisioned token (keychain/secret store)

private fun env(k: String): String =
  System.getenv(k) ?: run { System.err.println("missing env: $k"); exitProcess(2) }

private fun client() = HttpClient.newHttpClient()

private val J = Json { ignoreUnknownKeys = true }

/** Shared HTTP call; returns Pair<statusCode, body>. `postStatus`/`putStatus`/
 *  `getStatus`/`deleteStatus` below were four near-identical ~8-line functions
 *  differing only by method — collapsed by a repo-maintenance pass. */
private fun httpStatus(method: String, url: String, token: String?, body: String? = null): Pair<Int, String> {
  val b = HttpRequest.newBuilder(URI.create(url))
  if (token != null) b.header("authorization", "Bearer $token")
  when (method) {
    "GET" -> b.GET()
    "DELETE" -> b.DELETE()
    "POST" -> { b.header("content-type", "application/json"); b.POST(HttpRequest.BodyPublishers.ofString(body!!)) }
    "PUT" -> { b.header("content-type", "application/json"); b.PUT(HttpRequest.BodyPublishers.ofString(body!!)) }
    else -> error("unsupported method: $method")
  }
  val res = client().send(b.build(), HttpResponse.BodyHandlers.ofString())
  return Pair(res.statusCode(), res.body())
}

internal fun postStatus(url: String, body: String, token: String?): Pair<Int, String> = httpStatus("POST", url, token, body)
// postStatus/getStatus/putStatus/deleteStatus and refreshAccessToken are `internal` (not `private`)
// only because they are DEFAULT ARGUMENT values of the internal authed* helpers below —
// Kotlin requires a default expression to be at least as visible as its function. They are
// not otherwise part of any wider surface.
internal fun putStatus(url: String, body: String, token: String?): Pair<Int, String> = httpStatus("PUT", url, token, body)
internal fun getStatus(url: String, token: String?): Pair<Int, String> = httpStatus("GET", url, token)
internal fun deleteStatus(url: String, token: String?): Pair<Int, String> = httpStatus("DELETE", url, token)

private fun signout(c: Creds) {
  postStatus("${c.api}/auth/signout", "{}", c.accessToken)
}

/**
 * Refresh the access token via `/auth/refresh`, persist the rotated access+refresh
 * pair, and return the new access token — exits with actionable `dayfold login`
 * guidance on any failure. Shared by authedGet/authedDelete/push's 401-retry path
 * (was inlined three times, byte-for-byte, before this extraction).
 */
internal fun refreshAccessToken(store: Credentials, keychain: SecretStore?): String =
  store.withRefreshLock {
    val cur = loadCreds(store, keychain) ?: run { System.err.println("credentials removed — run: dayfold login"); exitProcess(1) }
    val (rc, rt) = postStatus("${cur.api}/auth/refresh", """{"refresh":"${cur.refreshToken}"}""", null)
    if (rc != 200) { System.err.println("session expired — run: dayfold login"); exitProcess(1) }
    val o = runCatching { J.parseToJsonElement(rt).jsonObject }.getOrNull()
    val newAccess = o?.get("access")?.jsonPrimitive?.contentOrNull
    val newRefresh = o?.get("refresh")?.jsonPrimitive?.contentOrNull
    if (newAccess == null || newRefresh == null) { System.err.println("session refresh failed — run: dayfold login"); exitProcess(1) }
    saveCreds(store, keychain, cur.copy(accessToken = newAccess, refreshToken = newRefresh))
    newAccess
  }

/**
 * Authed GET with one transparent refresh on 401 (device creds only; the legacy
 * env path has no refresh). Returns Pair<statusCode, body>.
 *
 * `transport` and `refresh` are injected (defaulting to the real ones) so the
 * retry rule is testable without a network or a keychain. This is the CLI's only
 * auth-orchestration logic and it had no direct coverage — see `AuthRetryTest`.
 */
internal fun authedGet(
  store: Credentials?, keychain: SecretStore?,
  api: String, token: String, refreshable: Creds?, path: String,
  transport: (url: String, token: String?) -> Pair<Int, String> = ::getStatus,
  refresh: (Credentials, SecretStore?) -> String = ::refreshAccessToken,
): Pair<Int, String> {
  val first = transport("$api$path", token)
  if (first.first != 401 || store == null || refreshable == null) return first
  return transport("$api$path", refresh(store, keychain))
}

/** Authed DELETE with one transparent refresh on 401 (mirrors authedGet). */
internal fun authedDelete(
  store: Credentials?, keychain: SecretStore?,
  api: String, token: String, refreshable: Creds?, path: String,
  transport: (url: String, token: String?) -> Pair<Int, String> = ::deleteStatus,
  refresh: (Credentials, SecretStore?) -> String = ::refreshAccessToken,
): Pair<Int, String> {
  val first = transport("$api$path", token)
  if (first.first != 401 || store == null || refreshable == null) return first
  return transport("$api$path", refresh(store, keychain))
}

/** Authed PUT with one transparent refresh on 401 (mirrors authedGet/authedDelete;
 *  was inlined in `push` before this extraction). */
internal fun authedPut(
  store: Credentials?, keychain: SecretStore?,
  api: String, token: String, refreshable: Creds?, path: String, requestBody: String,
  transport: (url: String, requestBody: String, token: String?) -> Pair<Int, String> = ::putStatus,
  refresh: (Credentials, SecretStore?) -> String = ::refreshAccessToken,
): Pair<Int, String> {
  val first = transport("$api$path", requestBody, token)
  if (first.first != 401 || store == null || refreshable == null) return first
  return transport("$api$path", requestBody, refresh(store, keychain))
}

/** Authed POST with one transparent refresh on 401 (mirrors authedGet/authedDelete).
 *  `archive` is the only caller: the route takes no body, so the empty object is a
 *  constant rather than a parameter — a body-carrying POST should not reuse this
 *  without adding the replay coverage `authedPut` has. */
internal fun authedPost(
  store: Credentials?, keychain: SecretStore?,
  api: String, token: String, refreshable: Creds?, path: String,
  transport: (url: String, requestBody: String, token: String?) -> Pair<Int, String> = ::postStatus,
  refresh: (Credentials, SecretStore?) -> String = ::refreshAccessToken,
): Pair<Int, String> {
  val first = transport("$api$path", "{}", token)
  if (first.first != 401 || store == null || refreshable == null) return first
  return transport("$api$path", "{}", refresh(store, keychain))
}

/** The build version embedded by Gradle (generateVersionResource) — what a
 *  brew-installed user reports in a bug. Falls back to "unknown" if absent. */
internal fun cliVersion(): String =
  {}.javaClass.getResourceAsStream("/dayfold-version.txt")?.readBytes()?.decodeToString()?.trim() ?: "unknown"

/** Shared "you're not set up" guidance — used by whoami's status line AND the pull/
 *  push commands (so a fresh user gets login guidance, not a cryptic `missing env`). */
internal const val NOT_SIGNED_IN_HINT =
  "not signed in — run: dayfold login (or set DAYFOLD_API + FAMILY_ID + HOUSEHOLD_SECRET)"

/** The `whoami` status line — pure, so it's testable. With neither a device login
 *  nor a legacy token, print actionable guidance instead of blank `family= api=`. */
internal fun whoamiStatus(signedInDevice: Boolean, hasToken: Boolean, family: String, api: String): String =
  if (!signedInDevice && !hasToken) NOT_SIGNED_IN_HINT
  else "family=$family api=$api (${if (signedInDevice) "device" else "legacy"})"

/** Pure: with neither a device login nor a legacy DAYFOLD_API env, the user hasn't
 *  set up auth at all → guide to login instead of a cryptic `missing env`. */
internal fun shouldGuideToLogin(signedInDevice: Boolean, hasLegacyApiEnv: Boolean): Boolean =
  !signedInDevice && !hasLegacyApiEnv

private fun requireAuthSetup(signedInDevice: Boolean) {
  if (shouldGuideToLogin(signedInDevice, System.getenv("DAYFOLD_API") != null)) {
    System.err.println(NOT_SIGNED_IN_HINT); exitProcess(2)
  }
}

/** Resolve (api, familyId, token) from a device login, else the legacy env vars —
 *  shared by `pull`/`delete` (was copy-pasted between the two; `push` keeps its own
 *  two-branch shape since its else-branch also re-resolves `secret` for the legacy PUT). */
private fun resolveAuth(creds: Creds?): Triple<String, String, String> =
  if (creds != null) Triple(creds.api, creds.familyId, creds.accessToken)
  else Triple(env("DAYFOLD_API"), env("FAMILY_ID"), env("HOUSEHOLD_SECRET"))

/** The OS-keychain handle + on-disk credentials — shared prologue for
 *  whoami/pull/delete/push (was `Credentials()` + `resolveKeychain()` + `loadCreds()`
 *  copy-pasted 4x). */
private class Session(val store: Credentials, val keychain: SecretStore?, val creds: Creds?)
private fun loadSession(): Session {
  val store = Credentials(); val keychain = resolveKeychain()
  return Session(store, keychain, loadCreds(store, keychain))
}

/**
 * Build the production-shaped `changeset diff` adapter around an injected GET.
 * The transport has no method argument and receives exactly one cards URL, so a
 * test can prove that the adapter cannot select refresh, PUT, DELETE, or apply.
 */
internal fun getOnlyRoutineCardReader(
  api: String,
  family: String,
  token: String,
  get: (url: String, token: String?) -> Pair<Int, String>,
): RoutineCardReader = RoutineCardReader {
  val (code, body) = get("$api/families/$family/cards", token)
  RoutineReadResponse(code, body)
}

/**
 * Production adapter for `changeset diff` without --current. Unlike normal CLI
 * reads, this safety-bounded path deliberately does not use `authedGet`: a 401 is
 * returned to the command and fails closed without POST /auth/refresh or local
 * credential rotation.
 */
private fun routineCardReader(): RoutineCardReader {
  val session = loadSession()
  requireAuthSetup(session.creds != null)
  val (api, family, token) = resolveAuth(session.creds)
  return getOnlyRoutineCardReader(api, family, token, ::getStatus)
}

private fun routineFile(file: String): String = try {
  Files.readString(Path.of(file))
} catch (e: Exception) {
  System.err.println(fileReadError(file, e)); exitProcess(2)
}

private fun emitRoutineResult(result: RoutineCommandResult) {
  if (result.stdout.isNotEmpty()) println(result.stdout)
  if (result.stderr.isNotEmpty()) System.err.println(result.stderr)
  if (result.exitCode != 0) exitProcess(result.exitCode)
}

/** A human-readable message for a failed payload-file read — pure, so it's tested.
 *  Keeps `push` from dumping a raw Java stack trace on a bad path. */
internal fun fileReadError(file: String, e: Exception): String = when (e) {
  is java.nio.file.NoSuchFileException -> "file not found: $file"
  is java.io.IOException -> "could not read $file: ${e.message ?: "I/O error"}"
  else -> "could not read $file"
}

fun main(args: Array<String>) {
  // Help routing first (ADR: complete per-command help). `help`/`-h`/`--help` at the front,
  // or `<command> --help`, or `... --json`, resolve to a HelpRequest → print to stdout, exit 0
  // (help is not an error). Falls through to the dispatch below for a normal invocation.
  helpInvocation(args)?.let { println(renderHelp(it)); return }

  when (args.getOrNull(0)) {
    "--version", "-v", "version" -> println("dayfold ${cliVersion()}")
    "update", "upgrade" -> runUpdate()         // ADR 0037: brew upgrade + version check

    "login" -> deviceLogin(
      api = System.getenv("DAYFOLD_API") ?: "https://family-ai-dashboard.vercel.app",
      allowEnvKey = hasFlag(args, "--allow-env-key"),
    )

    "logout" -> {
      val s = Credentials()
      s.load()?.let { runCatching { signout(it) } }
      deleteCreds(s, resolveKeychain())     // clear the file + the keychain refresh token
      println("logged out")
    }

    "whoami" -> {
      val session = loadSession()
      val creds = session.creds
      val dev = creds != null
      val api = creds?.api ?: System.getenv("DAYFOLD_API") ?: ""
      val fam = creds?.familyId ?: System.getenv("FAMILY_ID") ?: ""
      val tok = creds?.accessToken ?: System.getenv("HOUSEHOLD_SECRET") ?: ""
      println(whoamiStatus(dev, tok.isNotEmpty(), fam, api))
      // ADR 0029: show the credential's RESOLVED scope (server-side grant rows).
      if (api.isNotEmpty() && tok.isNotEmpty()) {
        val (code, body) = authedGet(session.store.takeIf { dev }, session.keychain, api, tok, creds, "/auth/whoami")
        if (code == 200) {
          val grants = runCatching { J.parseToJsonElement(body).jsonObject["grants"]?.jsonArray?.map { it.jsonPrimitive.content } }.getOrNull()
          if (grants != null) println("scope=${if (grants.isEmpty()) "(none)" else grants.joinToString(",")}")
        }
      }
    }

    // Local/read-only routine shadow tooling. Validation never constructs a
    // network reader. Diff is offline with --current; otherwise its injected
    // reader exposes only GET /cards. There is intentionally no apply command.
    "changeset" -> when (val invocation = parseChangesetInvocation(args)) {
      is ChangesetInvocation.Validate -> emitRoutineResult(
        validateChangesetCommand(
          routineFile(invocation.manifestFile),
          routineFile(invocation.changesetFile),
        ),
      )
      is ChangesetInvocation.Diff -> emitRoutineResult(
        diffChangesetCommand(
          manifestRaw = routineFile(invocation.manifestFile),
          changesetRaw = routineFile(invocation.changesetFile),
          currentRaw = invocation.currentFile?.let(::routineFile),
          readerFactory = if (invocation.currentFile == null) ({ routineCardReader() }) else null,
        ),
      )
      ChangesetInvocation.Invalid -> usage()
    }

    // dayfold pull [--hub <id>]  — read content back (proves the author→read loop).
    // No --hub: prints {"cards":[...],"hubs":[...]}. --hub: prints that hub's tree.
    "pull" -> {
      val session = loadSession()
      requireAuthSetup(session.creds != null)
      val (api, fam, tok) = resolveAuth(session.creds)
      val s = session.store.takeIf { session.creds != null }
      val hub = flagValue(args, "--hub")
      if (hub != null) {
        val (code, body) = authedGet(s, session.keychain, api, tok, session.creds, "/families/$fam/hubs/$hub/tree")
        if (code != 200) { System.err.println("pull failed ($code): $body"); exitProcess(1) }
        println(body)
      } else {
        val (cc, cards) = authedGet(s, session.keychain, api, tok, session.creds, "/families/$fam/cards")
        if (cc != 200) { System.err.println("pull cards failed ($cc): $cards"); exitProcess(1) }
        val (hc, hubs) = authedGet(s, session.keychain, api, tok, session.creds, "/families/$fam/hubs")
        if (hc != 200) { System.err.println("pull hubs failed ($hc): $hubs"); exitProcess(1) }
        println("""{"cards":$cards,"hubs":$hubs}""")
      }
      maybeNudgeUpdate()   // ADR 0037: throttled once/day update nudge (interactive only)
    }

    // dayfold delete <id> [--card|--block]  — remove a hub (default; cascades its
    // sections+blocks), a card, or a single block. There is no section delete route
    // (MVP); to drop a stray section, delete its hub and re-push the tree.
    "delete", "rm" -> {
      val id = targetId(args) ?: usage()
      val resource = deleteResource(args)
      val session = loadSession()
      requireAuthSetup(session.creds != null)
      val (api, fam, tok) = resolveAuth(session.creds)
      val (code, body) = authedDelete(session.store.takeIf { session.creds != null }, session.keychain, api, tok, session.creds, "/families/$fam/$resource/$id")
      if (code !in 200..299) { System.err.println("delete failed ($code): $body"); exitProcess(1) }
      println("deleted $resource/$id")
    }

    // dayfold archive <id>  — set a hub's status to `archived` (POST .../hubs/:id/archive).
    // NOT a delete: `delete` soft-deletes, while `archived` is a distinct Hub status in
    // the schema, so the two are not substitutes. Hubs only — cards and blocks have no
    // archive route. Named as a bare verb, mirroring `delete <id>`, because that is the
    // shape the CLI actually settled on (`pull --hub <id>`, `delete <id>` defaulting to
    // hubs); there is no `hub <verb>` namespace to join.
    "archive" -> {
      val id = targetId(args) ?: usage()
      val session = loadSession()
      requireAuthSetup(session.creds != null)
      val (api, fam, tok) = resolveAuth(session.creds)
      val (code, body) = authedPost(session.store.takeIf { session.creds != null }, session.keychain, api, tok, session.creds, "/families/$fam/hubs/$id/archive")
      if (code !in 200..299) { System.err.println("archive failed ($code): $body"); exitProcess(1) }
      println("archived hubs/$id")
    }

    // dayfold push <id> <file.json> [--hub|--section|--block]  — card (default) or hub tree.
    "push" -> {
      val pos = pushPositionals(args)
      val id = pos.getOrNull(0) ?: usage()
      val file = pos.getOrNull(1) ?: usage()
      val rawPayload = try { Files.readString(Path.of(file)) }
        catch (e: Exception) { System.err.println(fileReadError(file, e)); exitProcess(2) }
      // Author-side linkify (CL-LINK): wrap bare phone/email entities in every body_md
      // into explicit allowlisted links before storing (the server is content-blind,
      // ADR 0015). --no-linkify opts out; the result is the canonical stored body.
      val payload = if ("--no-linkify" in args) rawPayload else {
        val r = linkifyPayload(rawPayload)
        if (r.diffs.isNotEmpty()) {
          System.err.println("linkified ${r.diffs.size} body_md field(s):")
          r.diffs.forEach { (b, a) -> System.err.println("  - $b\n  + $a") }
        }
        if (r.maxBodyLen > BODY_MD_CAP) {
          System.err.println("a linkified body_md exceeds $BODY_MD_CAP chars — shorten"); exitProcess(1)
        }
        r.json
      }
      // --hub/--section/--block target the hub tree (PUT /hubs|sections|blocks/:id)
      // instead of a briefing card. The server is the authority for hub-tree shape
      // (no generated schema in the CLI yet), so the card --type validation below
      // applies to cards only.
      val resource = pushResource(args)
      // ADR 0038 — stamp a client-minted ULID `id` (+ `ord`) onto any checklist item
      // that lacks one, preserving ids from `pull` (idempotent re-push). Item ids must
      // exist before members can toggle (concurrent toggles need a stable per-item key)
      // and the server can't mint them at M1 (ciphertext). Hub-tree resources only.
      val stamped = stampChecklistIds(resource, payload) { Ulid.next() }
      // Fail fast with field errors before the server (which stays the authority).
      // Cards: opt-in typed validation via `--type` (against the generated schema).
      // Hub tree: always-on structural pre-check (cheap, no flag).
      val preErrors: List<String> =
        if (resource == "cards") flagValue(args, "--type")?.let { validateCard(it, withId(stamped, id)) } ?: emptyList()
        else validateHubTree(resource, stamped)
      if (preErrors.isNotEmpty()) {
        System.err.println("validation failed:\n  " + preErrors.joinToString("\n  "))
        exitProcess(1)
      }
      val session = loadSession()        // refresh token comes from the keychain
      val creds = session.creds
      requireAuthSetup(creds != null)
      // ADR 0064 pre-flight — consult the family's rules BEFORE writing. The server rejects a
      // muted subject anyway (409), but a rejection tells the caller "this failed"; the
      // pre-flight lets it say "skipped, the family asked me not to make these", which is the
      // difference between a defect and an expected outcome (ADR 0062). --no-preflight skips
      // the lookup for an offline/one-shot push.
      if (creds != null && "--no-preflight" !in args) {
        val (rc, rbody) = authedGet(
          session.store, session.keychain, creds.api, creds.accessToken, creds,
          "/families/${creds.familyId}/responses",
        )
        if (rc == 200) {
          val rules = parseResponses(rbody)
          val subjectRef = if (resource == "cards") "card:$id" else null
          if (subjectRef != null) {
            val op = CliChangesetOp(
              id = id, subjectRef = subjectRef,
              kind = jsonStringField(stamped, "kind"),
              source = jsonStringField(stamped, "source"),
            )
            val filtered = filterMutedOps(listOf(op), rules)
            if (filtered.skipped.isNotEmpty()) {
              val hit = rules.firstOrNull { it.id == filtered.skipped.first().ruleId }
              val why = hit?.label ?: hit?.subjectRef ?: "a family rule"
              println("skipped $resource/$id — the family muted \"$why\" (${filtered.skipped.first().reason})")
              return
            }
          }
        }
        // A failed lookup is NOT fatal: the write boundary still enforces the rule, so a
        // transient read error must not block authoring.
      }
      val (code, body) = if (creds != null) {
        authedPut(session.store, session.keychain, creds.api, creds.accessToken, creds, "/families/${creds.familyId}/$resource/$id", stamped)
      } else {
        // legacy env path (unchanged)
        val api = env("DAYFOLD_API"); val fam = env("FAMILY_ID"); val secret = env("HOUSEHOLD_SECRET")
        putStatus("$api/families/$fam/$resource/$id", stamped, secret)
      }
      println("push $resource/$id -> $code")
      if (code != 200) { System.err.println(body); exitProcess(1) }
      maybeNudgeUpdate()   // ADR 0037: throttled once/day update nudge (interactive only)
    }

    // dayfold responses [--json]  — the family's mute rules + done records (ADR 0064).
    //
    // Read this BEFORE authoring. The server enforces suppression by ID and never reads a
    // label, so a rejected push tells an agent only "that exact key is muted". The labels are
    // what let it avoid writing something SIMILAR in the first place, which is the actual ask:
    // stop producing this kind of content, not just this one card.
    "responses" -> {
      val session = loadSession()
      requireAuthSetup(session.creds != null)
      val (api, fam, tok) = resolveAuth(session.creds)
      val s = session.store.takeIf { session.creds != null }
      val (code, body) = authedGet(s, session.keychain, api, tok, session.creds, "/families/$fam/responses")
      if (code != 200) { System.err.println("responses failed ($code): $body"); exitProcess(1) }
      if ("--json" in args) {
        println(body)
      } else {
        val rules = parseResponses(body)
        val hidden = Regex("\"personal_rules_not_visible\"\\s*:\\s*(\\d+)")
          .find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        println(renderResponsesBrief(rules, hidden))
      }
    }

    // dayfold template <type>  — print a starter card OR hub-tree body (hub/section/block).
    "template" -> {
      val t = args.getOrNull(1) ?: usage()
      if (t !in TEMPLATE_KINDS) {
        System.err.println("unknown type: $t (one of: ${TEMPLATE_KINDS.joinToString()})"); exitProcess(2)
      }
      val tpl = {}.javaClass.getResourceAsStream("/templates/$t.json")
        ?: run { System.err.println("template missing for $t"); exitProcess(1) }
      print(tpl.readBytes().decodeToString())
    }

    else -> usage()
  }
}

private val CONTENT_TYPES = listOf("file", "link", "invite", "contact", "geo", "email")
// card payload types + the hub-tree bodies (push with --hub/--section/--block).
// "timeline" is a hub body carrying a Hub.timeline (ADR 0045) — push with --hub.
val TEMPLATE_KINDS = CONTENT_TYPES + listOf("hub", "section", "block", "timeline")

/** The content resource `push` targets — PUT /families/:fid/<resource>/:id.
 *  --hub | --section | --block author a hub tree; default is a briefing card.
 *  (section bodies carry `hubId`, block bodies carry `sectionId` — server-validated.) */
fun pushResource(args: Array<String>): String = when {
  hasFlag(args, "--hub") -> "hubs"
  hasFlag(args, "--section") -> "sections"
  hasFlag(args, "--block") -> "blocks"
  else -> "cards"
}

/** `dayfold delete <id> [--card|--block]` — a hub (default; cascades sections+blocks), a card, or a block. */
internal fun deleteResource(args: Array<String>): String = when {
  hasFlag(args, "--card") -> "cards"
  hasFlag(args, "--block") -> "blocks"
  else -> "hubs"
}

/** The target id of a single-id verb (`delete`, `archive`) = the first NON-FLAG positional
 *  after the subcommand, so `--card` can come before OR after the id (a flag is never
 *  mistaken for the id — `delete --card x` deletes x, not "--card"). null when no id was
 *  given → usage. Named for the shape, not one verb, since `archive` reuses it. */
internal fun targetId(args: Array<String>): String? = args.drop(1).firstOrNull { !it.startsWith("--") }

/** push positionals [id, file] = the args after the subcommand minus flags, so the flags
 *  can come before/after/between the id+file (mirrors pushResource — a `--hub` is never
 *  mistaken for the id). `--type` is the one value-flag, so skip it AND its value; the
 *  rest (--hub/--section/--block/--card) are bare. */
internal fun pushPositionals(args: Array<String>): List<String> {
  val out = mutableListOf<String>()
  var i = 1 // skip the subcommand token
  while (i < args.size) {
    val a = args[i]
    when {
      a == "--type" -> i += 2          // value-flag: skip the flag + its value
      a.startsWith("--") -> i += 1     // bare flag
      else -> { out.add(a); i += 1 }
    }
  }
  return out
}

/** Value following a `--flag` token (position-agnostic), or null. */
private fun flagValue(args: Array<String>, flag: String): String? {
  val i = args.indexOf(flag)
  return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

/** Whether a bare `--flag` token is present. */
private fun hasFlag(args: Array<String>, flag: String): Boolean = args.contains(flag)

/**
 * The keychain to store a fresh login's refresh token in. Keychain when one
 * exists; otherwise the plaintext-file fallback is allowed only behind
 * `--allow-env-key` (+ a loud warning). With neither, refuse — don't silently
 * write a 45-day secret to disk.
 */
private fun keychainForLogin(allowEnvKey: Boolean): SecretStore? {
  resolveKeychain()?.let { return it }
  if (allowEnvKey) {
    System.err.println("warning: no OS keychain found — storing the 45-day refresh token in plaintext ~/.config/dayfold/credentials.json (0600).")
    return null
  }
  System.err.println(
    "error: no OS keychain found to store the refresh token securely.\n" +
      "  Re-run with --allow-env-key to keep it in a 0600 file (headless/CI),\n" +
      "  or sign in on a machine with macOS Keychain / libsecret.",
  )
  exitProcess(2)
}

private fun deviceLogin(api: String, allowEnvKey: Boolean) {
  // Resolve the secret store up front so we fail BEFORE the device dance if a
  // headless host can't store the token and --allow-env-key wasn't passed.
  val keychain = keychainForLogin(allowEnvKey)
  // Start the grant. A non-200 (e.g. a 500 because the server's AUTH_* env is
  // unset) returns plain text, not JSON — surface it instead of crashing on a parse.
  val (ac, atxt) = postStatus("$api/device/authorize", "{}", null)
  if (ac != 200) {
    System.err.println("login failed: the server couldn't start a device login (HTTP $ac).")
    System.err.println("  " + atxt.trim().take(300).ifEmpty { "(no response body)" })
    exitProcess(1)
  }
  val auth = runCatching { J.parseToJsonElement(atxt).jsonObject }.getOrNull()
  val deviceCode = auth?.get("device_code")?.jsonPrimitive?.contentOrNull
  val userCode = auth?.get("user_code")?.jsonPrimitive?.contentOrNull
  val verifyUri = auth?.get("verification_uri")?.jsonPrimitive?.contentOrNull
  if (deviceCode == null || userCode == null || verifyUri == null) {
    System.err.println("login failed: unexpected response from /device/authorize."); exitProcess(1)
  }
  var interval = auth["interval"]?.jsonPrimitive?.intOrNull ?: 5
  val expiresIn = auth["expires_in"]?.jsonPrimitive?.intOrNull ?: 600
  val verifyComplete = auth["verification_uri_complete"]?.jsonPrimitive?.contentOrNull ?: "$verifyUri?user_code=$userCode"
  // [S3] Scannable QR when interactive; the text below is always printed so
  // SSH/CI/non-UTF-8 terminals (System.console()==null) still work.
  if (System.console() != null) runCatching { print("\n" + Qr.render(verifyComplete) + "\n") }
  println("To authorize this CLI, an owner must approve code:\n\n    $userCode\n\nScan the QR above, or go to $verifyUri")
  val deadline = System.currentTimeMillis() + (expiresIn + 30) * 1000L
  while (System.currentTimeMillis() < deadline) {
    Thread.sleep(interval * 1000L)
    val body = """{"grant_type":"urn:ietf:params:oauth:grant-type:device_code","device_code":"$deviceCode"}"""
    val (code, txt) = postStatus("$api/device/token", body, null)
    val obj = runCatching { J.parseToJsonElement(txt).jsonObject }.getOrNull()

    if (code == 200 && obj != null) {
      val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
      val refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
      if (accessToken == null || refreshToken == null) {
        System.err.println("login failed: unexpected response from /device/token."); exitProcess(1)
      }
      // Fetch familyId from /auth/whoami so push needs no env.
      val (wc, wt) = getStatus("$api/auth/whoami", accessToken)
      val familyId = if (wc == 200) runCatching { J.parseToJsonElement(wt).jsonObject["family_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } }.getOrNull() else null
      if (familyId.isNullOrEmpty()) {
        System.err.println("login succeeded but could not resolve a family — the approving owner needs a family set up first.")
        exitProcess(1)
      }
      saveCreds(Credentials(), keychain, Creds(api = api, accessToken = accessToken, refreshToken = refreshToken, familyId = familyId, obtainedAt = java.time.Instant.now().toString()))
      println("logged in")
      return
    }

    // RFC 8628 polling. A non-JSON body / transient 5xx is treated as a hiccup —
    // keep polling until the deadline rather than aborting the whole login.
    if (obj == null) {
      if (code in 500..599) continue
      System.err.println("login failed: unexpected response from /device/token (HTTP $code)."); exitProcess(1)
    }
    when (obj["error"]?.jsonPrimitive?.contentOrNull) {
      "authorization_pending" -> {}                       // owner hasn't approved yet
      "slow_down" -> interval += 5
      "access_denied" -> { System.err.println("login denied: the owner declined this device."); exitProcess(1) }
      "expired_token" -> { System.err.println("login expired: the code timed out — run `dayfold login` again."); exitProcess(1) }
      else -> { System.err.println("login failed: ${obj["error"]?.jsonPrimitive?.contentOrNull ?: "server error (HTTP $code)"}"); exitProcess(1) }
    }
  }
  System.err.println("login timed out — run `dayfold login` to try again."); exitProcess(1)
}

// The top-level index, rendered from the command registry (Help.kt) — the single source of
// truth for the index, per-command `--help`, and `--json`. `usage()` prints this on misuse.
internal val USAGE: String get() = renderIndex(helpModel())

// Misuse → usage to stderr, exit 2. Explicit `help` prints to stdout + exits 0 (help
// is not an error) — see the dispatch in main().
private fun usage(): Nothing {
  System.err.println(USAGE)
  exitProcess(2)
}
