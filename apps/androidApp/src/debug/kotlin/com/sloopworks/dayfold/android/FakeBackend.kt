package com.sloopworks.dayfold.android

import com.sloopworks.dayfold.client.fake.FakeBackend
import com.sloopworks.dayfold.client.fake.FakeScenarios
import com.sloopworks.debugdrawer.Backend
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

// Debug variant only: the fake-backend MockEngine adapter + the scenario list for the
// debug-drawer Backend switcher. Mirrored by an inert src/release copy (returns
// null/empty), same pattern as DebugDrawerPlugins.kt — so MainActivity (src/main)
// compiles against either variant and ktor-client-mock never reaches release.

/** The scenario entries shown in the Backend switcher (debug only). */
fun fakeBackends(): List<Backend> =
  FakeScenarios.all.map { Backend(it.id, it.label, "fake://${it.id}") }

/** Build a fake-backend HttpClient for a scenario id, or null if the id is unknown. */
fun fakeBackendClient(scenarioId: String): HttpClient? =
  FakeScenarios.byId(scenarioId)?.let { scenario ->
    val backend = FakeBackend(scenario.data)
    HttpClient(MockEngine { request ->
      val res = backend.handle(request.method.value, request.url.encodedPath, request.url.parameters["user_code"])
      respond(res.json, HttpStatusCode.fromValue(res.status), headersOf(HttpHeaders.ContentType, "application/json"))
    })
  }
