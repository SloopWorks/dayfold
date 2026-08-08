package com.sloopworks.dayfold.client

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A deliberately local, synthetic model for the Smart Briefings UX preview.
 *
 * It cannot hold credentials, provider URLs, grant identifiers, or live Dayfold data. The
 * production routine contract can adapt into this model once that contract is available.
 */
@Immutable
data class SmartBriefingsPreviewState(
  val destination: SmartBriefingsPreviewDestination = SmartBriefingsPreviewDestination.Entry,
  val step: SmartBriefingsPreviewStep = SmartBriefingsPreviewStep.Provider,
  val authority: SmartBriefingsPreviewAuthority = SmartBriefingsPreviewAuthority.Owner,
  val provider: SmartBriefingsPreviewProvider? = SmartBriefingsPreviewProvider.Claude,
  val schedule: SmartBriefingsPreviewSchedule? = SmartBriefingsPreviewSchedule.WeekdayMorning,
  val sources: List<SmartBriefingsPreviewSource> = SmartBriefingsPreviewFixtures.sources,
  val hubs: List<SmartBriefingsPreviewHub> = SmartBriefingsPreviewFixtures.hubs,
  val dayfoldOnly: Boolean = false,
  val status: SmartBriefingsPreviewStatus = SmartBriefingsPreviewStatus.Waiting,
  val revokeState: SmartBriefingsPreviewRevokeState = SmartBriefingsPreviewRevokeState.Confirm,
  val recovery: SmartBriefingsPreviewRecovery? = null,
  val draftConflict: Boolean = false,
  val mutationsEnabled: Boolean = true,
  val detailsOpen: Boolean = false,
  val revokeDialogOpen: Boolean = false,
  val revokeOutcomeControlsEnabled: Boolean = false,
)

enum class SmartBriefingsPreviewDestination { Entry, Configure, Privacy, Handoff, Status, Draft, Revoke }
enum class SmartBriefingsPreviewStep { Provider, Sources, Access, Schedule }
enum class SmartBriefingsPreviewAuthority { Owner, AdultReadOnly }
enum class SmartBriefingsPreviewProvider(val label: String) { Claude("Claude"), Codex("Codex") }
enum class SmartBriefingsPreviewSchedule { WeekdayMorning, DailyEvening, WeeklySunday }
enum class SmartBriefingsPreviewStatus { Waiting, Active, Partial, Offline, Failed, Unknown, Revoking, Revoked }
enum class SmartBriefingsPreviewRevokeState { Confirm, Pending, Failed, Revoked }
enum class SmartBriefingsPreviewRecoveryAction { ReviewSources, RefreshDraft, RetryRun, ReviewPrivacy, ViewGuidance }

@Immutable
data class SmartBriefingsPreviewSource(
  val key: String,
  val title: String,
  val supporting: String,
  val selected: Boolean,
  val statusLabel: String? = null,
)

@Immutable
data class SmartBriefingsPreviewHub(
  val key: String,
  val title: String,
  val supporting: String,
  val selected: Boolean,
  val enabled: Boolean = true,
)

@Immutable
data class SmartBriefingsPreviewRecovery(
  val title: String,
  val message: String,
  val actionLabel: String? = null,
  val action: SmartBriefingsPreviewRecoveryAction? = null,
)

sealed interface SmartBriefingsPreviewAction {
  data class SelectProvider(val provider: SmartBriefingsPreviewProvider) : SmartBriefingsPreviewAction
  data class ToggleSource(val key: String) : SmartBriefingsPreviewAction
  data class SelectHub(val key: String) : SmartBriefingsPreviewAction
  data class SelectSchedule(val schedule: SmartBriefingsPreviewSchedule) : SmartBriefingsPreviewAction
  data object Continue : SmartBriefingsPreviewAction
  data object GoBack : SmartBriefingsPreviewAction
  data object ShowPrivacy : SmartBriefingsPreviewAction
  data object ContinuePreview : SmartBriefingsPreviewAction
  data object ReviewDraft : SmartBriefingsPreviewAction
  data object AcceptDraftPreview : SmartBriefingsPreviewAction
  data object RejectDraftPreview : SmartBriefingsPreviewAction
  data object PreviewRevocation : SmartBriefingsPreviewAction
  data object ConfirmRevocationPreview : SmartBriefingsPreviewAction
  data object CompleteRevocationPreview : SmartBriefingsPreviewAction
  data object FailRevocationPreview : SmartBriefingsPreviewAction
  data object Retry : SmartBriefingsPreviewAction
  data class Recover(val recoveryAction: SmartBriefingsPreviewRecoveryAction) : SmartBriefingsPreviewAction
  data object Done : SmartBriefingsPreviewAction
  data object CloseDetails : SmartBriefingsPreviewAction
}

object SmartBriefingsPreviewFixtures {
  val sources = listOf(
    SmartBriefingsPreviewSource("gmail", "Gmail", "Example: messages about plans and logistics", true),
    SmartBriefingsPreviewSource("calendar", "Google Calendar", "Example: upcoming family events", true),
    SmartBriefingsPreviewSource("drive", "Google Drive", "Example: selected documents", false),
  )
  val hubs = listOf(
    SmartBriefingsPreviewHub("school", "School week (example)", "Synthetic family hub", true),
    SmartBriefingsPreviewHub("plans", "Family plans (example)", "Synthetic family hub", false),
  )

  val ownerEntry = SmartBriefingsPreviewState()
  val adultEntry = ownerEntry.copy(authority = SmartBriefingsPreviewAuthority.AdultReadOnly)
  fun configure(step: SmartBriefingsPreviewStep) = ownerEntry.copy(
    destination = SmartBriefingsPreviewDestination.Configure,
    step = step,
  )
  val privacy = ownerEntry.copy(destination = SmartBriefingsPreviewDestination.Privacy)
  val handoff = ownerEntry.copy(destination = SmartBriefingsPreviewDestination.Handoff)
  val waiting = ownerEntry.copy(destination = SmartBriefingsPreviewDestination.Status)
  val active = waiting.copy(
    status = SmartBriefingsPreviewStatus.Active,
    sources = sources.map { source ->
      if (source.selected) source.copy(statusLabel = "Observed in simulated result") else source
    },
  )
  val adultActive = active.copy(authority = SmartBriefingsPreviewAuthority.AdultReadOnly)
  val partial = waiting.copy(
    status = SmartBriefingsPreviewStatus.Partial,
    recovery = SmartBriefingsPreviewRecovery(
      "Some example sources need attention",
      "Calendar is shown as unavailable. Gmail and Drive examples can still be reviewed.",
      "Review example sources",
      SmartBriefingsPreviewRecoveryAction.ReviewSources,
    ),
  )
  val offline = waiting.copy(
    status = SmartBriefingsPreviewStatus.Offline,
    recovery = SmartBriefingsPreviewRecovery(
      "You're offline",
      "This preview keeps its example content available. Actions are disabled until you're online.",
    ),
  )
  val unknown = waiting.copy(
    status = SmartBriefingsPreviewStatus.Unknown,
    recovery = SmartBriefingsPreviewRecovery(
      "Status is temporarily unavailable",
      "Nothing needs to be reconnected from this preview. Try again when you're ready.",
      "View recovery guidance",
      SmartBriefingsPreviewRecoveryAction.ViewGuidance,
    ),
  )
  val draft = active.copy(destination = SmartBriefingsPreviewDestination.Draft)
  val conflict = draft.copy(draftConflict = true)
  val revoke = active.copy(destination = SmartBriefingsPreviewDestination.Revoke)
  val revokePending = revoke.copy(
    status = SmartBriefingsPreviewStatus.Revoking,
    revokeState = SmartBriefingsPreviewRevokeState.Pending,
    mutationsEnabled = false,
    revokeOutcomeControlsEnabled = true,
  )
  val revoked = active.copy(status = SmartBriefingsPreviewStatus.Revoked, mutationsEnabled = false)
  val revokeFailed = revoke.copy(
    revokeState = SmartBriefingsPreviewRevokeState.Failed,
    recovery = SmartBriefingsPreviewRecovery(
      "The example turn-off did not finish",
      "No live access changed. You can safely retry the preview.",
    ),
  )
}

