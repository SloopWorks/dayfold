package com.sloopworks.dayfold.client.fake

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

actual fun fakeClientForApi(api: String): HttpClient? =
  api.removePrefix("fake://").takeIf { api.startsWith("fake://") }?.let { scenarioId ->
    FakeScenarios.byId(scenarioId)?.let { scenario ->
      val backend = FakeBackend(scenario.data)
      HttpClient(MockEngine { request ->
        if (backend.data.latencyMs > 0) kotlinx.coroutines.delay(backend.data.latencyMs)
        val response = backend.handle(
          method = request.method.value,
          path = request.url.encodedPath,
          userCode = request.url.parameters["user_code"],
          body = (request.body as? TextContent)?.text,
        )
        respond(
          content = response.json,
          status = HttpStatusCode.fromValue(response.status),
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      })
    }
  }
