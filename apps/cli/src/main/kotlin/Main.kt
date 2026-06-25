package com.sloopworks.dayfold.cli

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

/** POST; returns Pair<statusCode, body>. */
private fun postStatus(url: String, body: String, token: String?): Pair<Int, String> {
  val b = HttpRequest.newBuilder(URI.create(url))
    .header("content-type", "application/json")
    .apply { if (token != null) header("authorization", "Bearer $token") }
    .POST(HttpRequest.BodyPublishers.ofString(body))
    .build()
  val res = client().send(b, HttpResponse.BodyHandlers.ofString())
  return Pair(res.statusCode(), res.body())
}

/** PUT; returns Pair<statusCode, body>. */
private fun putStatus(url: String, body: String, token: String?): Pair<Int, String> {
  val b = HttpRequest.newBuilder(URI.create(url))
    .header("content-type", "application/json")
    .apply { if (token != null) header("authorization", "Bearer $token") }
    .PUT(HttpRequest.BodyPublishers.ofString(body))
    .build()
  val res = client().send(b, HttpResponse.BodyHandlers.ofString())
  return Pair(res.statusCode(), res.body())
}

/** GET; returns Pair<statusCode, body>. */
private fun getStatus(url: String, token: String?): Pair<Int, String> {
  val b = HttpRequest.newBuilder(URI.create(url))
    .apply { if (token != null) header("authorization", "Bearer $token") }
    .GET()
    .build()
  val res = client().send(b, HttpResponse.BodyHandlers.ofString())
  return Pair(res.statusCode(), res.body())
}

private fun signout(c: Creds) {
  postStatus("${c.api}/auth/signout", "{}", c.accessToken)
}

/**
 * Authed GET with one transparent refresh on 401 (device creds only; the legacy
 * env path has no refresh). Returns Pair<statusCode, body>.
 */
private fun authedGet(
  store: Credentials?, keychain: SecretStore?,
  api: String, token: String, refreshable: Creds?, path: String,
): Pair<Int, String> {
  var (code, body) = getStatus("$api$path", token)
  if (code == 401 && store != null && refreshable != null) {
    val st = store
    val newAccess = st.withRefreshLock {
      val cur = loadCreds(st, keychain) ?: run { System.err.println("credentials removed — run: dayfold login"); exitProcess(1) }
      val (rc, rt) = postStatus("${cur.api}/auth/refresh", """{"refresh":"${cur.refreshToken}"}""", null)
      if (rc != 200) { System.err.println("session expired — run: dayfold login"); exitProcess(1) }
      val o = J.parseToJsonElement(rt).jsonObject
      val na = o["access"]!!.jsonPrimitive.content
      saveCreds(st, keychain, cur.copy(accessToken = na, refreshToken = o["refresh"]!!.jsonPrimitive.content))
      na
    }
    val retry = getStatus("$api$path", newAccess); code = retry.first; body = retry.second
  }
  return Pair(code, body)
}

/** The build version embedded by Gradle (generateVersionResource) — what a
 *  brew-installed user reports in a bug. Falls back to "unknown" if absent. */
internal fun cliVersion(): String =
  {}.javaClass.getResourceAsStream("/dayfold-version.txt")?.readBytes()?.decodeToString()?.trim() ?: "unknown"

/** The `whoami` status line — pure, so it's testable. With neither a device login
 *  nor a legacy token, print actionable guidance instead of blank `family= api=`. */
internal fun whoamiStatus(signedInDevice: Boolean, hasToken: Boolean, family: String, api: String): String =
  if (!signedInDevice && !hasToken)
    "not signed in — run: dayfold login (or set DAYFOLD_API + FAMILY_ID + HOUSEHOLD_SECRET)"
  else "family=$family api=$api (${if (signedInDevice) "device" else "legacy"})"

/** A human-readable message for a failed payload-file read — pure, so it's tested.
 *  Keeps `push` from dumping a raw Java stack trace on a bad path. */
internal fun fileReadError(file: String, e: Exception): String = when (e) {
  is java.nio.file.NoSuchFileException -> "file not found: $file"
  is java.io.IOException -> "could not read $file: ${e.message ?: "I/O error"}"
  else -> "could not read $file"
}

