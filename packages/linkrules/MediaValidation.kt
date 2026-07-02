package com.sloopworks.dayfold.client

// ADR 0036 — SHARED (packages/linkrules, srcDir'd into client commonMain AND the CLI)
// hardened validation for author-supplied image enrichment. MUST stay byte-for-byte in
// lock-step with the server (apps/api/src/media-validation.ts) — a parser differential
// IS the vulnerability. The client also applies this before any Coil load (defense in
// depth: the server already validated, but cached/old rows + future surfaces must not
// fetch a non-allowlisted host). Pure Kotlin, no platform deps, so one source compiles
// into both the CLI (author-side pre-check) and client commonMain (render-side guard).
object MediaValidation {
  val ALLOWED_IMAGE_HOSTS: Set<String> = setOf("upload.wikimedia.org")

  val CURATED_ICONS: Set<String> = setOf(
    "school", "luggage", "medical", "move", "party", "baby",
    "calendar", "location", "link", "document", "contact", "budget",
    "travel", "car", "food", "pet", "sport", "list",
  )

  private const val MAX_URL_LEN = 2048
  private val ACCENT_RE = Regex("^#[0-9a-fA-F]{6}$")

  /** null = acceptable, else a short reason. Hand-rolled to match the WHATWG-URL
   *  decisions the server reaches for the ASCII-only allowlist. */
  fun imageUrlError(url: String?): String? {
    if (url == null) return "must be a string"
    if (url.isEmpty() || url.length > MAX_URL_LEN) return "url length out of range"
    // reject control chars / whitespace / backslash before parsing (no smuggling).
    if (url.any { it.code <= 0x20 || it.code == 0x7f || it == '\\' }) return "url contains illegal characters"

    val schemeSep = url.indexOf("://")
    if (schemeSep < 0) return "url does not parse"
    if (url.substring(0, schemeSep).lowercase() != "https") return "scheme must be https" // blocks http/data/javascript/blob

    val rest = url.substring(schemeSep + 3)
    val authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authEnd < 0) rest else rest.substring(0, authEnd)
    val path = if (authEnd < 0) "" else rest.substring(authEnd)

    if (authority.contains('@')) return "userinfo not allowed"
    if (authority.contains('[') || authority.contains(']')) return "ip-literal host not allowed"

    var host = authority
    var port = ""
    val colon = authority.lastIndexOf(':')
    if (colon >= 0) { host = authority.substring(0, colon); port = authority.substring(colon + 1) }
    if (port.isNotEmpty() && port != "443") return "explicit port not allowed"

    host = host.lowercase().removeSuffix(".")
    // Exact-host match. Any non-ascii (IDN homograph) or stray char → illegal host,
    // so a unicode/punycode-decoded host can never alias the allowlisted ASCII host.
    if (host.isEmpty() || host.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-') })
      return "illegal host"
    if (host !in ALLOWED_IMAGE_HOSTS) return "host \"$host\" is not on the image allowlist"

    val pathOnly = path.substringBefore('?').substringBefore('#').lowercase()
    if (pathOnly.endsWith(".svg")) return "SVG images are not allowed"
    return null
  }

  fun iconError(icon: String?): String? =
    if (icon == null) "must be a string"
    else if (icon in CURATED_ICONS) null else "icon \"$icon\" is not in the curated set"

  fun accentHexError(hex: String?): String? =
    if (hex == null) "must be a string"
    else if (ACCENT_RE.matches(hex)) null else "accentColor must match #RRGGBB"

  /** The URL only if it passes — Coil never receives a non-allowlisted/SVG/insecure URL. */
  fun safeImageUrl(url: String?): String? = url?.takeIf { imageUrlError(it) == null }
}
