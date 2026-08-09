package com.sloopworks.dayfold.client.cards

import com.sloopworks.dayfold.client.ALLOWED_SCHEMES
import com.sloopworks.dayfold.client.schemeOf

// CL-PLAT — the platform effect layer (epic "Platform shims"). commonMain owns
// the EXPECT + the pure, vetted URI builder; each actual just opens what this
// returns. Mirrors the DriverFactory expect/actual-with-Context precedent: the
// Android actual takes a Context ctor; common code never constructs it (each
// shell does). Read-only (ADR 0020) — every effect is an OS handoff.
expect class PlatformActions {
  /** Perform an [CardAction] as an OS handoff. No-op for OpenDetail (in-app nav,
   *  CL-6) and for any action that yields no vetted URI (fail-safe). */
  fun perform(action: CardAction)

  /** Open an already-built, vetted URI — the inline body-link tap path (a custom
   *  [androidx.compose.ui.platform.UriHandler] routes here). Re-vets the scheme
   *  allowlist as defense-in-depth; no-op if it doesn't vet. */
  fun openUri(uri: String)

  /** Open this app's OS settings screen (WI-447 "Allow in phone settings" — ADR 0063 §1 denied
   *  state). Mirrors AndroidPermissionControllers/IosPermissionControllers' openAppSettings; a
   *  no-op on desktop, which has no per-app OS permission screen. */
  fun openAppSettings()
}

/** The URI to open for a direct inline-link tap, or null if its scheme isn't
 *  allowlisted. Same one-seam vetting as [cardActionUri], for already-built URIs. */
fun vettedOpenUri(uri: String): String? =
  uri.trim().takeIf { schemeOf(it) in ALLOWED_SCHEMES }

/**
 * Pure: the VETTED URI a [CardAction] opens, or null if it isn't URI-openable or
 * fails vetting. Centralizes scheme-allowlisting at ONE seam (shared with the
 * body-link renderer via [com.sloopworks.dayfold.client.ALLOWED_SCHEMES]) so the
 * composables never construct arbitrary intents. Copy/Share are handled directly
 * by the actual (not URIs); OpenDetail is in-app nav (CL-6).
 */
fun cardActionUri(action: CardAction): String? = when (action) {
  is CardAction.OpenUrl -> action.url.trim().takeIf { schemeOf(it) == "https" } // links are https-only
  is CardAction.Call -> sanitizePhone(action.number)?.let { "tel:$it" }
  is CardAction.Message -> sanitizePhone(action.number)?.let { "sms:$it" } // no ?body — number only
  is CardAction.Email -> vetMailto(action.mailto)
  is CardAction.Navigate -> action.query.takeIf { it.isNotBlank() }?.let { "geo:0,0?q=${percentEncode(it)}" }
  // Respond opens the ADR 0064 sheet — in-app nav, never an OS handoff.
  is CardAction.Copy, is CardAction.Share, is CardAction.OpenDetail, is CardAction.OpenHub, is CardAction.Respond -> null  // in-app / handled elsewhere
}
  // defense-in-depth: never hand back a non-allowlisted scheme.
  ?.takeIf { schemeOf(it) in ALLOWED_SCHEMES }

// vetMailto / sanitizePhone / percentEncode now live in the shared linkrules source
// (Vetting.kt, same package) — one copy, reused by cardActionUri/vettedOpenUri here
// AND by the author-side linkifier, so production and render/open vetting can't drift.
