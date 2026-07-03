package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// AUTH-S6-D Phase 2 — the scan + deep-link surfaces (A8b: scanprimer / scandevice
// / scandenied / deviceresume / devicefinishing). Pure composables, MaterialTheme
// roles only, snapshot-tested in isolation. The live camera is the QrScanner
// expect/actual (Tier 2); these screens are the chrome around it + the deep-link
// cold-start handoff. The scan path only FILLS the code → the human still reviews
// + approves on AuthorizeDevice (never auto-approve).

// A close (✕) tap-target in the top-left.
@Composable
private fun CloseGlyph(onClose: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
    Text("✕", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
  }
}

// A camera "lens" mark drawn from theme roles (no icon font) — a rounded tile with
// a concentric lens, matching the brand's drawn-glyph approach.
@Composable
private fun CameraMark(container: Color, onContainer: Color, size: Int = 74) {
  Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.31f).dp)).background(container), contentAlignment = Alignment.Center) {
    Box(Modifier.size((size * 0.42f).dp).clip(RoundedCornerShape(50)).border(3.dp, onContainer, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
      Box(Modifier.size((size * 0.16f).dp).clip(RoundedCornerShape(50)).background(onContainer))
    }
  }
}

// ── Scan primer (camera-permission rationale, before the OS prompt) ──
@Composable
fun ScanPrimerScreen(
  onAllow: () -> Unit = {},
  onEnterCode: () -> Unit = {},
  onClose: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxSize().background(cs.surface).padding(start = 28.dp, end = 28.dp, top = 14.dp, bottom = 30.dp)) {
    CloseGlyph(onClose)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
      CameraMark(cs.tertiaryContainer, cs.onTertiaryContainer)
      Spacer(Modifier.height(24.dp))
      Text("Scan from your computer", style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
      Spacer(Modifier.height(12.dp))
      Text(
        "Point your phone at the QR code in the terminal and we'll fill in the device code for you. You'll still review and approve the request yourself.",
        style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
      )
      Spacer(Modifier.height(22.dp))
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
      ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(cs.secondary).align(Alignment.Top))
        Text(
          "Dayfold uses the camera only to read the code on your screen — nothing is recorded or sent.",
          style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
      }
    }
    PillButton("Allow camera", container = cs.primary, content = cs.onPrimary, modifier = Modifier.fillMaxWidth().testTag("scan-allow"), onClick = onAllow)
    Spacer(Modifier.height(11.dp))
    PillButton("Enter code instead", container = cs.surface, content = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().testTag("scan-entercode"), onClick = onEnterCode)
  }
}

// ── Scan device (live viewfinder overlay over QrScanner) ──
// Hosts the camera (QrScanner actual) full-bleed with a dark overlay: scrim,
// centered reticle with corner brackets, top bar, "looking for a code" pill, and
// a persistent manual fallback. onCode receives the RAW scanned payload.
@Composable
fun ScanDeviceScreen(
  onCode: (String) -> Unit = {},
  onEnterManually: () -> Unit = {},
  onClose: () -> Unit = {},
) {
  val scrim = Color(0x9E140D0B)
  Box(Modifier.fillMaxSize()) {
    // live camera behind everything; raw payload → user_code → caller looks it up
    QrScanner(onCode = { raw -> parseDeviceCode(raw)?.let(onCode) }, onCancel = onClose, modifier = Modifier.fillMaxSize())

    // dim scrim + centered reticle (corner brackets + scan line) drawn off the camera
    Box(Modifier.fillMaxSize().background(scrim))
    androidx.compose.foundation.Canvas(Modifier.size(260.dp).align(Alignment.Center)) {
      val s = this.size.minDimension
      val len = s * 0.18f; val w = 4.dp.toPx(); val r = 10.dp.toPx()
      val white = Color.White
      // four corner brackets
      fun corner(ox: Float, oy: Float, dx: Float, dy: Float) {
        drawLine(white, Offset(ox, oy), Offset(ox + dx * len, oy), w)
        drawLine(white, Offset(ox, oy), Offset(ox, oy + dy * len), w)
      }
      corner(r, r, 1f, 1f); corner(s - r, r, -1f, 1f)
      corner(r, s - r, 1f, -1f); corner(s - r, s - r, -1f, -1f)
      // static scan line (animation is a Tier-2 polish; respects reduced-motion by default)
      drawLine(Color(0xFFFFB4A3), Offset(s * 0.08f, s / 2), Offset(s * 0.92f, s / 2), 2.dp.toPx())
      // a faint reticle frame
      drawRoundRect(Color(0x33FFFFFF), Offset.Zero, Size(s, s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(34.dp.toPx()), style = Stroke(1.dp.toPx()))
    }

    // top bar: close · title · torch
    Row(
      Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 20.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      ScrimCircle(onClick = onClose, testTag = "scan-close") { Text("✕", color = Color.White, style = MaterialTheme.typography.titleMedium) }
      Text("Scan device code", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      ScrimCircle(onClick = {}) { Text("✦", color = Color.White, style = MaterialTheme.typography.titleMedium) }   // torch (Tier 2)
    }

    // "looking for a code" pill, just below the reticle
    Row(
      Modifier.align(Alignment.Center).padding(top = 320.dp).clip(RoundedCornerShape(50)).background(Color(0x8C140D0B)).padding(horizontal = 15.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color(0xFF50DBC9)))
      Text("Looking for a code…", color = Color(0xFFF0DFDA), style = MaterialTheme.typography.bodyMedium)
    }

    // instruction + manual fallback
    Column(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Point at the QR code on your computer.", color = Color(0xFFF0DFDA), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
      Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
          .background(Color(0x24F0DFDA)).border(1.dp, Color(0x4DF0DFDA), RoundedCornerShape(16.dp))
          .clickable(onClick = onEnterManually).testTag("scan-manual"),
        contentAlignment = Alignment.Center,
      ) { Text("Enter code manually", color = Color.White, style = MaterialTheme.typography.titleSmall) }
    }
  }
}