@Composable
fun SmartBriefingsPreviewScreen(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit = {},
  onClose: () -> Unit = {},
) {
  val cs = MaterialTheme.colorScheme
  val scrollState = remember(
    state.destination,
    state.step,
    state.status,
    state.revokeState,
    state.revokeDialogOpen,
  ) { ScrollState(0) }
  Box(Modifier.fillMaxSize().background(cs.surface)) {
    Column(Modifier.matchParentSize()) {
      PreviewTopBar(state.destination.title, onClose)
      PreviewTruthBanner()
      Box(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 18.dp),
        contentAlignment = Alignment.TopCenter,
      ) {
        Column(
          Modifier.fillMaxWidth().widthIn(max = 680.dp).padding(top = 20.dp, bottom = 40.dp),
          verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          when (state.destination) {
            SmartBriefingsPreviewDestination.Entry -> EntryContent(state, onAction)
            SmartBriefingsPreviewDestination.Configure -> ConfigureContent(state, onAction)
            SmartBriefingsPreviewDestination.Privacy -> PrivacyContent(state, onAction)
            SmartBriefingsPreviewDestination.Handoff -> HandoffContent(state, onAction)
            SmartBriefingsPreviewDestination.Status -> StatusContent(state, onAction)
            SmartBriefingsPreviewDestination.Draft -> DraftContent(state, onAction)
            SmartBriefingsPreviewDestination.Revoke -> RevokeContent(state, onAction)
          }
        }
      }
    }
  }
  if (state.revokeDialogOpen) {
    RevokeDialog(state, onAction)
  }
  if (state.detailsOpen) {
    TechnicalDetailsDialog(onDismiss = { onAction(SmartBriefingsPreviewAction.CloseDetails) })
  }
}

private val SmartBriefingsPreviewDestination.title: String
  get() = when (this) {
    SmartBriefingsPreviewDestination.Entry -> "Smart Briefings"
    SmartBriefingsPreviewDestination.Configure -> "Explore setup"
    SmartBriefingsPreviewDestination.Privacy -> "Privacy preview"
    SmartBriefingsPreviewDestination.Handoff -> "Provider handoff"
    SmartBriefingsPreviewDestination.Status -> "Briefing status"
    SmartBriefingsPreviewDestination.Draft -> "Example draft"
    SmartBriefingsPreviewDestination.Revoke -> "Turn-off preview"
  }

private val SmartBriefingsPreviewSchedule?.statusLabel: String
  get() = when (this) {
    SmartBriefingsPreviewSchedule.WeekdayMorning -> "Weekdays · 6:30 AM (example)"
    SmartBriefingsPreviewSchedule.DailyEvening -> "Daily · 6:00 PM (example)"
    SmartBriefingsPreviewSchedule.WeeklySunday -> "Sundays · 7:00 PM (example)"
    null -> "Not selected"
  }

@Composable
private fun PreviewTopBar(title: String, onClose: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("smart-briefings-back")) {
      Icon(DayfoldIcons.ArrowBack, contentDescription = "Back")
    }
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
  }
  HorizontalDivider()
}

