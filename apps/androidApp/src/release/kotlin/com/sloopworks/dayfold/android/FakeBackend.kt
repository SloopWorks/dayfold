package com.sloopworks.dayfold.android

import com.sloopworks.debugdrawer.Backend
import io.ktor.client.HttpClient

// Release variant: inert mirror of the debug fake-backend accessors. No scenarios,
// no MockEngine, no ktor-client-mock dependency — release never serves fake data.
// (HttpClient is from ktor-client-core, which IS on the release classpath, so the
// return type is fine here.)

fun fakeBackends(): List<Backend> = emptyList()

@Suppress("UNUSED_PARAMETER")
fun fakeBackendClient(scenarioId: String): HttpClient? = null
