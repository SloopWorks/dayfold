package com.sloopworks.dayfold.client

// AUTH-S6-D Phase 2 — deep-link payload parsing (platform-agnostic, pure).
// The terminal QR / App-Link / Universal-Link payload is `<api-origin>/device?
// user_code=WDJF-7K2P`; the in-app scanner also yields that URL. This extracts the
// `user_code` so the engine can drive the SAME enter-code → lookup → approve path.
//
// Robust by design (it's fed untrusted scans/links):
// - a URL with `?user_code=` → that value (case-insensitive key, stops at &/#/space);
// - a bare code (no scheme/path) → the code itself (tolerated per the design);
// - a URL WITHOUT a user_code (e.g. a stray `/device` link) → null, never a false
//   code minted from the URL text;
// - normalized to 8 alphanumerics; anything that isn't exactly 8 → null.
fun parseDeviceCode(input: String): String? {
  val raw = input.trim()
  val fromQuery = Regex("[?&]user_code=([^&#\\s]+)", RegexOption.IGNORE_CASE)
    .find(raw)?.groupValues?.get(1)
  val candidate = when {
    fromQuery != null -> fromQuery
    raw.contains("://") || raw.contains("/") -> return null  // URL w/o user_code → no code
    else -> raw                                              // bare code
  }
  val norm = normalizeDeviceCode(candidate)                  // upper + strip non-alnum, take 8
  return if (norm.length == 8) formatUserCode(norm) else null
}

// Invite deep-link (ADR 0048): the App-Link payload is `<api-origin>/invite/<token>`
// (token = base64url of 32 CSPRNG bytes, spec 05 §42). Extracts the token so the engine
// can redeem it via POST /invites:redeem. A URL WITHOUT `/invite/` → null (never a false
// token from URL text — e.g. a stray `/device` link); a bare token (no scheme/path) is
// tolerated; a trailing ?query / #frag is stripped.
fun parseInviteToken(input: String): String? {
  val raw = input.trim()
  val candidate = when {
    "/invite/" in raw -> raw.substringAfterLast("/invite/")
    raw.contains("://") || raw.contains("/") -> return null   // URL w/o /invite/ → no token
    else -> raw                                               // bare token
  }
  return candidate.substringBefore("?").substringBefore("#").trim().ifBlank { null }
}