@Composable
private fun ScrimCircle(onClick: () -> Unit, testTag: String? = null, content: @Composable () -> Unit) {
  Box(
    Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color(0x80140D0B))
      .then(if (testTag != null) Modifier.testTag(testTag) else Modifier).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) { content() }
}

// ── Scan denied (camera permission off — no dead end) ──
@Composable
fun ScanDeniedScreen(
  onOpenSettings: () -> Unit = {},
  onEnterCode: () -> Unit = {},
  onClose: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  Column(Modifier.fillMaxSize().background(cs.surface).padding(start = 28.dp, end = 28.dp, top = 14.dp, bottom = 30.dp)) {
    CloseGlyph(onClose)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
      Box(Modifier.size(74.dp).clip(RoundedCornerShape(23.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Text("⊘", style = MaterialTheme.typography.headlineMedium, color = cs.onSurfaceVariant)
      }
      Spacer(Modifier.height(24.dp))
      Text("The camera is off", style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
      Spacer(Modifier.height(12.dp))
      Text(
        "No problem — you can turn it on in Settings, or just type the code from your computer instead. It only takes a moment.",
        style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
      )
      Spacer(Modifier.height(18.dp))
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
      ) {
        Text("⚙", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
        Text(
          "Enable Camera for Dayfold in your device Settings. We only ever use it to read the on-screen code.",
          style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
      }
    }
    PillButton("Open Settings", container = cs.surfaceContainerHigh, content = cs.onSurface, modifier = Modifier.fillMaxWidth().testTag("scan-settings"), onClick = onOpenSettings)
    Spacer(Modifier.height(11.dp))
    PillButton("Enter code instead", container = cs.primary, content = cs.onPrimary, modifier = Modifier.fillMaxWidth().testTag("scan-denied-entercode"), onClick = onEnterCode)
  }
}

// ── Device resume (deep-link cold-start: tapped a link, not signed in yet) ──
@Composable
fun DeviceResumeScreen(onProvider: (String) -> Unit = {}) {
  val cs = MaterialTheme.colorScheme
  Column(
    Modifier.fillMaxSize().background(cs.surface).padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 30.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
      DayfoldMark(size = 74)
      Spacer(Modifier.height(26.dp))
      Row(
        Modifier.clip(RoundedCornerShape(50)).background(cs.tertiaryContainer).padding(horizontal = 13.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(cs.onTertiaryContainer))
        Text("DEVICE APPROVAL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onTertiaryContainer)
      }
      Spacer(Modifier.height(18.dp))
      Text("Sign in to approve this device", style = MaterialTheme.typography.displaySmall, color = cs.onSurface, textAlign = TextAlign.Center)
      Spacer(Modifier.height(12.dp))
      Text(
        "You followed a link from a computer asking for access. Sign in and we'll bring you right back to review it.",
        style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
      )
    }
    AuthButton(
      "Continue with Google", container = cs.surfaceContainerLowest, content = cs.onSurface,
      border = cs.outlineVariant, leading = { GoogleGlyph() }, modifier = Modifier.testTag("resume-google"),
      onClick = { onProvider("google") },
    )
    Spacer(Modifier.height(11.dp))
    AuthButton(
      "Continue with Apple", container = cs.onSurface, content = cs.surface,
      leading = { AppleGlyph(cs.surface) }, onClick = { onProvider("apple") },
    )
    Spacer(Modifier.height(10.dp))
    Text("We'll return you to the approval screen right after.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
  }
}

// ── Device finishing (post-sign-in beat while the stashed link resumes) ──
@Composable
fun DeviceFinishingScreen() {
  val cs = MaterialTheme.colorScheme
  Column(
    Modifier.fillMaxSize().background(cs.surface).padding(28.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
  ) {
    Box(Modifier.size(80.dp).clip(RoundedCornerShape(50)).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(color = cs.onTertiaryContainer, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
    }
    Spacer(Modifier.height(24.dp))
    Text("SIGNED IN", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Text("Finishing…", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
    Spacer(Modifier.height(10.dp))
    Text(
      "Bringing you back to the device request you started. This only takes a second.",
      style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
    )
  }
}