fun main(args: Array<String>) {
  when (args.getOrNull(0)) {
    "--version", "-v", "version" -> println("dayfold ${cliVersion()}")
    "help", "-h", "--help" -> println(USAGE)   // explicit help → stdout, exit 0 (not an error)

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
      val store = Credentials(); val keychain = resolveKeychain()
      val creds = loadCreds(store, keychain)
      val dev = creds != null
      val api = creds?.api ?: System.getenv("DAYFOLD_API") ?: ""
      val fam = creds?.familyId ?: System.getenv("FAMILY_ID") ?: ""
      val tok = creds?.accessToken ?: System.getenv("HOUSEHOLD_SECRET") ?: ""
      println(whoamiStatus(dev, tok.isNotEmpty(), fam, api))
      // ADR 0029: show the credential's RESOLVED scope (server-side grant rows).
      if (api.isNotEmpty() && tok.isNotEmpty()) {
        val (code, body) = authedGet(store.takeIf { dev }, keychain, api, tok, creds, "/auth/whoami")
        if (code == 200) {
          val grants = runCatching { J.parseToJsonElement(body).jsonObject["grants"]?.jsonArray?.map { it.jsonPrimitive.content } }.getOrNull()
          if (grants != null) println("scope=${if (grants.isEmpty()) "(none)" else grants.joinToString(",")}")
        }
      }
    }

    // dayfold pull [--hub <id>]  — read content back (proves the author→read loop).
    // No --hub: prints {"cards":[...],"hubs":[...]}. --hub: prints that hub's tree.
    "pull" -> {
      val store = Credentials(); val keychain = resolveKeychain()
      val creds = loadCreds(store, keychain)
      val (api, fam, tok) =
        if (creds != null) Triple(creds.api, creds.familyId, creds.accessToken)
        else Triple(env("DAYFOLD_API"), env("FAMILY_ID"), env("HOUSEHOLD_SECRET"))
      val s = store.takeIf { creds != null }
      val hub = flagValue(args, "--hub")
      if (hub != null) {
        val (code, body) = authedGet(s, keychain, api, tok, creds, "/families/$fam/hubs/$hub/tree")
        if (code != 200) { System.err.println("pull failed ($code): $body"); exitProcess(1) }
        println(body)
      } else {
        val (cc, cards) = authedGet(s, keychain, api, tok, creds, "/families/$fam/cards")
        if (cc != 200) { System.err.println("pull cards failed ($cc): $cards"); exitProcess(1) }
        val (hc, hubs) = authedGet(s, keychain, api, tok, creds, "/families/$fam/hubs")
        if (hc != 200) { System.err.println("pull hubs failed ($hc): $hubs"); exitProcess(1) }
        println("""{"cards":$cards,"hubs":$hubs}""")
      }
    }

    // dayfold push <id> <file.json> [--hub|--section|--block]  — card (default) or hub tree.
    "push" -> {
      val id = args.getOrNull(1) ?: usage()
      val file = args.getOrNull(2) ?: usage()
      val payload = try { Files.readString(Path.of(file)) }
        catch (e: Exception) { System.err.println(fileReadError(file, e)); exitProcess(2) }
      // --hub/--section/--block target the hub tree (PUT /hubs|sections|blocks/:id)
      // instead of a briefing card. The server is the authority for hub-tree shape
      // (no generated schema in the CLI yet), so the card --type validation below
      // applies to cards only.
      val resource = pushResource(args)
      // Fail fast with field errors before the server (which stays the authority).
      // Cards: opt-in typed validation via `--type` (against the generated schema).
      // Hub tree: always-on structural pre-check (cheap, no flag).
      val preErrors: List<String> =
        if (resource == "cards") flagValue(args, "--type")?.let { validateCard(it, withId(payload, id)) } ?: emptyList()
        else validateHubTree(resource, payload)
      if (preErrors.isNotEmpty()) {
        System.err.println("validation failed:\n  " + preErrors.joinToString("\n  "))
        exitProcess(1)
      }
      val store = Credentials()
      val keychain = resolveKeychain()
      val creds = loadCreds(store, keychain)        // refresh token comes from the keychain
      if (creds != null) {
        var access = creds.accessToken
        var (code, body) = putStatus("${creds.api}/families/${creds.familyId}/$resource/$id", payload, access)
        if (code == 401) {
          access = store.withRefreshLock {
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
          val retry = putStatus("${creds.api}/families/${creds.familyId}/$resource/$id", payload, access)
          code = retry.first; body = retry.second
        }
        println("push $resource/$id -> $code")
        if (code != 200) { System.err.println(body); exitProcess(1) }
      } else {
        // legacy env path (unchanged)
        val api = env("DAYFOLD_API"); val fam = env("FAMILY_ID"); val secret = env("HOUSEHOLD_SECRET")
        val (code, body) = putStatus("$api/families/$fam/$resource/$id", payload, secret)
        println("push $resource/$id -> $code")
        if (code != 200) { System.err.println(body); exitProcess(1) }
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
val TEMPLATE_KINDS = CONTENT_TYPES + listOf("hub", "section", "block")

/** The content resource `push` targets — PUT /families/:fid/<resource>/:id.
 *  --hub | --section | --block author a hub tree; default is a briefing card.
 *  (section bodies carry `hubId`, block bodies carry `sectionId` — server-validated.) */
fun pushResource(args: Array<String>): String = when {
  hasFlag(args, "--hub") -> "hubs"
  hasFlag(args, "--section") -> "sections"
  hasFlag(args, "--block") -> "blocks"
  else -> "cards"
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

internal val USAGE =
  "usage: dayfold <command>\n" +
    "  login [--allow-env-key] | logout | whoami\n" +
    "        (refresh token is stored in the OS keychain; --allow-env-key permits\n" +
    "         a 0600-file fallback on hosts without a keychain — headless/CI)\n" +
    "  push <id> <file.json> [--hub|--section|--block] [--type file|link|...]\n" +
    "        (default: a briefing card; --hub/--section/--block author a hub tree.\n" +
    "         --type runs local typed card validation before the server)\n" +
    "  pull [--hub <id>]          read content back (cards+hubs, or one hub tree)\n" +
    "  template <type>            starter body: a card type, or hub|section|block\n" +
    "  version | --version       print the CLI version\n" +
    "  help | -h | --help         print this usage"

// Misuse → usage to stderr, exit 2. Explicit `help` prints to stdout + exits 0 (help
// is not an error) — see the dispatch in main().
private fun usage(): Nothing {
  System.err.println(USAGE)
  exitProcess(2)
}
