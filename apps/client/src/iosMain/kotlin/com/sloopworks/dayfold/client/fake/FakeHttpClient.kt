package com.sloopworks.dayfold.client.fake

import io.ktor.client.HttpClient

// The MockEngine implementation exists only in the simulator target; device/release artifacts
// get an inert actual and therefore carry no mock transport dependency.
expect fun fakeClientForApi(api: String): HttpClient?