@Composable
private fun PreviewTruthBanner() {
  val cs = MaterialTheme.colorScheme
  Surface(
    color = cs.tertiaryContainer,
    contentColor = cs.onTertiaryContainer,
    modifier = Modifier.fillMaxWidth().testTag("smart-briefings-preview-banner")
      .semantics { contentDescription = "Interactive preview. Nothing connects or saves." },
  ) {
    Row(
      Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(DayfoldIcons.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
      Column {
        Text("Interactive preview", style = MaterialTheme.typography.labelLarge)
        Text("Nothing connects or saves.", style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun EntryContent(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  Hero(
    icon = DayfoldIcons.AutoAwesome,
    title = "Turn scattered updates into a calm family briefing",
    body = if (state.authority == SmartBriefingsPreviewAuthority.Owner) {
      "Explore how a family owner could configure an AI provider to prepare Dayfold drafts from selected sources."
    } else {
      "Your family owner manages this feature. You can review its example status without changing access or settings."
    },
  )
  TruthCard(
    rows = listOf(
      Triple(DayfoldIcons.Lock, "Private by design", "Dayfold would share only the sources and hubs an owner chooses."),
      Triple(DayfoldIcons.Check, "Review before changes", "Briefings arrive as drafts; this preview never publishes."),
      Triple(DayfoldIcons.Document, "Family-scoped", "Setup belongs to the family, not an individual device."),
    ),
  )
  if (state.authority == SmartBriefingsPreviewAuthority.Owner) {
    ResponsiveActions(
      primaryLabel = "Explore setup",
      onPrimary = { onAction(SmartBriefingsPreviewAction.Continue) },
      enabled = state.mutationsEnabled,
    )
  } else {
    InfoCard(
      icon = DayfoldIcons.Lock,
      title = "Owner controls only",
      body = "Adults can read briefings and status. Setup and turn-off controls are intentionally unavailable.",
    )
  }
}

@Composable
private fun ConfigureContent(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  StepIndicator(state.step)
  if (state.authority != SmartBriefingsPreviewAuthority.Owner) {
    InfoCard(DayfoldIcons.Lock, "Owner controls only", "Ask the family owner to manage this setup.")
    return
  }
  state.recovery?.let { RecoveryCard(it, state.mutationsEnabled, onAction) }
  when (state.step) {
    SmartBriefingsPreviewStep.Provider -> ProviderStep(state, onAction)
    SmartBriefingsPreviewStep.Sources -> SourcesStep(state, onAction)
    SmartBriefingsPreviewStep.Access -> AccessStep(state, onAction)
    SmartBriefingsPreviewStep.Schedule -> ScheduleStep(state, onAction)
  }
}

@Composable
private fun StepIndicator(step: SmartBriefingsPreviewStep) {
  val steps = SmartBriefingsPreviewStep.entries
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Step ${steps.indexOf(step) + 1} of ${steps.size}", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      steps.forEach {
        Surface(
          color = if (it.ordinal <= step.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
          shape = CircleShape,
          modifier = Modifier.weight(1f).height(5.dp),
        ) {}
      }
    }
  }
}

@Composable
private fun ProviderStep(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  SectionHeading("Choose an AI provider", "Your provider subscription would do the processing. Dayfold does not sell a second AI plan.")
  SmartBriefingsPreviewProvider.entries.forEach { provider ->
    SelectionCard(
      title = provider.label,
      supporting = when (provider) {
        SmartBriefingsPreviewProvider.Claude -> "Explore a future Claude subscription handoff"
        SmartBriefingsPreviewProvider.Codex -> "Explore a future Codex subscription handoff"
      },
      selected = state.provider == provider,
      tag = "provider-${provider.name.lowercase()}",
      enabled = state.mutationsEnabled,
      onClick = { onAction(SmartBriefingsPreviewAction.SelectProvider(provider)) },
    )
  }
  InfoCard(
    DayfoldIcons.Warning,
    "Availability is not verified",
    "Subscription automation, webhooks, and connector support vary by provider and plan. This preview does not claim eligibility.",
  )
  ResponsiveActions(
    "Continue",
    { onAction(SmartBriefingsPreviewAction.Continue) },
    enabled = state.mutationsEnabled && state.provider != null,
  )
}

@Composable
private fun SourcesStep(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  SectionHeading("Choose example sources", "Each source would require its own provider authorization outside this preview.")
  state.sources.forEach { source ->
    ToggleCard(
      title = source.title,
      supporting = source.statusLabel ?: source.supporting,
      checked = source.selected,
      tag = "source-${source.key}",
      enabled = state.mutationsEnabled,
      onClick = { onAction(SmartBriefingsPreviewAction.ToggleSource(source.key)) },
    )
  }
  InfoCard(
    DayfoldIcons.Lock,
    "Source credentials stay separate",
    "A future setup must show the provider's consent screen, granted scopes, and a reliable way to revoke each source.",
  )
  ResponsiveActions(
    "Continue",
    { onAction(SmartBriefingsPreviewAction.Continue) },
    "Back",
    { onAction(SmartBriefingsPreviewAction.GoBack) },
    enabled = state.mutationsEnabled,
  )
}

@Composable
private fun AccessStep(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  SectionHeading("Limit Dayfold access", "Choose which family hubs could receive reviewed drafts.")
  state.hubs.forEach { hub ->
    SelectionCard(
      title = hub.title,
      supporting = hub.supporting,
      selected = hub.selected,
      tag = "hub-${hub.key}",
      enabled = hub.enabled && state.mutationsEnabled,
      onClick = { onAction(SmartBriefingsPreviewAction.SelectHub(hub.key)) },
    )
  }
  InfoCard(
    DayfoldIcons.Verified,
    "Minimum access",
    if (state.dayfoldOnly) "The example is limited to selected hubs and draft creation." else "Review the selected access before continuing.",
  )
  ResponsiveActions(
    "Continue",
    { onAction(SmartBriefingsPreviewAction.Continue) },
    "Back",
    { onAction(SmartBriefingsPreviewAction.GoBack) },
    enabled = state.mutationsEnabled && state.hubs.any { it.selected && it.enabled },
  )
}

@Composable
private fun ScheduleStep(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  SectionHeading("Choose an example cadence", "This only previews the request Dayfold could make. It does not create a routine.")
  SelectionCard(
    "Weekdays at 6:30 AM",
    "Example schedule · local time",
    state.schedule == SmartBriefingsPreviewSchedule.WeekdayMorning,
    "schedule-weekdays",
    enabled = state.mutationsEnabled,
  ) { onAction(SmartBriefingsPreviewAction.SelectSchedule(SmartBriefingsPreviewSchedule.WeekdayMorning)) }
  SelectionCard(
    "Daily at 6:00 PM",
    "Example schedule · local time",
    state.schedule == SmartBriefingsPreviewSchedule.DailyEvening,
    "schedule-daily",
    enabled = state.mutationsEnabled,
  ) { onAction(SmartBriefingsPreviewAction.SelectSchedule(SmartBriefingsPreviewSchedule.DailyEvening)) }
  SelectionCard(
    "Sundays at 7:00 PM",
    "Example schedule · local time",
    state.schedule == SmartBriefingsPreviewSchedule.WeeklySunday,
    "schedule-sunday",
    enabled = state.mutationsEnabled,
  ) { onAction(SmartBriefingsPreviewAction.SelectSchedule(SmartBriefingsPreviewSchedule.WeeklySunday)) }
  InfoCard(
    DayfoldIcons.Event,
    "Runs remain provider-managed",
    "A production flow must verify the provider actually created the schedule and report when its state is unknown.",
  )
  ResponsiveActions(
    "Review privacy",
    { onAction(SmartBriefingsPreviewAction.Continue) },
    "Back",
    { onAction(SmartBriefingsPreviewAction.GoBack) },
    enabled = state.mutationsEnabled && state.schedule != null,
  )
}

@Composable
private fun PrivacyContent(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  Hero(
    DayfoldIcons.Lock,
    "Understand the boundary before continuing",
    "A secure production integration needs explicit consent, short-lived credentials, least-privilege scopes, and revocation that is verified end to end.",
  )
  TruthCard(
    listOf(
      Triple(DayfoldIcons.Document, "What leaves Dayfold", "Only selected content required for the requested briefing."),
      Triple(DayfoldIcons.Lock, "How secrets are handled", "Provider and source tokens must never be stored in drafts, logs, analytics, or prompts."),
      Triple(DayfoldIcons.Delete, "How access ends", "Turning off must revoke Dayfold access and verify provider-side cleanup."),
    ),
  )
  InfoCard(
    DayfoldIcons.Warning,
    "Encryption alone is not enough",
    "The cloud worker needs an authorized way to decrypt selected content. A production design must define key custody, auditability, expiry, and breach recovery before launch.",
  )
  OutlinedButton(
    onClick = { onAction(SmartBriefingsPreviewAction.ShowPrivacy) },
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("smart-briefings-details-action"),
  ) { Text("View technical details") }
  ResponsiveActions(
    "Continue preview",
    { onAction(SmartBriefingsPreviewAction.Continue) },
    "Back",
    { onAction(SmartBriefingsPreviewAction.GoBack) },
    enabled = state.mutationsEnabled,
  )
}

@Composable
private fun HandoffContent(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  Hero(
    DayfoldIcons.ArrowOutward,
    "Provider handoff preview",
    "In a supported production flow, ${state.provider?.label ?: "the selected provider"} would explain its own subscription, connector, and routine permissions before the user authorizes anything.",
  )
  InfoCard(
    DayfoldIcons.Warning,
    "No provider page opens",
    "This build does not discover webhooks, request OAuth grants, paste tokens, call URLs, or create schedules.",
  )
  TruthCard(
    listOf(
      Triple(DayfoldIcons.Check, "1. Confirm eligibility", "Verify the user's plan and available provider capabilities."),
      Triple(DayfoldIcons.Link, "2. Authorize deliberately", "Use an official provider-owned flow with scoped, expiring consent."),
      Triple(DayfoldIcons.Verified, "3. Verify completion", "Return to Dayfold only after the provider reports an authoritative result."),
    ),
  )
  ResponsiveActions(
    "Continue preview",
    { onAction(SmartBriefingsPreviewAction.ContinuePreview) },
    "Back",
    { onAction(SmartBriefingsPreviewAction.GoBack) },
    enabled = state.mutationsEnabled,
  )
}

@Composable
private fun StatusContent(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  val status = when (state.status) {
    SmartBriefingsPreviewStatus.Waiting -> StatusVisual(DayfoldIcons.Event, "Waiting for provider", "Requested, not verified", false)
    SmartBriefingsPreviewStatus.Active -> StatusVisual(DayfoldIcons.Check, "Example briefing is active", "Simulated result · never ran", true)
    SmartBriefingsPreviewStatus.Partial -> StatusVisual(DayfoldIcons.Warning, "Example briefing is partial", "Some example sources unavailable", false)
    SmartBriefingsPreviewStatus.Offline -> StatusVisual(DayfoldIcons.WifiOff, "Offline", "Example content is still readable", false)
    SmartBriefingsPreviewStatus.Failed -> StatusVisual(DayfoldIcons.Warning, "Example briefing needs attention", "No live access changed", false)
    SmartBriefingsPreviewStatus.Unknown -> StatusVisual(DayfoldIcons.Warning, "Status unknown", "No provider result is available", false)
    SmartBriefingsPreviewStatus.Revoking -> StatusVisual(DayfoldIcons.Event, "Example turn-off is pending", "No terminal result has been reported", false)
    SmartBriefingsPreviewStatus.Revoked -> StatusVisual(DayfoldIcons.Delete, "Example access is off", "No future preview work can run", false)
  }
  StatusCard(status)
  state.recovery?.let { RecoveryCard(it, state.mutationsEnabled, onAction) }
  SectionHeading("Example configuration", "These values are synthetic and cannot be used to infer a live provider state.")
  DetailRow("Provider", state.provider?.label ?: "Not selected")
  DetailRow("Schedule", state.schedule.statusLabel)
  DetailRow("Destination", state.hubs.firstOrNull { it.selected }?.title ?: "None")
  SectionHeading("Example sources", "Requested and observed source state stays separate.")
  val selectedSources = state.sources.filter { it.selected }
  if (selectedSources.isEmpty()) {
    DetailRow("Sources", "Dayfold only · no external sources")
  } else {
    selectedSources.forEach { SourceStatusRow(it) }
  }
  OutlinedButton(
    onClick = { onAction(SmartBriefingsPreviewAction.ShowPrivacy) },
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("smart-briefings-details-action"),
  ) { Text("View technical details") }
  if (state.status == SmartBriefingsPreviewStatus.Revoking &&
    state.authority == SmartBriefingsPreviewAuthority.Owner
  ) {
    OutlinedButton(
      onClick = { onAction(SmartBriefingsPreviewAction.PreviewRevocation) },
      modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("smart-briefings-reopen-revoke"),
    ) { Text("View turn-off status") }
  } else if (state.status == SmartBriefingsPreviewStatus.Revoked) {
    InfoCard(DayfoldIcons.Lock, "No actions available", "This preview is revoked. Return to Account when you're done.")
  } else if (state.authority == SmartBriefingsPreviewAuthority.Owner) {
    val enabled = state.mutationsEnabled && state.status != SmartBriefingsPreviewStatus.Offline
    ResponsiveActions(
      "Review example draft",
      { onAction(SmartBriefingsPreviewAction.ReviewDraft) },
      "Preview turning off",
      { onAction(SmartBriefingsPreviewAction.PreviewRevocation) },
      enabled = enabled,
      secondaryEnabled = enabled,
    )
  } else {
    InfoCard(DayfoldIcons.Lock, "Read-only for adults", "The family owner manages configuration and access.")
  }
}

@Composable
private fun DraftContent(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  if (state.draftConflict) {
    RecoveryCard(
      SmartBriefingsPreviewRecovery(
        "This example changed while you were reviewing",
        "Your choice was not applied. Review the newest example before trying again.",
        "Review newest example",
        SmartBriefingsPreviewRecoveryAction.RefreshDraft,
      ),
      state.mutationsEnabled,
      onAction,
    )
  }
  SectionHeading("School week briefing", "Synthetic draft · not created by a provider")
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      DraftSection("Coming up", listOf("Tuesday · Library books due", "Wednesday · Soccer practice at 4:00 PM"))
      HorizontalDivider()
      DraftSection("Needs a look", listOf("Confirm Friday pickup", "Review the field trip form"))
      HorizontalDivider()
      Text("Every item here is example content. Nothing is sent, saved, or published.", style = MaterialTheme.typography.bodySmall)
    }
  }
  if (state.authority == SmartBriefingsPreviewAuthority.Owner) {
    val decisionsEnabled = state.mutationsEnabled && !state.draftConflict
    ResponsiveActions(
      "Mark accepted in preview",
      { onAction(SmartBriefingsPreviewAction.AcceptDraftPreview) },
      "Reject in preview",
      { onAction(SmartBriefingsPreviewAction.RejectDraftPreview) },
      enabled = decisionsEnabled,
      secondaryEnabled = decisionsEnabled,
    )
  } else {
    InfoCard(DayfoldIcons.Lock, "Read-only example", "Only the family owner can review future drafts for publishing.")
  }
}

@Composable
private fun DraftSection(title: String, items: List<String>) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    items.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
  }
}

