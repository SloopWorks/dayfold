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

/** POST with optional bearer token; returns response body string. */
private fun post(url: String, body: String, token: String?): String {
  val b = HttpRequest.newBuilder(URI.create(url))
    .header("content-type", "application/json")
    .apply { if (token != null) header("authorization", "Bearer $token") }
    .POST(HttpRequest.BodyPublishers.ofString(body))
    .build()
  return client().send(b, HttpResponse.BodyHandlers.ofString()).body()
}

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

fun main(args: Array<String>) {
  when (args.getOrNull(0)) {
    "login" -> deviceLogin(api = System.getenv("DAYFOLD_API") ?: "https://family-ai-dashboard.vercel.app")

    "logout" -> {
      val s = Credentials()
      s.load()?.let { runCatching { signout(it) } }
      s.delete()
      println("logged out")
    }

    "whoami" -> {
      val c = Credentials().load()
      if (c != null) println("family=${c.familyId} api=${c.api} (device)")
      else println("family=${System.getenv("FAMILY_ID")} api=${System.getenv("DAYFOLD_API")} (legacy)")
    }

    // dayfold push <cardId> <file.json>  — PUT a briefing card (M0 feed).
    "push" -> {
      val id = args.getOrNull(1) ?: usage()
      val file = args.getOrNull(2) ?: usage()
      val payload = Files.readString(Path.of(file))
      // CL-3: opt-in local typed validation (`--type <t>`) — fail fast with field
      // errors before the server, against the generated schema types. Server stays
      // the authority. Flags come AFTER the positional <cardId> <file>.
      flagValue(args, "--type")?.let { t ->
        val errs = validateCard(t, withId(payload, id))
        if (errs.isNotEmpty()) {
          System.err.println("validation failed:\n  " + errs.joinToString("\n  "))
          exitProcess(1)
        }
      }
      val store = Credentials()
      val creds = store.load()
      if (creds != null) {
        var access = creds.accessToken
        var (code, body) = putStatus("${creds.api}/families/${creds.familyId}/cards/$id", payload, access)
        if (code == 401) {
          access = store.withRefreshLock {
            val cur = store.load() ?: run { System.err.println("credentials removed — run: dayfold login"); exitProcess(1) }
            val (rc, rt) = postStatus("${cur.api}/auth/refresh", """{"refresh":"${cur.refreshToken}"}""", null)
            if (rc != 200) { System.err.println("session expired — run: dayfold login"); exitProcess(1) }
            val o = J.parseToJsonElement(rt).jsonObject
            val newAccess = o["access"]!!.jsonPrimitive.content
            store.save(cur.copy(accessToken = newAccess, refreshToken = o["refresh"]!!.jsonPrimitive.content))
            newAccess
          }
          val retry = putStatus("${creds.api}/families/${creds.familyId}/cards/$id", payload, access)
          code = retry.first; body = retry.second
        }
        println("push $id -> $code")
        if (code != 200) { System.err.println(body); exitProcess(1) }
      } else {
        // legacy env path (unchanged)
        val api = env("DAYFOLD_API"); val fam = env("FAMILY_ID"); val secret = env("HOUSEHOLD_SECRET")
        val (code, body) = putStatus("$api/families/$fam/cards/$id", payload, secret)
        println("push $id -> $code")
        if (code != 200) { System.err.println(body); exitProcess(1) }
      }
    }

    // dayfold template <type>  — print a valid starter card for the type.
    "template" -> {
      val t = args.getOrNull(1) ?: usage()
      if (t !in CONTENT_TYPES) {
        System.err.println("unknown type: $t (one of: ${CONTENT_TYPES.joinToString()})"); exitProcess(2)
      }
      val tpl = {}.javaClass.getResourceAsStream("/templates/$t.json")
        ?: run { System.err.println("template missing for $t"); exitProcess(1) }
      print(tpl.readBytes().decodeToString())
    }

    else -> usage()
  }
}

private val CONTENT_TYPES = listOf("file", "link", "invite", "contact", "geo", "email")

/** Value following a `--flag` token (position-agnostic), or null. */
private fun flagValue(args: Array<String>, flag: String): String? {
  val i = args.indexOf(flag)
  return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun deviceLogin(api: String) {
  val auth = J.parseToJsonElement(post("$api/device/authorize", "{}", null)).jsonObject
  val deviceCode = auth["device_code"]!!.jsonPrimitive.content
  var interval = auth["interval"]!!.jsonPrimitive.int
  val expiresIn = auth["expires_in"]!!.jsonPrimitive.int
  println("To authorize this CLI, an owner must approve code:\n\n    ${auth["user_code"]!!.jsonPrimitive.content}\n\nat ${auth["verification_uri"]!!.jsonPrimitive.content}")
  val deadline = System.currentTimeMillis() + (expiresIn + 30) * 1000L
  while (System.currentTimeMillis() < deadline) {
    Thread.sleep(interval * 1000L)
    val body = """{"grant_type":"urn:ietf:params:oauth:grant-type:device_code","device_code":"$deviceCode"}"""
    val (code, txt) = postStatus("$api/device/token", body, null)
    val obj = J.parseToJsonElement(txt).jsonObject
    if (code == 200) {
      val accessToken = obj["access_token"]!!.jsonPrimitive.content
      val refreshToken = obj["refresh_token"]!!.jsonPrimitive.content
      // Fetch familyId from /auth/whoami so push needs no env
      val (wc, wt) = getStatus("$api/auth/whoami", accessToken)
      val familyId = if (wc == 200) runCatching { J.parseToJsonElement(wt).jsonObject["family_id"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } }.getOrNull() else null
      if (familyId.isNullOrEmpty()) {
        System.err.println("login succeeded but could not resolve family — is the approving owner's family set up? run: dayfold login")
        exitProcess(1)
      }
      Credentials().save(Creds(api = api, accessToken = accessToken, refreshToken = refreshToken, familyId = familyId, obtainedAt = java.time.Instant.now().toString()))
      println("logged in")
      return
    }
    when (obj["error"]?.jsonPrimitive?.content) {
      "authorization_pending" -> {}
      "slow_down" -> interval += 5
      else -> { System.err.println("login failed: ${obj["error"]?.jsonPrimitive?.content}"); exitProcess(1) }
    }
  }
  System.err.println("login timed out"); exitProcess(1)
}

private fun usage(): Nothing {
  System.err.println(
    "usage: dayfold <command>\n" +
      "  login | logout | whoami\n" +
      "  push <cardId> <file.json> [--type file|link|invite|contact|geo|email]\n" +
      "        (--type runs local typed validation before the server)\n" +
      "  template <type>            print a valid starter card for the type",
  )
  exitProcess(2)
}
