package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.ChildFriendly
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.pow
import kotlin.math.roundToInt

// ADR 0036 — visual enrichment render kit (curated icons + accent harmonization +
// fallback-ladder composables). The accent math mirrors the signed-off design's
// derive() (Hub-Enrichment-Phone.dc.html): keep hue, clamp chroma, pin lightness
// per theme → decorative roles. accentColor is NEVER painted on body text.

/** Curated icon NAME → glyph (the 18 from the design §D). Unknown name → null → tile default. */
object CuratedIcons {
  val byName: Map<String, ImageVector> = mapOf(
    "school" to Icons.Outlined.School,
    "luggage" to Icons.Outlined.Luggage,
    "medical" to Icons.Outlined.MedicalServices,
    "move" to Icons.Outlined.Home,
    "party" to Icons.Outlined.Celebration,
    "baby" to Icons.Outlined.ChildFriendly,
    "calendar" to Icons.Outlined.CalendarMonth,
    "location" to Icons.Outlined.LocationOn,
    "link" to Icons.Outlined.Link,
    "document" to Icons.Outlined.Description,
    "contact" to Icons.Outlined.Person,
    "budget" to Icons.Outlined.Payments,
    "travel" to Icons.Outlined.FlightTakeoff,
    "car" to Icons.Outlined.DirectionsCar,
    "food" to Icons.Outlined.Restaurant,
    "pet" to Icons.Outlined.Pets,
    "sport" to Icons.Outlined.SportsSoccer,
    "list" to Icons.Outlined.Checklist,
  )
  fun get(name: String?): ImageVector? = name?.let { byName[it] }
}

/** Derived, contrast-clamped accent roles (decorative surfaces only). */
data class AccentRoles(
  val edge: Color, val tile: Color, val onTile: Color,
  val containBg: Color, val logoInk: Color, val chipBg: Color, val chipFg: Color,
)

private val HEX = Regex("^#[0-9a-fA-F]{6}$")

private fun parseHex(hex: String?): Triple<Int, Int, Int>? {
  if (hex == null || !HEX.matches(hex)) return null
  val h = hex.substring(1)
  return Triple(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
}

private fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
  val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
  val mx = maxOf(rf, gf, bf); val mn = minOf(rf, gf, bf)
  val l = (mx + mn) / 2
  if (mx == mn) return Triple(0.0, 0.0, l)
  val d = mx - mn
  val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
  val h = when (mx) {
    rf -> (gf - bf) / d + (if (gf < bf) 6 else 0)
    gf -> (bf - rf) / d + 2
    else -> (rf - gf) / d + 4
  } / 6
  return Triple(h * 360, s, l)
}

private fun hslColor(hDeg: Double, s: Double, l: Double): Color {
  val h = hDeg / 360
  if (s == 0.0) { val v = (l * 255).roundToInt().coerceIn(0, 255); return Color(v, v, v) }
  val q = if (l < 0.5) l * (1 + s) else l + s - l * s
  val p = 2 * l - q
  fun t2(t0: Double): Double {
    var t = t0; if (t < 0) t += 1; if (t > 1) t -= 1
    return when {
      t < 1.0 / 6 -> p + (q - p) * 6 * t
      t < 1.0 / 2 -> q
      t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
      else -> p
    }
  }
  fun ch(t: Double) = (t2(t) * 255).roundToInt().coerceIn(0, 255)
  return Color(ch(h + 1.0 / 3), ch(h), ch(h - 1.0 / 3))
}