@Composable
private fun SourceStatusRow(source: SmartBriefingsPreviewSource) {
  val sourceStatus = source.statusLabel ?: "Requested in preview · not observed"
  Column(
    Modifier.fillMaxWidth().padding(vertical = 10.dp)
      .testTag("smart-briefings-source-status-${source.key}")
      .semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
        stateDescription = sourceStatus
      },
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Text(source.title, style = MaterialTheme.typography.titleSmall)
    Text(
      sourceStatus,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  HorizontalDivider()
}

@Composable
private fun TechnicalDetailsDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        "Technical details",
        modifier = Modifier.semantics { heading() },
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Execution mode · Shadow preview")
        Text("Publication · Review first")
        Text("Support code · Not available in preview")
        Text("Recovery guidance · Close this panel and try again later; this preview never requires reconnecting.")
        Text(
          "This local surface contains no provider URL, grant ID, key fingerprint, credential, or family content.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp).testTag("smart-briefings-details-done"),
      ) { Text("Done") }
    },
    modifier = Modifier.testTag("smart-briefings-details-dialog"),
  )
}

@Composable
private fun RevokeContent(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  if (state.authority != SmartBriefingsPreviewAuthority.Owner) {
    InfoCard(DayfoldIcons.Lock, "Owner controls only", "Only the family owner can turn off a briefing integration.")
    return
  }
  val content = when (state.revokeState) {
    SmartBriefingsPreviewRevokeState.Confirm -> Triple(
      "Explore turning off Smart Briefings?",
      "A production flow must stop future work, revoke scoped credentials, and verify provider cleanup. This preview has no grant or task to revoke.",
      "Turn off in preview",
    )
    SmartBriefingsPreviewRevokeState.Pending -> Triple(
      "Example turn-off is pending",
      "Dayfold would keep this state visible until both Dayfold and the provider report a result.",
      null,
    )
    SmartBriefingsPreviewRevokeState.Failed -> Triple(
      "Example turn-off needs attention",
      "A failure must never be reported as success. No live access changed in this preview.",
      "Retry preview",
    )
    SmartBriefingsPreviewRevokeState.Revoked -> Triple(
      "Example access is off",
      "Preview result only. A production success would require verified provider cleanup and local credential deletion.",
      "Done",
    )
  }
  Hero(DayfoldIcons.Delete, content.first, content.second)
  state.recovery?.let { RecoveryCard(it.copy(actionLabel = null, action = null), state.mutationsEnabled, onAction) }
  when (state.revokeState) {
    SmartBriefingsPreviewRevokeState.Confirm -> ResponsiveActions(
      content.third!!,
      { onAction(SmartBriefingsPreviewAction.ConfirmRevocationPreview) },
      "Keep briefing",
      { onAction(SmartBriefingsPreviewAction.GoBack) },
      enabled = state.mutationsEnabled,
    )
    SmartBriefingsPreviewRevokeState.Pending -> {
      InfoCard(
        DayfoldIcons.Event,
        "Do not close over uncertainty",
        "A safe implementation preserves the pending state and offers recovery instead of assuming completion.",
      )
      ResponsiveActions(
        "Finish as success in preview",
        { onAction(SmartBriefingsPreviewAction.CompleteRevocationPreview) },
        "Show failure in preview",
        { onAction(SmartBriefingsPreviewAction.FailRevocationPreview) },
        enabled = state.revokeOutcomeControlsEnabled,
        secondaryEnabled = state.revokeOutcomeControlsEnabled,
      )
    }
    SmartBriefingsPreviewRevokeState.Failed -> ResponsiveActions(
      content.third!!,
      { onAction(SmartBriefingsPreviewAction.Retry) },
      "Back",
      { onAction(SmartBriefingsPreviewAction.GoBack) },
      enabled = state.mutationsEnabled,
    )
    SmartBriefingsPreviewRevokeState.Revoked -> ResponsiveActions(
      content.third!!,
      { onAction(SmartBriefingsPreviewAction.Done) },
    )
  }
}

