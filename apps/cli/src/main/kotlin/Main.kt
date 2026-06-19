package com.familyai.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

// M0 CLI: the operator's (and Claude Code's) authoring side. JDK-only HTTP.
// Config from env (M0 household token; never a flag, never in the repo):
//   FAMILYAI_API   e.g. http://localhost:8787
//   FAMILY_ID      the provisioned family id
//   HOUSEHOLD_SECRET  the provisioned token (keychain/secret store)

private fun env(k: String): String =
  System.getenv(k) ?: run { System.err.println("missing env: $k"); exitProcess(2) }

private fun client() = HttpClient.newHttpClient()

fun main(args: Array<String>) {
  when (args.getOrNull(0)) {
    "whoami" -> println("family=${env("FAMILY_ID")} api=${env("FAMILYAI_API")}")

    // familyai push <cardId> <file.json>  — PUT a briefing card (M0 feed).
    "push" -> {
      val id = args.getOrNull(1) ?: usage()
      val file = args.getOrNull(2) ?: usage()
      val body = Files.readString(Path.of(file))
      val api = env("FAMILYAI_API"); val fam = env("FAMILY_ID"); val secret = env("HOUSEHOLD_SECRET")
      val req = HttpRequest.newBuilder(URI.create("$api/families/$fam/cards/$id"))
        .header("authorization", "Bearer $secret")
        .header("content-type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val res = client().send(req, HttpResponse.BodyHandlers.ofString())
      println("push $id -> ${res.statusCode()}")
      if (res.statusCode() != 200) { System.err.println(res.body()); exitProcess(1) }
    }

    else -> usage()
  }
}

private fun usage(): Nothing {
  System.err.println("usage: familyai <whoami | push <cardId> <file.json>>")
  exitProcess(2)
}
