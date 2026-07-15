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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// AUTH-S6-D — CLI/device approval UI (Dayfold, A8b `entercode` / `authorizedevice`
// / `devicedenied` / `deviceexpired`). Pure composables: state in, callbacks out,
// MaterialTheme roles only (light+dark correct) — snapshot-testable in isolation
// and routed by FeedApp. No icon-font dep (glyphs are text/drawn, like AuthScreens
// + DevicesScreen). Scanner + deep-link stay Phase 2 (onScan defaults null).

// CODE_LEN / normalizeDeviceCode / formatUserCode moved to :client (DeviceCode.kt)
// in P2.2a — staying DeepLink.kt needs them and :client can't depend on :ui.

// ── small shared chrome ──

@Composable
internal fun PillButton(
  text: String,
  container: Color,
  content: Color,
  modifier: Modifier = Modifier,
  border: Color? = null,
  enabled: Boolean = true,
  busy: Boolean = false,
  onClick: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  val effectiveEnabled = enabled && !busy
  Box(
    modifier
      .height(52.dp)
      .clip(RoundedCornerShape(16.dp))
      .background(if (effectiveEnabled) container else cs.surfaceContainerHigh)
      .then(if (border != null) Modifier.border(1.5.dp, border, RoundedCornerShape(16.dp)) else Modifier)
      .clickable(enabled = effectiveEnabled, onClick = onClick)
      .semantics { if (busy) stateDescription = "Busy" },
    contentAlignment = Alignment.Center,
  ) {
    androidx.compose.foundation.layout.Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp),
    ) {
      if (busy) androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, color = content, modifier = Modifier.size(18.dp))
      Text(text, style = MaterialTheme.typography.titleSmall, color = if (effectiveEnabled || busy) content else cs.onSurfaceVariant)
    }
  }
}

// Scan/Type segmented toggle (A8b entercode). On this screen Type is active; the
// Scan segment routes to the camera flow. Hidden when scanning isn't supported.
@Composable
private fun ScanTypeToggle(onScan: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(cs.surfaceContainer).padding(4.dp)) {
    Box(
      Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(50)).clickable(onClick = onScan).testTag("toggle-scan"),
      contentAlignment = Alignment.Center,
    ) { Text("Scan QR", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant) }
    Box(
      Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(50)).background(cs.primary),
      contentAlignment = Alignment.Center,
    ) { Text("Type code", style = MaterialTheme.typography.titleSmall, color = cs.onPrimary) }
  }
}

// A back chevron tap-target (mirrors DevicesScreen's header back).
@Composable
private fun BackChevron(onBack: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Box(
    Modifier.size(38.dp).clip(RoundedCornerShape(50)).clickable(onClick = onBack),
    contentAlignment = Alignment.Center,
  ) { androidx.compose.material3.Icon(DayfoldIcons.ArrowBack, contentDescription = "Back", tint = cs.onSurface, modifier = Modifier.size(24.dp)) }
}

// 8 monospace code cells split 4-4 with a dash. `editable` cells show a filled
// border (entry); read-only cells (the confirm row on AuthorizeDevice) are flat.
@Composable
private fun CodeCells(code: String, editable: Boolean) {
  val cs = MaterialTheme.colorScheme
  val mono = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp, color = cs.onSurface,
  )
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(CODE_LEN) { i ->
      if (i == 4) Text("–", style = mono.copy(color = cs.onSurfaceVariant), modifier = Modifier.padding(horizontal = 5.dp))
      val ch = code.getOrNull(i)?.toString() ?: ""
      if (editable) {
        Box(
          Modifier.padding(horizontal = 3.dp).width(34.dp).height(50.dp)
            .clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainer)
            .border(2.dp, if (ch.isNotEmpty()) cs.primary else cs.outlineVariant, RoundedCornerShape(12.dp)),
          contentAlignment = Alignment.Center,
        ) { Text(ch, style = mono) }
      } else {
        Text(ch.ifEmpty { "•" }, style = mono.copy(fontSize = 25.sp), modifier = Modifier.padding(horizontal = 4.dp))
      }
    }
  }
}