@Composable
private fun RevokeDialog(
  state: SmartBriefingsPreviewState,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  val title = when (state.revokeState) {
    SmartBriefingsPreviewRevokeState.Confirm -> "Explore turning off Smart Briefings?"
    SmartBriefingsPreviewRevokeState.Pending -> "Example turn-off is pending"
    SmartBriefingsPreviewRevokeState.Failed -> "Example turn-off needs attention"
    SmartBriefingsPreviewRevokeState.Revoked -> "Example access is off"
  }
  val message = when (state.revokeState) {
    SmartBriefingsPreviewRevokeState.Confirm ->
      "A production flow must stop future work, revoke scoped credentials, and verify provider cleanup. This preview has no grant or task to revoke."
    SmartBriefingsPreviewRevokeState.Pending ->
      "No terminal result has been reported. Closing this dialog keeps the pending state visible on Status."
    SmartBriefingsPreviewRevokeState.Failed ->
      "A failure is never reported as success. No live access changed in this preview."
    SmartBriefingsPreviewRevokeState.Revoked ->
      "Preview result only. A production success would require verified provider cleanup and local credential deletion."
  }
  AlertDialog(
    onDismissRequest = { onAction(SmartBriefingsPreviewAction.GoBack) },
    title = { Text(title, modifier = Modifier.semantics { heading() }) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(message)
        state.recovery?.let {
          Text(it.title, style = MaterialTheme.typography.titleSmall)
          Text(it.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    },
    confirmButton = {
      when (state.revokeState) {
        SmartBriefingsPreviewRevokeState.Confirm -> Button(
          onClick = { onAction(SmartBriefingsPreviewAction.ConfirmRevocationPreview) },
          enabled = state.mutationsEnabled,
          modifier = Modifier.heightIn(min = 48.dp).testTag("smart-briefings-revoke-confirm"),
        ) { Text("Turn off in preview") }
        SmartBriefingsPreviewRevokeState.Pending -> Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Button(
            onClick = { onAction(SmartBriefingsPreviewAction.CompleteRevocationPreview) },
            enabled = state.revokeOutcomeControlsEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("smart-briefings-revoke-complete"),
          ) { Text("Finish as success in preview") }
          OutlinedButton(
            onClick = { onAction(SmartBriefingsPreviewAction.FailRevocationPreview) },
            enabled = state.revokeOutcomeControlsEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("smart-briefings-revoke-fail"),
          ) { Text("Show failure in preview") }
        }
        SmartBriefingsPreviewRevokeState.Failed -> Button(
          onClick = { onAction(SmartBriefingsPreviewAction.Retry) },
          enabled = state.mutationsEnabled,
          modifier = Modifier.heightIn(min = 48.dp).testTag("smart-briefings-revoke-retry"),
        ) { Text("Retry preview") }
        SmartBriefingsPreviewRevokeState.Revoked -> Button(
          onClick = { onAction(SmartBriefingsPreviewAction.Done) },
          modifier = Modifier.heightIn(min = 48.dp).testTag("smart-briefings-revoke-done"),
        ) { Text("Done") }
      }
    },
    dismissButton = {
      if (state.revokeState != SmartBriefingsPreviewRevokeState.Revoked) {
        TextButton(
          onClick = { onAction(SmartBriefingsPreviewAction.GoBack) },
          modifier = Modifier.heightIn(min = 48.dp).testTag("smart-briefings-revoke-close"),
        ) {
          Text(if (state.revokeState == SmartBriefingsPreviewRevokeState.Confirm) "Keep briefing" else "Close")
        }
      }
    },
    modifier = Modifier.testTag("smart-briefings-revoke-dialog"),
  )
}

@Composable
private fun Hero(icon: ImageVector, title: String, body: String) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier.size(52.dp),
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(27.dp)) } }
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun SectionHeading(title: String, body: String) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun TruthCard(rows: List<Triple<ImageVector, String, String>>) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column {
      rows.forEachIndexed { index, row ->
        Row(
          Modifier.fillMaxWidth().padding(16.dp),
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(row.first, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(row.second, style = MaterialTheme.typography.titleSmall)
            Text(row.third, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        if (index != rows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
      }
    }
  }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String) {
  Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      Modifier.padding(16.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(icon, null, Modifier.size(23.dp))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
private fun SelectionCard(
  title: String,
  supporting: String,
  selected: Boolean,
  tag: String,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Surface(
    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag).selectable(
      selected = selected,
      enabled = enabled,
      role = Role.RadioButton,
      onClick = onClick,
    ),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
          title,
          style = MaterialTheme.typography.titleMedium,
          color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Text(
          supporting,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
        )
      }
      RadioButton(selected = selected, enabled = enabled, onClick = null)
    }
  }
}

@Composable
private fun ToggleCard(
  title: String,
  supporting: String,
  checked: Boolean,
  tag: String,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag).toggleable(
      value = checked,
      enabled = enabled,
      role = Role.Checkbox,
      onValueChange = { onClick() },
    ),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
    }
  }
}

private data class StatusVisual(val icon: ImageVector, val title: String, val body: String, val positive: Boolean)

