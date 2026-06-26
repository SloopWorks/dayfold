package com.sloopworks.dayfold.client.fake

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

// Desktop MockEngine adapter (dev tool — desktop has no release variant, so the
// ktor-client-mock dep lives in desktopMain). Wraps the pure FakeBackend router
// into an HttpClient the transport clients (Sync/Hub/Auth) accept verbatim.
// The single-lambda MockEngine handler is reused for every request (safe for the
// poll loop + concurrent Sync/Hub/Auth calls because FakeBackend.handle is pure).

/** Build a fake-backend HttpClient for a scenario id, or null if unknown. */
fun fakeBackendClient(scenarioId: String): HttpClient? =
  FakeScenarios.byId(scenarioId)?.let { fakeHttpClient(FakeBackend(it.data)) }

/** Resolve a `fake://<id>` selector to a fake HttpClient (null for non-fake URLs). */
fun fakeClientForApi(api: String): HttpClient? =
  api.removePrefix("fake://").takeIf { api.startsWith("fake://") }?.let { fakeBackendClient(it) }

fun fakeHttpClient(backend: FakeBackend): HttpClient = HttpClient(MockEngine { request ->
  val res = backend.handle(request.method.value, request.url.encodedPath, request.url.parameters["user_code"])
  respond(res.json, HttpStatusCode.fromValue(res.status), headersOf(HttpHeaders.ContentType, "application/json"))
})