// ── Enter device code (manual user_code entry) ──
// onScan != null (a camera platform, qrScanSupported) renders the Scan/Type
// segmented toggle so both entry paths are discoverable; null hides it (desktop /
// Phase 1). The 8-cell entry is unchanged.
@Composable
fun EnterCodeScreen(
  state: EnterCodeViewState,
  onLookup: (String) -> Unit = {},
  onBack: () -> Unit = {},
  onScan: (() -> Unit)? = null,
) {
  val cs = MaterialTheme.colorScheme
  var raw by remember { mutableStateOf("") }
  val code = normalizeDeviceCode(raw)
  val ready = code.length == CODE_LEN && !state.busy
  fun submit() { if (code.length == CODE_LEN) onLookup(formatUserCode(code)) }

  Column(Modifier.fillMaxSize().background(cs.surface).padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 30.dp)) {
    BackChevron(onBack)
    Spacer(Modifier.height(16.dp))
    if (onScan != null) {
      ScanTypeToggle(onScan = onScan)   // Type is the active segment on this screen
      Spacer(Modifier.height(18.dp))
    }
    Text("Enter device code", style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
    Spacer(Modifier.height(8.dp))
    Text(
      "Type the code shown on your computer, then confirm the request on the next screen.",
      style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
    )
    Spacer(Modifier.height(28.dp))
    BasicTextField(
      value = code,
      onValueChange = { raw = normalizeDeviceCode(it) },
      singleLine = true,
      textStyle = TextStyle(color = Color.Transparent),
      cursorBrush = SolidColor(Color.Transparent),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Characters),
      keyboardActions = KeyboardActions(onDone = { submit() }),
      modifier = Modifier.fillMaxWidth().testTag("device-code-field"),
      decorationBox = { CodeCells(code, editable = true) },
    )
    Spacer(Modifier.height(18.dp))
    Text(
      "Codes are 8 characters and expire in about 10 minutes.",
      style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
      textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let {
      Spacer(Modifier.height(12.dp))
      Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.weight(1f))
    PillButton(
      "Continue",
      container = cs.primary, content = cs.onPrimary, enabled = ready, busy = state.busy,
      modifier = Modifier.fillMaxWidth().testTag("device-continue"), onClick = { submit() },
    )
  }
}

// Per-hub scope picker (ADR 0029 T4): Full = blanket grant (hubIds null, back-compat
// default); Hubs = per-hub content scope (hubIds = the checked set, non-empty enforced
// by canApprove below — not just the API's 400).
private enum class DeviceScopeMode { Full, Hubs }