@Composable
private fun StatusCard(status: StatusVisual) {
  val cs = MaterialTheme.colorScheme
  Surface(
    color = if (status.positive) cs.primaryContainer else cs.surfaceContainer,
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth().testTag("smart-briefings-status").semantics {
      liveRegion = LiveRegionMode.Polite
      stateDescription = "${status.title}. ${status.body}"
    },
  ) {
    Row(
      Modifier.padding(18.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Icon(status.icon, null, Modifier.size(28.dp), tint = if (status.positive) cs.primary else cs.onSurfaceVariant)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(status.title, style = MaterialTheme.typography.titleMedium)
        Text(status.body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun RecoveryCard(
  recovery: SmartBriefingsPreviewRecovery,
  enabled: Boolean,
  onAction: (SmartBriefingsPreviewAction) -> Unit,
) {
  val cs = MaterialTheme.colorScheme
  Surface(
    color = cs.errorContainer,
    contentColor = cs.onErrorContainer,
    shape = RoundedCornerShape(18.dp),
    modifier = Modifier.fillMaxWidth().testTag("smart-briefings-recovery").semantics {
      liveRegion = LiveRegionMode.Polite
      stateDescription = "${recovery.title}. ${recovery.message}"
    },
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(DayfoldIcons.Warning, null, Modifier.size(23.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(recovery.title, style = MaterialTheme.typography.titleSmall)
          Text(recovery.message, style = MaterialTheme.typography.bodyMedium)
        }
      }
      val actionLabel = recovery.actionLabel
      val recoveryAction = recovery.action
      if (actionLabel != null && recoveryAction != null) {
        OutlinedButton(
          onClick = { onAction(SmartBriefingsPreviewAction.Recover(recoveryAction)) },
          enabled = enabled || recoveryAction == SmartBriefingsPreviewRecoveryAction.ViewGuidance,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("smart-briefings-recovery-action"),
        ) { Text(actionLabel) }
      }
    }
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
  }
  HorizontalDivider()
}

@Composable
private fun ResponsiveActions(
  primaryLabel: String,
  onPrimary: () -> Unit,
  secondaryLabel: String? = null,
  onSecondary: (() -> Unit)? = null,
  enabled: Boolean = true,
  secondaryEnabled: Boolean = true,
) {
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth >= 520.dp && secondaryLabel != null && onSecondary != null) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
          onClick = onSecondary,
          enabled = secondaryEnabled,
          modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("smart-briefings-secondary-action"),
        ) { Text(secondaryLabel) }
        Button(
          onClick = onPrimary,
          enabled = enabled,
          modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("smart-briefings-primary-action"),
        ) { Text(primaryLabel) }
      }
    } else {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
          onClick = onPrimary,
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("smart-briefings-primary-action"),
        ) { Text(primaryLabel) }
        if (secondaryLabel != null && onSecondary != null) {
          OutlinedButton(
            onClick = onSecondary,
            enabled = secondaryEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("smart-briefings-secondary-action"),
          ) { Text(secondaryLabel) }
        }
      }
    }
  }
}

@Immutable
internal data class SmartBriefingsRouteViewState(
  val preview: SmartBriefingsPreviewState,
  val selectedSources: Set<RoutineExternalSource>,
  val operationGeneration: Long,
  val coreDestination: RoutineDestination,
  val revokeSheetOpen: Boolean,
  val lifecycle: RoutineLifecycle,
  val backAction: Action,
)

/** Narrow, pure adapter from the Redux contract to the UI-only preview model. */
internal fun smartBriefingsRouteViewState(state: AppState): SmartBriefingsRouteViewState {
  val routine = state.routines
  val authority = when (routineAuthority(state)) {
    RoutineAuthority.OWNER -> SmartBriefingsPreviewAuthority.Owner
    RoutineAuthority.ADULT_READ_ONLY -> SmartBriefingsPreviewAuthority.AdultReadOnly
  }
  val provider = when (routine.provider) {
    RoutineProvider.UNSELECTED -> null
    RoutineProvider.CLAUDE -> SmartBriefingsPreviewProvider.Claude
    RoutineProvider.CHATGPT -> SmartBriefingsPreviewProvider.Codex
  }
  val sources = RoutineExternalSource.entries.map { source ->
    val status = routine.sourceStatuses[source]
    val statusLabel = when (status) {
      RoutineSourceStatus.REQUESTED -> "Requested in preview · not observed"
      RoutineSourceStatus.PROBING -> "Checking example access"
      RoutineSourceStatus.OBSERVED -> "Observed in simulated result"
      RoutineSourceStatus.SYNCING -> "Example source is syncing"
      RoutineSourceStatus.REAUTH_REQUIRED -> "Example source needs authorization"
      RoutineSourceStatus.ADMIN_BLOCKED -> "Unavailable under an example admin policy"
      RoutineSourceStatus.UNAVAILABLE -> "Unavailable in this simulated result"
      RoutineSourceStatus.REMOVED -> "Removed from the example"
      null -> null
    }
    SmartBriefingsPreviewSource(
      key = source.name.lowercase(),
      title = when (source) {
        RoutineExternalSource.GMAIL -> "Gmail"
        RoutineExternalSource.GOOGLE_CALENDAR -> "Google Calendar"
        RoutineExternalSource.GOOGLE_DRIVE -> "Google Drive"
      },
      supporting = when (source) {
        RoutineExternalSource.GMAIL -> "Example: messages about plans and logistics"
        RoutineExternalSource.GOOGLE_CALENDAR -> "Example: upcoming family events"
        RoutineExternalSource.GOOGLE_DRIVE -> "Example: selected documents"
      },
      selected = source in routine.externalSources,
      statusLabel = statusLabel,
    )
  }
  val hubOptions = routineHubOptions(state)
  val hubs = hubOptions.map { option ->
    SmartBriefingsPreviewHub(
      key = option.hub.name.lowercase(),
      title = "${option.title} (example)",
      supporting = if (option.eligible) "Synthetic family hub" else "Unavailable in this preview",
      selected = routine.selectedHub == option.hub,
      enabled = option.eligible,
    )
  }
  val hubRecovery = when (routine.hubFixture) {
    RoutineHubFixture.LOADING -> SmartBriefingsPreviewRecovery(
      "Loading example hubs",
      "Wait for the synthetic choices to become available.",
    )
    RoutineHubFixture.EMPTY -> SmartBriefingsPreviewRecovery(
      "No eligible example hubs",
      "A production setup would return to this step after an eligible family hub is created.",
    )
    RoutineHubFixture.SELECTED_REMOVED -> SmartBriefingsPreviewRecovery(
      "The selected example hub is unavailable",
      "Choose another eligible destination before continuing.",
    )
    RoutineHubFixture.AVAILABLE -> null
  }
  val offlineRecovery = if (routine.offline) SmartBriefingsPreviewRecovery(
    "You're offline",
    "Example content remains readable. Actions are disabled until you're online.",
  ) else null
  val recovery = routine.recovery?.toPreviewRecovery() ?: offlineRecovery ?: hubRecovery
  val status = when {
    routine.lifecycle == RoutineLifecycle.REVOKING -> SmartBriefingsPreviewStatus.Revoking
    routine.offline -> SmartBriefingsPreviewStatus.Offline
    routine.lifecycle == RoutineLifecycle.REVOKED -> SmartBriefingsPreviewStatus.Revoked
    routine.recovery?.reason == RoutineRecoveryReason.PARTIAL_SOURCE_SET -> SmartBriefingsPreviewStatus.Partial
    routine.recovery?.reason == RoutineRecoveryReason.UNKNOWN -> SmartBriefingsPreviewStatus.Unknown
    routine.recovery != null -> SmartBriefingsPreviewStatus.Failed
    routine.lifecycle in setOf(RoutineLifecycle.ACTIVE, RoutineLifecycle.PAUSED) ->
      SmartBriefingsPreviewStatus.Active
    else -> SmartBriefingsPreviewStatus.Waiting
  }
  val destination = when {
    routine.destination == RoutineDestination.ENTRY -> SmartBriefingsPreviewDestination.Entry
    routine.destination in setOf(
      RoutineDestination.PROVIDER,
      RoutineDestination.SOURCES,
      RoutineDestination.HUB_ACCESS,
      RoutineDestination.SCHEDULE,
    ) -> SmartBriefingsPreviewDestination.Configure
    routine.destination == RoutineDestination.PRIVACY -> SmartBriefingsPreviewDestination.Privacy
    routine.destination == RoutineDestination.HANDOFF -> SmartBriefingsPreviewDestination.Handoff
    routine.destination == RoutineDestination.DRAFT -> SmartBriefingsPreviewDestination.Draft
    else -> SmartBriefingsPreviewDestination.Status
  }
  val step = when (routine.destination) {
    RoutineDestination.SOURCES -> SmartBriefingsPreviewStep.Sources
    RoutineDestination.HUB_ACCESS -> SmartBriefingsPreviewStep.Access
    RoutineDestination.SCHEDULE -> SmartBriefingsPreviewStep.Schedule
    else -> SmartBriefingsPreviewStep.Provider
  }
  val revokeState = when {
    routine.pendingOperation == RoutineOperation.REVOKE -> SmartBriefingsPreviewRevokeState.Pending
    routine.lifecycle == RoutineLifecycle.REVOKED -> SmartBriefingsPreviewRevokeState.Revoked
    routine.recovery?.reason == RoutineRecoveryReason.REVOKE_FAILED ->
      SmartBriefingsPreviewRevokeState.Failed
    else -> SmartBriefingsPreviewRevokeState.Confirm
  }
  val schedule = when (routine.schedule) {
    RoutineSchedulePreset.WEEKDAY_MORNING -> SmartBriefingsPreviewSchedule.WeekdayMorning
    RoutineSchedulePreset.DAILY_EVENING -> SmartBriefingsPreviewSchedule.DailyEvening
    RoutineSchedulePreset.WEEKLY_SUNDAY -> SmartBriefingsPreviewSchedule.WeeklySunday
    null -> null
  }
  return SmartBriefingsRouteViewState(
    preview = SmartBriefingsPreviewState(
      destination = destination,
      step = step,
      authority = authority,
      provider = provider,
      schedule = schedule,
      sources = sources,
      hubs = hubs,
      dayfoldOnly = routine.externalSources.isEmpty(),
      status = status,
      revokeState = revokeState,
      recovery = recovery,
      draftConflict = routine.draft == RoutineDraftFixture.STALE_OR_CONFLICTED,
      mutationsEnabled = routineCanMutate(state),
      detailsOpen = routine.detailsOpen,
      revokeDialogOpen = routine.revokeSheetOpen,
      revokeOutcomeControlsEnabled = routineCanConfigure(state) &&
        !routine.offline &&
        routine.lifecycle == RoutineLifecycle.REVOKING &&
        routine.pendingOperation == RoutineOperation.REVOKE,
    ),
    selectedSources = routine.externalSources,
    operationGeneration = routine.operationGeneration,
    coreDestination = routine.destination,
    revokeSheetOpen = routine.revokeSheetOpen,
    lifecycle = routine.lifecycle,
    backAction = routineBackAction(state),
  )
}