private fun lumOf(c: Color): Double {
  fun lin(x: Float): Double { val v = x.toDouble(); return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
  return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

private val INK = Color(0xFF1A0D08)

/** Harmonize a brand hex → safe decorative roles, per theme. null hex → null. */
fun accentRolesFor(hex: String?, dark: Boolean): AccentRoles? {
  val (r, g, b) = parseHex(hex) ?: return null
  val (h, s0, l) = rgbToHsl(r, g, b)
  val s = s0.coerceIn(0.10, 0.92)
  val tile = hslColor(h, s.coerceIn(0.0, 0.62), if (dark) 0.34 else 0.46)
  return AccentRoles(
    edge = hslColor(h, s, if (dark) l.coerceIn(0.60, 0.80) else l.coerceIn(0.34, 0.52)),
    tile = tile,
    onTile = if (lumOf(tile) > 0.45) INK else Color.White,
    containBg = hslColor(h, s.coerceIn(0.0, 0.42), if (dark) 0.17 else 0.93),
    logoInk = hslColor(h, s.coerceIn(0.0, 0.7), if (dark) 0.78 else 0.34),
    chipBg = if (dark) hslColor(h, s.coerceIn(0.0, 0.5), 0.26) else hslColor(h, s.coerceIn(0.0, 0.5), 0.90),
    chipFg = if (dark) hslColor(h, s.coerceIn(0.0, 0.6), 0.86) else hslColor(h, s.coerceIn(0.0, 0.7), 0.28),
  )
}

@Composable
private fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Theme-aware accent roles for the current accentColor (null hex → null). Memoized: the
 *  HSL harmonization (parseHex + rgbToHsl + 7× hslColor + lumOf) recomputes only when the
 *  hex or theme actually changes, not on every recomposition of an enriched card/hub. */
@Composable
fun rememberAccentRoles(hex: String?): AccentRoles? {
  val dark = isDarkTheme()
  return remember(hex, dark) { accentRolesFor(hex, dark) }
}

/** True when this hub/card carries any enrichment worth showing a slot for. */
fun HubMedia?.isEnriched(): Boolean =
  this != null && (heroUrl != null || thumbnailUrl != null || icon != null || accentColor != null)

/** Contact avatar: photo (avatarUrl, host-validated) over an initials fallback —
 *  a failed/missing avatar is invisible (initials show through). */
@Composable
fun ContactAvatar(name: String?, avatarUrl: String?, size: Dp = 44.dp) {
  val url = MediaValidation.safeImageUrl(avatarUrl)
  val initials = (name ?: "?").split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")
  Box(
    Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
    contentAlignment = Alignment.Center,
  ) {
    Text(initials, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    if (url != null) AsyncImage(url, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
  }
}

/** Kind/status chip carrying the curated icon + derived accent (decorative). */
@Composable
fun AccentKindChip(label: String, icon: String?, accentHex: String?, modifier: Modifier = Modifier) {
  if (label.isBlank() && icon == null) return
  val roles = rememberAccentRoles(accentHex)
  val bg = roles?.chipBg ?: MaterialTheme.colorScheme.secondaryContainer
  val fg = roles?.chipFg ?: MaterialTheme.colorScheme.onSecondaryContainer
  Surface(color = bg, shape = RoundedCornerShape(8.dp), modifier = modifier) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      CuratedIcons.get(icon)?.let {
        Icon(it, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
      }
      if (label.isNotBlank()) {
        Text(label, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

/**
 * Fixed-aspect enrichment slot with the fallback ladder baked in:
 * image → icon+accent tile → plain default, all identical in size/shape so a
 * missing/loading/failed image is INVISIBLE (the tile shows through). cover=Crop,
 * contain=Fit on the accent tint with padding (logos). a11y: [alt] on the slot.
 */
@Composable
fun EnrichedThumbnail(
  imageUrl: String?,
  fit: String?,
  icon: String?,
  accentHex: String?,
  alt: String?,
  size: Dp,
  corner: Dp,
  modifier: Modifier = Modifier,
) {
  val roles = rememberAccentRoles(accentHex)
  val url = MediaValidation.safeImageUrl(imageUrl)
  val contain = fit == "contain"
  val tileBg = roles?.let { if (contain) it.containBg else it.tile } ?: MaterialTheme.colorScheme.surfaceContainerHighest
  val glyph = CuratedIcons.get(icon) ?: Icons.Outlined.Image
  val glyphTint = if (contain) (roles?.logoInk ?: MaterialTheme.colorScheme.onSurfaceVariant)
  else (roles?.onTile ?: MaterialTheme.colorScheme.onSurfaceVariant)
  Box(
    modifier.size(size).clip(RoundedCornerShape(corner)).background(tileBg)
      .semantics { if (alt != null) contentDescription = alt },
    contentAlignment = Alignment.Center,
  ) {
    // base tile glyph — what loading / error / "nothing set" all resolve to.
    Icon(glyph, contentDescription = null, tint = glyphTint, modifier = Modifier.size(size * 0.46f))
    if (url != null) {
      AsyncImage(
        model = url,
        contentDescription = null, // alt is on the slot; avoid double-announce
        contentScale = if (contain) ContentScale.Fit else ContentScale.Crop,
        modifier = Modifier.size(size).then(if (contain) Modifier.padding(size * 0.16f) else Modifier),
      )
    }
  }
}

/**
 * Hub-detail hero banner (height-capped, rounded; scrim keeps a white title legible
 * over any image). cover photo / contain logo / accent-only header — same fallback
 * ladder. Rendered as the first list item when the hub is enriched.
 */
@Composable
fun EnrichedHeroBanner(media: HubMedia?, title: String, meta: String?, modifier: Modifier = Modifier) {
  val roles = rememberAccentRoles(media?.accentColor)
  val url = MediaValidation.safeImageUrl(media?.heroUrl ?: media?.thumbnailUrl)
  val contain = media?.heroFit == "contain"
  val bg = roles?.let { if (contain) it.containBg else it.tile } ?: MaterialTheme.colorScheme.surfaceContainerHigh
  val glyph = CuratedIcons.get(media?.icon) ?: Icons.Outlined.Dashboard
  val glyphTint = if (contain) (roles?.logoInk ?: MaterialTheme.colorScheme.onSurfaceVariant)
  else (roles?.onTile ?: MaterialTheme.colorScheme.onSurfaceVariant)
  Box(modifier.fillMaxWidth().height(184.dp).clip(RoundedCornerShape(22.dp)).background(bg)) {
    Icon(glyph, contentDescription = null, tint = glyphTint, modifier = Modifier.align(Alignment.Center).size(64.dp))
    if (url != null) {
      AsyncImage(
        model = url,
        contentDescription = media?.imageAlt,
        contentScale = if (contain) ContentScale.Fit else ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(184.dp).then(if (contain) Modifier.padding(28.dp) else Modifier),
      )
    }
    // bottom scrim band (fixed, sized to the title — legibility never depends on the image)
    Box(
      Modifier.fillMaxWidth().height(110.dp).align(Alignment.BottomStart)
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD1120804)))),
    )
    Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
      Text(
        title, color = Color.White, style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
      )
      meta?.let { Text(it, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.labelLarge) }
    }
  }
}