// ── Authorize device (RFC 8628 — owner approve/deny) ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorizeDeviceScreen(
  state: AuthorizeDeviceViewState,
  onApprove: (fid: String, hubIds: List<String>?) -> Unit = { _, _ -> },
  onDeny: (String) -> Unit = {},
  onCancel: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  val device = state.pendingDevice
  val owners = ownerFamiliesFor(state.families)
  var selectedFid by remember(state.families, state.activeFamilyId) {
    mutableStateOf(state.activeFamilyId?.takeIf { id -> owners.any { it.familyId == id } } ?: owners.firstOrNull()?.familyId)
  }
  var pickerOpen by remember { mutableStateOf(false) }
  var scopeMode by remember { mutableStateOf(DeviceScopeMode.Full) }
  var selectedHubIds by remember { mutableStateOf(emptySet<String>()) }
  val canApprove = device != null && selectedFid != null && !state.busy &&
    (scopeMode == DeviceScopeMode.Full || selectedHubIds.isNotEmpty())

  Column(Modifier.fillMaxSize().background(cs.surface)) {
    // top bar: close · title · spacer
    Row(
      Modifier.fillMaxWidth().padding(start = 18.dp, end = 22.dp, top = 10.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).clickable(onClick = onCancel).testTag("device-cancel"),
        contentAlignment = Alignment.Center) { Text("✕", style = MaterialTheme.typography.titleMedium, color = cs.onSurface) }
      Text("Authorize device", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
      Spacer(Modifier.width(34.dp))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
      // header
      Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) {
          Text(">_", style = MaterialTheme.typography.titleMedium, color = cs.onTertiaryContainer)
        }
        Spacer(Modifier.height(12.dp))
        Text(
          (device?.client?.takeIf { it.isNotBlank() } ?: "A device") + " wants access",
          style = MaterialTheme.typography.titleLarge, color = cs.onSurface, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text("A device is asking to sign in to your account.", style = MaterialTheme.typography.bodyMedium,
          color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
      }
      Spacer(Modifier.height(14.dp))

      // code confirm
      Text("CONFIRM THIS CODE MATCHES YOUR SCREEN", style = MaterialTheme.typography.labelSmall,
        color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 7.dp))
      Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(16.dp), contentAlignment = Alignment.Center) {
        CodeCells(device?.userCode?.let { normalizeDeviceCode(it) } ?: "", editable = false)
      }
      Spacer(Modifier.height(16.dp))

      // datacenter anti-phishing warning (ADR 0011 §7)
      if (device?.originKind == "datacenter") {
        Row(
          Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.errorContainer).padding(12.dp).testTag("device-datacenter-warning"),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          androidx.compose.material3.Icon(DayfoldIcons.Warning, contentDescription = null, tint = cs.onErrorContainer, modifier = Modifier.size(22.dp))
          Text(
            "This request comes from a data center, not a home network. Only approve if you started it yourself.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer,
          )
        }
        Spacer(Modifier.height(14.dp))
      }

      // detail rows: origin · scope (informational) · owner-family selector
      Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer)) {
        DetailRow(
          glyph = "◉",
          title = device?.originIp?.takeIf { it.isNotBlank() } ?: "Origin unknown",
          sub = device?.originUa?.takeIf { it.isNotBlank() } ?: "No device details reported",
        )
        RowDivider()
        // scope picker (ADR 0029 T4) — Full access (blanket, hubIds=null) vs Only these
        // hubs (per-hub content scope). Full is the default so a device approved without
        // touching this control keeps today's behavior.
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⚷", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
            Text("Access", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
          }
          Spacer(Modifier.height(10.dp))
          val scopeOptions = listOf(DeviceScopeMode.Full to "Full access", DeviceScopeMode.Hubs to "Only these hubs")
          SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("device-scope-toggle")) {
            scopeOptions.forEachIndexed { i, (mode, label) ->
              SegmentedButton(
                selected = scopeMode == mode,
                onClick = { scopeMode = mode },
                shape = SegmentedButtonDefaults.itemShape(i, scopeOptions.size),
              ) { Text(label) }
            }
          }
          when {
            scopeMode == DeviceScopeMode.Full -> {
              Spacer(Modifier.height(8.dp))
              Text(
                "Read & write · can't manage members or kids' info",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
              )
            }
            state.hubs.isEmpty() -> {
              Spacer(Modifier.height(8.dp))
              Text("No hubs yet — create one first.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            else -> {
              Spacer(Modifier.height(4.dp))
              state.hubs.forEach { hub ->
                key(hub.id) {
                  val checked = hub.id in selectedHubIds
                  Row(
                    Modifier.fillMaxWidth()
                      .clickable { selectedHubIds = if (checked) selectedHubIds - hub.id else selectedHubIds + hub.id }
                      .testTag("device-scope-hub-${hub.id}").padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    Text(if (checked) "●" else "○", style = MaterialTheme.typography.titleMedium, color = if (checked) cs.primary else cs.onSurfaceVariant)
                    Text(hub.title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                  }
                }
              }
            }
          }
        }
        RowDivider()
        // owner-family selector — static when exactly one (S2); a picker when many
        when {
          owners.isEmpty() -> DetailRow(glyph = "⌂", title = "No family to approve into", sub = "You need to be a family owner to approve")
          owners.size == 1 -> {
            val f = owners.first()
            DetailRow(glyph = "⌂", title = "For ${f.name.ifBlank { "your family" }}", sub = "You're an owner here")
          }
          else -> {
            val sel = owners.firstOrNull { it.familyId == selectedFid } ?: owners.first()
            Box(Modifier.fillMaxWidth().clickable { pickerOpen = !pickerOpen }.testTag("device-family-selector")) {
              DetailRow(glyph = "⌂", title = "For ${sel.name.ifBlank { "your family" }}", sub = "You're an owner here", trailing = if (pickerOpen) "⌃" else "⌄")
            }
            if (pickerOpen) {
              owners.forEach { family ->
                key(family.familyId) {
                  RowDivider()
                  Box(
                    Modifier.fillMaxWidth().clickable { selectedFid = family.familyId; pickerOpen = false }.testTag("device-family-${family.familyId}"),
                  ) {
                    DetailRow(
                      glyph = if (family.familyId == sel.familyId) "●" else "○",
                      title = family.name.ifBlank { "Family ${family.familyId}" }, sub = "Owner",
                    )
                  }
                }
              }
            }
          }
        }
      }

      state.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
      }

      Spacer(Modifier.height(18.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton(
          "Deny", container = cs.surface, content = cs.onSurface, border = cs.outline,
          enabled = device != null && selectedFid != null && !state.busy,
          modifier = Modifier.weight(1f).testTag("device-deny"),
          onClick = { selectedFid?.let { onDeny(it) } },
        )
        PillButton(
          "Approve", container = cs.primary, content = cs.onPrimary, enabled = canApprove, busy = state.busy,
          modifier = Modifier.weight(1.2f).testTag("device-approve"),
          onClick = { selectedFid?.let { fid -> onApprove(fid, if (scopeMode == DeviceScopeMode.Full) null else selectedHubIds.toList()) } },
        )
      }
      Spacer(Modifier.height(12.dp))
      Text(
        "Only approve if you just started this on your computer.",
        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun DetailRow(glyph: String, title: String, sub: String, trailing: String? = null) {
  val cs = MaterialTheme.colorScheme
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(glyph, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
      Text(sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
    if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
  }
}

@Composable
private fun RowDivider() {
  Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

// ── Terminal outcome screens (centered, mirror the A8b denied/expired views) ──

@Composable
private fun OutcomeScreen(
  glyph: String,
  title: String,
  body: String,
  primary: (Pair<String, () -> Unit>)? = null,
  secondaryText: String,
  onSecondary: () -> Unit,
) {
  val cs = MaterialTheme.colorScheme
  Column(
    Modifier.fillMaxSize().background(cs.surface).padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 30.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
  ) {
    Box(Modifier.size(74.dp).clip(RoundedCornerShape(23.dp)).background(cs.surfaceContainerHigh), contentAlignment = Alignment.Center) {
      Text(glyph, style = MaterialTheme.typography.headlineMedium, color = cs.onSurfaceVariant)
    }
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, textAlign = TextAlign.Center)
    Spacer(Modifier.height(10.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(30.dp))
    if (primary != null) {
      PillButton(primary.first, container = cs.primary, content = cs.onPrimary, modifier = Modifier.fillMaxWidth(), onClick = primary.second)
      Spacer(Modifier.height(11.dp))
    }
    PillButton(secondaryText, container = cs.surfaceContainerHigh, content = cs.onSurface, modifier = Modifier.fillMaxWidth().testTag("outcome-done"), onClick = onSecondary)
  }
}

@Composable
fun DeviceDeniedScreen(onDone: () -> Unit = {}) = OutcomeScreen(
  glyph = "✕", title = "Denied",
  body = "That device won't get access to your family. If you didn't start this, you're all set — nothing happened and nothing changed.",
  secondaryText = "Done", onSecondary = onDone,
)

@Composable
fun DeviceExpiredScreen(onRetry: () -> Unit = {}, onDone: () -> Unit = {}) = OutcomeScreen(
  glyph = "↺", title = "This request has expired",
  body = "For your security, approval requests time out — and this one may already have been handled elsewhere. Start again on the computer that asked to sign in.",
  primary = "Enter a new code" to onRetry,
  secondaryText = "Done", onSecondary = onDone,
)

// No A8b mockup for "approved" → a minimal confirmation reusing existing styles
// (design-first: don't invent a hi-fi screen). The CLI shows "logged in" and the
// new grant appears under Connected devices.
@Composable
fun DeviceApprovedConfirm(onDone: () -> Unit = {}) = OutcomeScreen(
  glyph = "✓", title = "You're connected",
  body = "That device can now add cards to your family. You can revoke it any time from Connected devices.",
  secondaryText = "Done", onSecondary = onDone,
)