private fun RoutineRecoveryEnvelope.toPreviewRecovery(): SmartBriefingsPreviewRecovery {
  data class RecoveryCopy(
    val title: String,
    val message: String,
    val label: String? = null,
    val action: SmartBriefingsPreviewRecoveryAction? = null,
  )
  val copy = when (reason) {
    RoutineRecoveryReason.PARTIAL_SOURCE_SET -> RecoveryCopy(
      "Some example sources need attention",
      "Available example sources can still be reviewed. Nothing is connected or changed.",
      "Review example sources",
      SmartBriefingsPreviewRecoveryAction.ReviewSources,
    )
    RoutineRecoveryReason.SOURCE_REAUTH -> RecoveryCopy(
      "An example source needs authorization",
      "A production flow would return to a provider-owned consent screen with the requested scope explained.",
      "Review example sources",
      SmartBriefingsPreviewRecoveryAction.ReviewSources,
    )
    RoutineRecoveryReason.CONFLICT, RoutineRecoveryReason.REPEATED_CONFLICTS -> RecoveryCopy(
      "This example changed while you were reviewing",
      "Your choice was not applied. Review the newest example before trying again.",
      "Review newest example",
      SmartBriefingsPreviewRecoveryAction.RefreshDraft,
    )
    RoutineRecoveryReason.REVOKE_FAILED -> RecoveryCopy(
      "The example turn-off did not finish",
      "No live access changed. You can safely retry the preview.",
    )
    RoutineRecoveryReason.PROVIDER_TASK_REMAINS -> RecoveryCopy(
      "Provider cleanup is not confirmed",
      "Dayfold access is off. The provider task may remain and should be removed in the provider; this preview cannot verify or retry that cleanup.",
    )
    RoutineRecoveryReason.OFFLINE -> RecoveryCopy(
      "You're offline",
      "Example content remains readable. Actions are disabled until you're online.",
    )
    RoutineRecoveryReason.UNKNOWN, RoutineRecoveryReason.INTERNAL -> RecoveryCopy(
      "Status is temporarily unavailable",
      "Nothing needs to be reconnected from this preview. Try again when you're ready.",
      "View recovery guidance",
      SmartBriefingsPreviewRecoveryAction.ViewGuidance,
    )
    else -> when (recommendedAction) {
      RoutineRecommendedAction.REVIEW_POLICY -> RecoveryCopy(
        "Review the preview boundary",
        "No live access or family content changed. Review the privacy model before continuing.",
        "Review privacy model",
        SmartBriefingsPreviewRecoveryAction.ReviewPrivacy,
      )
      RoutineRecommendedAction.REFRESH_DRAFT -> RecoveryCopy(
        "The example draft needs to be refreshed",
        "Your choice was not applied. Review a fresh synthetic draft before trying again.",
        "Refresh example draft",
        SmartBriefingsPreviewRecoveryAction.RefreshDraft,
      )
      RoutineRecommendedAction.CONTACT_SUPPORT -> RecoveryCopy(
        "Status is temporarily unavailable",
        "Nothing needs to be reconnected from this preview. Open local guidance for safe next steps.",
        "View recovery guidance",
        SmartBriefingsPreviewRecoveryAction.ViewGuidance,
      )
      else -> RecoveryCopy(
        "The example needs attention",
        "No live access or family content changed. This preview has no safe automated recovery for this state.",
      )
    }
  }
  return SmartBriefingsPreviewRecovery(copy.title, copy.message, copy.label, copy.action)
}

