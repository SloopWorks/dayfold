package com.sloopworks.dayfold.client

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.HttpClient
import io.ktor.client.plugins.UserAgent

private var configured = false

// Wikimedia (the ADR 0036 Phase-1 allowlist host, upload.wikimedia.org) returns 403
// to requests whose User-Agent is a bare library default like `okhttp/4.x` — which is
// exactly what the Android ktor engine sends by default, so every hero/thumbnail/avatar
// silently fell back to its icon/initials tile. A descriptive UA (app + contact, per
// Wikimedia's UA policy) is accepted. One client, reused for all image loads.
private val imageHttpClient: HttpClient = HttpClient {
  install(UserAgent) { agent = "Dayfold/0.1 (family dashboard; +https://github.com/SloopWorks/dayfold)" }
}

/**
 * ADR 0036 — one-time Coil setup. Installs the Ktor network fetcher (resolves the
 * platform ktor engine already on the classpath: cio desktop / okhttp android /
 * darwin iOS) with a descriptive User-Agent (Wikimedia 403s default library UAs), and
 * enables crossfade. Idempotent (SingletonImageLoader.setSafe only applies if unset).
 * Every image URL still passes MediaValidation before Coil sees it — the loader is the
 * transport, not the gate.
 */
fun setupImageLoader() {
  if (configured) return
  configured = true
  SingletonImageLoader.setSafe { ctx: PlatformContext ->
    ImageLoader.Builder(ctx)
      .components { add(KtorNetworkFetcherFactory(httpClient = { imageHttpClient })) }
      .crossfade(true)
      .build()
  }
}