/** Converts a UI event to explicit local-fixture Redux actions. No effect or URI is invoked. */
internal fun smartBriefingsPreviewActions(
  state: SmartBriefingsRouteViewState,
  action: SmartBriefingsPreviewAction,
): List<Action> {
  val nextGeneration = state.operationGeneration + 1L
  val readOnlyLocalAction = action in setOf(
    SmartBriefingsPreviewAction.GoBack,
    SmartBriefingsPreviewAction.ShowPrivacy,
    SmartBriefingsPreviewAction.PreviewRevocation,
    SmartBriefingsPreviewAction.Done,
    SmartBriefingsPreviewAction.CloseDetails,
  ) || (
    action is SmartBriefingsPreviewAction.Recover &&
      action.recoveryAction == SmartBriefingsPreviewRecoveryAction.ViewGuidance
  )
  val revokeTerminalAction = action in setOf(
    SmartBriefingsPreviewAction.CompleteRevocationPreview,
    SmartBriefingsPreviewAction.FailRevocationPreview,
  ) && state.preview.revokeOutcomeControlsEnabled
  if (!state.preview.mutationsEnabled && !readOnlyLocalAction && !revokeTerminalAction) return emptyList()
  fun observedRunAndDraft(): List<Action> {
    val observedSources = state.selectedSources.associateWith { RoutineSourceStatus.OBSERVED }
    return listOf(
      RoutineOperationStarted(RoutineOperation.RUN, nextGeneration),
      RoutineRunFinished(
        generation = nextGeneration,
        result = RoutineRunResult.SUCCESS,
        counts = RoutineSafeCounts(candidates = 1, sourcesObserved = observedSources.size),
        sourceStatuses = observedSources,
      ),
      RoutineOperationStarted(RoutineOperation.RUN, nextGeneration + 1L),
      RoutineDraftPresented(nextGeneration + 1L, RoutineDraftFixture.FIRST_DRAFT),
    )
  }
  return when (action) {
    is SmartBriefingsPreviewAction.SelectProvider -> listOf(
      RoutineProviderSelected(
        when (action.provider) {
          SmartBriefingsPreviewProvider.Claude -> RoutineProvider.CLAUDE
          SmartBriefingsPreviewProvider.Codex -> RoutineProvider.CHATGPT
        },
      ),
    )
    is SmartBriefingsPreviewAction.ToggleSource -> {
      val source = RoutineExternalSource.entries.firstOrNull { it.name.lowercase() == action.key }
      if (source == null) emptyList() else listOf(
        RoutineSourcesSelected(
          if (source in state.selectedSources) state.selectedSources - source else state.selectedSources + source,
        ),
      )
    }
    is SmartBriefingsPreviewAction.SelectHub -> {
      val hub = RoutineSyntheticHub.entries.firstOrNull { it.name.lowercase() == action.key }
      if (hub == null) emptyList() else listOf(RoutineHubSelected(hub))
    }
    is SmartBriefingsPreviewAction.SelectSchedule -> listOf(
      RoutineScheduleSelected(
        when (action.schedule) {
          SmartBriefingsPreviewSchedule.WeekdayMorning -> RoutineSchedulePreset.WEEKDAY_MORNING
          SmartBriefingsPreviewSchedule.DailyEvening -> RoutineSchedulePreset.DAILY_EVENING
          SmartBriefingsPreviewSchedule.WeeklySunday -> RoutineSchedulePreset.WEEKLY_SUNDAY
        },
      ),
    )
    SmartBriefingsPreviewAction.Continue -> if (state.coreDestination == RoutineDestination.PRIVACY) {
      listOf(RoutinePrivacyAcknowledged(true), RoutineContinue)
    } else {
      listOf(RoutineContinue)
    }
    SmartBriefingsPreviewAction.GoBack -> listOf(
      if (state.revokeSheetOpen) CloseRoutineRevokeSheet else RoutineNavigateBack,
    )
    SmartBriefingsPreviewAction.ShowPrivacy -> listOf(OpenRoutineDetails)
    SmartBriefingsPreviewAction.ContinuePreview -> listOf(RoutineContinue)
    SmartBriefingsPreviewAction.ReviewDraft -> observedRunAndDraft()
    SmartBriefingsPreviewAction.AcceptDraftPreview -> listOf(
      RoutineDraftDecisionPreviewed(RoutineDraftDecision.ACCEPTED_PREVIEW),
    )
    SmartBriefingsPreviewAction.RejectDraftPreview -> listOf(
      RoutineDraftDecisionPreviewed(RoutineDraftDecision.REJECTED_PREVIEW),
    )
    SmartBriefingsPreviewAction.PreviewRevocation -> listOf(OpenRoutineRevokeSheet)
    SmartBriefingsPreviewAction.ConfirmRevocationPreview -> listOf(
      RoutineOperationStarted(RoutineOperation.REVOKE, nextGeneration),
    )
    SmartBriefingsPreviewAction.CompleteRevocationPreview -> if (
      state.revokeSheetOpen &&
      state.lifecycle == RoutineLifecycle.REVOKING &&
      state.preview.revokeOutcomeControlsEnabled
    ) listOf(
      RoutineRevokeFinished(state.operationGeneration, RoutineRevokeOutcome.REVOKED),
    ) else emptyList()
    SmartBriefingsPreviewAction.FailRevocationPreview -> if (
      state.revokeSheetOpen &&
      state.lifecycle == RoutineLifecycle.REVOKING &&
      state.preview.revokeOutcomeControlsEnabled
    ) listOf(
      RoutineRevokeFinished(state.operationGeneration, RoutineRevokeOutcome.FAILED),
    ) else emptyList()
    SmartBriefingsPreviewAction.Retry -> if (state.revokeSheetOpen) {
      listOf(
        RoutineOperationStarted(RoutineOperation.REVOKE, nextGeneration),
      )
    } else emptyList()
    is SmartBriefingsPreviewAction.Recover -> if (
      !state.preview.mutationsEnabled &&
      action.recoveryAction != SmartBriefingsPreviewRecoveryAction.ViewGuidance
    ) {
      emptyList()
    } else when (action.recoveryAction) {
      SmartBriefingsPreviewRecoveryAction.ReviewSources -> listOf(RoutineReviewSources)
      SmartBriefingsPreviewRecoveryAction.ReviewPrivacy -> listOf(RoutineReviewPrivacy)
      SmartBriefingsPreviewRecoveryAction.RefreshDraft -> if (state.coreDestination == RoutineDestination.DRAFT) {
        listOf(
          RoutineOperationStarted(RoutineOperation.DRAFT_REFRESH, nextGeneration),
          RoutineDraftPresented(nextGeneration, RoutineDraftFixture.FIRST_DRAFT),
        )
      } else emptyList()
      SmartBriefingsPreviewRecoveryAction.RetryRun -> if (
        state.coreDestination in setOf(RoutineDestination.STATUS, RoutineDestination.ACTIVE) &&
        state.lifecycle != RoutineLifecycle.REVOKED
      ) {
        observedRunAndDraft()
      } else emptyList()
      SmartBriefingsPreviewRecoveryAction.ViewGuidance -> listOf(OpenRoutineDetails)
    }
    SmartBriefingsPreviewAction.Done -> listOf(CloseRoutineRevokeSheet)
    SmartBriefingsPreviewAction.CloseDetails -> listOf(CloseRoutineDetails)
  }
}
