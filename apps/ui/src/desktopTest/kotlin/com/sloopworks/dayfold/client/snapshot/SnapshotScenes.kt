package com.sloopworks.dayfold.client.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.AccountScreen
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.AvatarPickerContent
import com.sloopworks.dayfold.client.AuthorizeDeviceScreen
import com.sloopworks.dayfold.client.CapReachedState
import com.sloopworks.dayfold.client.CreateFamilyScreen
import com.sloopworks.dayfold.client.DeviceApprovedConfirm
import com.sloopworks.dayfold.client.DeviceDeniedScreen
import com.sloopworks.dayfold.client.DeviceExpiredScreen
import com.sloopworks.dayfold.client.DeviceFinishingScreen
import com.sloopworks.dayfold.client.DeviceResumeScreen
import com.sloopworks.dayfold.client.DevicesScreen
import com.sloopworks.dayfold.client.EnterCodeScreen
import com.sloopworks.dayfold.client.FamilyNullState
import com.sloopworks.dayfold.client.FeedScreen
import com.sloopworks.dayfold.client.HubDetailScreen
import com.sloopworks.dayfold.client.HubListScreen
import com.sloopworks.dayfold.client.JoinInviteScreen
import com.sloopworks.dayfold.client.LocationPermission
import com.sloopworks.dayfold.client.MatchedOnDeviceChip
import com.sloopworks.dayfold.client.MatchedOnDeviceRow
import com.sloopworks.dayfold.client.MembersScreen
import com.sloopworks.dayfold.client.NotifConfig
import com.sloopworks.dayfold.client.OfflineBanner
import com.sloopworks.dayfold.client.PermissionLadderScreen
import com.sloopworks.dayfold.client.PermissionPrompt
import com.sloopworks.dayfold.client.PlacesListScreen
import com.sloopworks.dayfold.client.PrivacyDetailContent
import com.sloopworks.dayfold.client.ProximitySettingsScreen
import com.sloopworks.dayfold.client.QuietHoursHeldCard
import com.sloopworks.dayfold.client.Route
import com.sloopworks.dayfold.client.ScanDeniedScreen
import com.sloopworks.dayfold.client.ScanDeviceScreen
import com.sloopworks.dayfold.client.ScanPrimerScreen
import com.sloopworks.dayfold.client.SignInScreen
import com.sloopworks.dayfold.client.SplashScreen
import com.sloopworks.dayfold.client.TabShell
import com.sloopworks.dayfold.client.TimelineCard
import com.sloopworks.dayfold.client.TimelineDetail
import com.sloopworks.dayfold.client.TimelineScale
import com.sloopworks.dayfold.client.cards.DetailScreen
import com.sloopworks.dayfold.client.currentDetailCard
import com.sloopworks.dayfold.client.presentTimelineCard
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import com.sloopworks.dayfold.client.ui.loading.EmptyState
import com.sloopworks.dayfold.client.ui.loading.ErrorRetry
import com.sloopworks.dayfold.client.ui.loading.FeedSkeleton
import com.sloopworks.dayfold.client.ui.loading.ListSkeleton
import kotlinx.datetime.TimeZone
import org.reduxkotlin.snapshot.SnapshotApp
import org.reduxkotlin.snapshot.SnapshotInput
import org.reduxkotlin.snapshot.cli.runCli
import org.reduxkotlin.snapshot.snapshotApp

// One registry, two consumers: assertGolden tests (Task 6/7) and the :client:snapshotUi
// CLI (Task 5). presets -> SnapshotStates -> the state-based composables under DayfoldTheme.

// The fixed "now" every snapshot renders at (matches the committed goldens' header date).
val SNAPSHOT_NOW: kotlin.time.Instant = kotlin.time.Instant.parse("2026-07-02T12:00:00Z")

// Timeline scenes pin to move-in day 10:40 ET so done/next markers exercise mid-day state.
val TIMELINE_NOW: kotlin.time.Instant = kotlin.time.Instant.parse(SnapshotStates.TIMELINE_NOW)
private val NY = TimeZone.of("America/New_York")

val clientSnapshots: SnapshotApp = snapshotApp {
  defaults { width = 411; height = 891; density = 2f; theme = "light" }

  scene("feed") {
    presets("busy", "empty", "caught-up", "syncing", "offline", "typed", "enriched",
      "invite-none", "invite-yes", "invite-no", "enriched-pair")
    render { args ->
      val p = presetName(args.input)
      val state = when (p) {
        "invite-none", "invite-yes", "invite-no" -> SnapshotStates.inviteFeed(p.removePrefix("invite-"))
        "enriched-pair" -> SnapshotStates.ENRICHED_PAIR_FEED
        else -> SnapshotStates.feed(p)
      }
      // Pinned clock/tz: the header renders "Today / <date>" from the clock — a live clock
      // makes every feed golden stale at the next date rollover (and CI runs in UTC).
      // Wrap in TabShell so the golden shows the persistent bottom bar in its production
      // position (Task 3). reduceMotion=true → Snap, no animation frame captured.
      themed(args.theme) {
        TabShell(
          Route.Feed, reduceMotion = true, barVisible = true, onNow = {}, onHubs = {},
          feedContent = { FeedScreen(state, now = SNAPSHOT_NOW, timeZone = TimeZone.UTC) },
          hubsContent = {},
        )
      }
    }
  }

  scene("hub-detail") {
    presets("canonical", "enriched", "checklist", "enriched-logo", "enriched-photo",
      "timeline-card", "timeline-overlay", "timeline-hidden", "timeline-nudge", "derived-timeline")
    render { args ->
      val p = presetName(args.input)
      val state = when (p) {
        "canonical", "enriched" -> SnapshotStates.hubTree(p).let { AppState(currentHubId = it.hub.id, currentHubTree = it) }
        "checklist" -> SnapshotStates.CHECKLIST_HUB
        "enriched-logo" -> SnapshotStates.enrichedHubDetail(SnapshotStates.ENRICHED_HUBS[1])
        "enriched-photo" -> SnapshotStates.enrichedHubDetail(SnapshotStates.ENRICHED_HUBS[0])
        "timeline-card" -> SnapshotStates.timelineHubCardState()
        "timeline-overlay" -> SnapshotStates.timelineHubOverlayState()
        "timeline-hidden" -> SnapshotStates.timelineHubHiddenState()
        "timeline-nudge" -> SnapshotStates.timelineNudgeState()
        "derived-timeline" -> SnapshotStates.derivedTimelineHubState()
        else -> error("unknown hub-detail preset '$p'")
      }
      // Timeline presets pin move-in-day now (mid-day markers); the rest pin SNAPSHOT_NOW
      // so countdown badges ("in N days") are date-stable.
      val now = if (p.startsWith("timeline") || p == "derived-timeline") TIMELINE_NOW else SNAPSHOT_NOW
      themed(args.theme) {
        TabShell(
          // Match production: the timeline overlay (timelineDetail != null) hides the bar
          // (full-screen morph, ADR 0050); every other hub-detail preset keeps it.
          Route.Hubs, reduceMotion = true, barVisible = state.timelineDetail == null, onNow = {}, onHubs = {},
          feedContent = {},
          hubsContent = { HubDetailScreen(state, now = now, timeZone = NY) },
        )
      }
    }
  }

  scene("hub-list") {
    presets("enriched")
    render { args ->
      themed(args.theme) {
        TabShell(
          Route.Hubs, reduceMotion = true, barVisible = true, onNow = {}, onHubs = {},
          feedContent = {},
          hubsContent = { HubListScreen(AppState(hubs = SnapshotStates.ENRICHED_HUBS), now = SNAPSHOT_NOW) },
        )
      }
    }
  }

  scene("detail") {
    presets("file", "link", "invite", "contact", "geo", "email")
    render { args ->
      val id = presetName(args.input)
      val state = SnapshotStates.TYPED_FEED.copy(detailStack = listOf(id))
      val card = currentDetailCard(state)!!
      themed(args.theme) { DetailScreen(card, onBack = {}, onAction = {}) }
    }
  }

  // ── Auth / onboarding ────────────────────────────────────────────────────────
  scene("auth") {
    presets("signin", "signin-busy", "signin-error", "createfamily", "familynull", "splash")
    render { args ->
      themed(args.theme) {
        when (presetName(args.input)) {
          "signin" -> SignInScreen()
          "signin-busy" -> SignInScreen(pendingProvider = "google")
          "signin-error" -> SignInScreen(error = "Couldn't reach Dayfold. Try again.")
          "createfamily" -> CreateFamilyScreen(initialName = "The Jacksons")
          "familynull" -> FamilyNullState()
          "splash" -> SplashScreen()
          else -> error("unknown auth preset")
        }
      }
    }
  }

  scene("account") {
    presets("default", "signout-busy")
    render { args ->
      themed(args.theme) {
        AccountScreen(SnapshotStates.ACCOUNT_STATE, signOutBusy = presetName(args.input) == "signout-busy")
      }
    }
  }

  // Delta A / Task 5 — the picker's inner content (see AvatarPickerContent's doc comment for
  // why the ModalBottomSheet wrapper itself isn't scened: a headless single-frame render never
  // paints a Dialog's separate compose scene).
  scene("avatar-picker") {
    defaults { height = 460 }
    presets("monogram", "fun")
    render { args ->
      themedSurface(args.theme) {
        when (presetName(args.input)) {
          "monogram" -> AvatarPickerContent(currentColor = "teal", currentRef = null, onSave = { _, _ -> })
          "fun" -> AvatarPickerContent(currentColor = null, currentRef = "avatar:fox-01", onSave = { _, _ -> })
          else -> error("unknown avatar-picker preset")
        }
      }
    }
  }

  scene("join") {
    presets("entry", "waiting", "locked", "error")
    render { args ->
      val state = when (presetName(args.input)) {
        "entry" -> SnapshotStates.joinState()
        "waiting" -> SnapshotStates.joinState("waiting", "The Riveras")
        "locked" -> SnapshotStates.joinState("locked")
        "error" -> SnapshotStates.joinState("error")
        else -> error("unknown join preset")
      }
      themed(args.theme) { JoinInviteScreen(state) }
    }
  }

  scene("members") {
    presets("roster", "loading", "error", "row-busy")
    render { args ->
      themed(args.theme) { MembersScreen(SnapshotStates.membersState(presetName(args.input))) }
    }
  }

  scene("devices") {
    presets("list", "loading", "error", "row-busy")
    render { args ->
      themed(args.theme) { DevicesScreen(SnapshotStates.devicesState(presetName(args.input))) }
    }
  }

  scene("device-approval") {
    presets("entercode", "entercode-error", "entercode-scan",
      "authorize-datacenter", "authorize-residential", "authorize-multiowner",
      "denied", "expired", "approved", "resume", "finishing")
    render { args ->
      themed(args.theme) {
        when (presetName(args.input)) {
          "entercode" -> EnterCodeScreen(AppState(route = Route.EnterCode))
          "entercode-error" -> EnterCodeScreen(AppState(route = Route.EnterCode, deviceError = "Too many tries — wait about 15 minutes."))
          "entercode-scan" -> EnterCodeScreen(AppState(route = Route.EnterCode), onScan = {})
          "authorize-datacenter" -> AuthorizeDeviceScreen(SnapshotStates.authorizeState("datacenter"))
          "authorize-residential" -> AuthorizeDeviceScreen(SnapshotStates.authorizeState("residential"))
          "authorize-multiowner" -> AuthorizeDeviceScreen(SnapshotStates.authorizeState("residential", multiOwner = true))
          "denied" -> DeviceDeniedScreen()
          "expired" -> DeviceExpiredScreen()
          "approved" -> DeviceApprovedConfirm()
          "resume" -> DeviceResumeScreen()
          "finishing" -> DeviceFinishingScreen()
          else -> error("unknown device-approval preset")
        }
      }
    }
  }

  scene("scan") {
    presets("primer", "device", "denied")
    render { args ->
      themed(args.theme) {
        when (presetName(args.input)) {
          "primer" -> ScanPrimerScreen()
          "device" -> ScanDeviceScreen()
          "denied" -> ScanDeniedScreen()
          else -> error("unknown scan preset")
        }
      }
    }
  }

  // ── Phase-B device-glue surfaces ─────────────────────────────────────────────
  scene("notif") {
    defaults { height = 360 }
    presets("quiet-held", "cap-reached")
    render { args ->
      themedSurface(args.theme) {
        when (presetName(args.input)) {
          "quiet-held" -> QuietHoursHeldCard("8:00 AM", 2)
          "cap-reached" -> CapReachedState(3, onOpenApp = {})
          else -> error("unknown notif preset")
        }
      }
    }
  }

  scene("privacy") {
    defaults { height = 640 }
    presets("affordance")
    render { args ->
      themedSurface(args.theme) {
        Column {
          MatchedOnDeviceChip()
          MatchedOnDeviceRow(onClick = {})
          PrivacyDetailContent(onManagePlaces = {}, onDismiss = {})
        }
      }
    }
  }

  scene("places") {
    defaults { height = 560 }
    presets("list", "empty")
    render { args ->
      val places = if (presetName(args.input) == "empty") emptyList() else SnapshotStates.PLACES
      themedSurface(args.theme) { PlacesListScreen(places) }
    }
  }

  scene("proximity") {
    presets("on", "off", "deregistering")
    render { args ->
      themed(args.theme) {
        when (presetName(args.input)) {
          "on" -> ProximitySettingsScreen(NotifConfig(enabled = true), LocationPermission.Always, deregistering = false, {}, {}, {}, {}, {}, {})
          "off" -> ProximitySettingsScreen(NotifConfig(enabled = false), LocationPermission.WhenInUse, deregistering = false, {}, {}, {}, {}, {}, {})
          "deregistering" -> ProximitySettingsScreen(NotifConfig(enabled = false), LocationPermission.WhenInUse, deregistering = true, {}, {}, {}, {}, {}, {})
          else -> error("unknown proximity preset")
        }
      }
    }
  }

  scene("permission") {
    presets("locprime", "always", "notif", "limited", "denied", "downgraded")
    render { args ->
      val prompt = when (presetName(args.input)) {
        "locprime" -> PermissionPrompt.LocPrime
        "always" -> PermissionPrompt.AlwaysUpgrade
        "notif" -> PermissionPrompt.NotifPrime
        "limited" -> PermissionPrompt.Limited
        "denied" -> PermissionPrompt.Denied
        "downgraded" -> PermissionPrompt.Downgraded
        else -> error("unknown permission preset")
      }
      themed(args.theme) { PermissionLadderScreen(prompt, onPrimary = {}, onSecondary = {}) }
    }
  }

  scene("offline-banner") {
    defaults { height = 140 }
    presets("default")
    render { args -> themedSurface(args.theme) { OfflineBanner() } }
  }

  // ── Loading/error kit ────────────────────────────────────────────────────────
  scene("kit") {
    defaults { height = 560 }
    presets("list-skeleton", "feed-skeleton", "error-retry", "error-retry-busy", "empty-state")
    render { args ->
      themedSurface(args.theme) {
        when (presetName(args.input)) {
          "list-skeleton" -> ListSkeleton(rows = 4)
          "feed-skeleton" -> FeedSkeleton()
          "error-retry" -> ErrorRetry("Couldn't load devices. Try again.", onRetry = {})
          "error-retry-busy" -> ErrorRetry("Retrying", onRetry = {}, retrying = true)
          "empty-state" -> EmptyState("No devices yet", "Phones and CLIs you authorize show up here.")
          else -> error("unknown kit preset")
        }
      }
    }
  }

  // ── Timelines (ADR 0045/0046) — pinned to TIMELINE_NOW ──────────────────────
  scene("timeline-card") {
    defaults { height = 640 }
    presets("day", "hub", "hub-collapsed", "derived")
    render { args ->
      val tl = when (presetName(args.input)) {
        "day" -> SnapshotStates.dayTimeline()
        "hub" -> SnapshotStates.hubCardTimeline()
        "hub-collapsed" -> SnapshotStates.hubCollapsedCardTimeline()
        "derived" -> com.sloopworks.dayfold.client.deriveTimeline(SnapshotStates.derivedTimelineTree(), NY)!!
        else -> error("unknown timeline-card preset")
      }
      val model = presentTimelineCard(tl, SnapshotStates.TIMELINE_NOW, NY)!!
      // Mirror TimelineCardSnapshotTest framing: fixed 390dp column on the warm canvas.
      themed(args.theme) {
        Box(Modifier.fillMaxSize().background(Color(0xFFE9DDD7)).padding(16.dp)) {
          Box(Modifier.width(379.dp)) { TimelineCard(model, onOpen = {}) }
        }
      }
    }
  }

  scene("timeline-detail") {
    presets("day", "hub", "both-toggle", "derived")
    render { args ->
      val (tl, scale) = when (presetName(args.input)) {
        "day" -> SnapshotStates.dayTimeline() to TimelineScale.Day
        "hub" -> SnapshotStates.hubTimeline() to TimelineScale.Hub
        "both-toggle" -> SnapshotStates.bothScalesTimeline() to TimelineScale.Day
        "derived" -> com.sloopworks.dayfold.client.deriveTimeline(SnapshotStates.derivedTimelineTree(), NY)!! to TimelineScale.Day
        else -> error("unknown timeline-detail preset")
      }
      themed(args.theme) {
        TimelineDetail(tl = tl, scale = scale, nowIso = SnapshotStates.TIMELINE_NOW, tz = NY, onBack = {}, onAction = {})
      }
    }
  }
}

// Presets only (this registry doesn't accept ad-hoc --state-json; keep the surface small).
private fun presetName(input: SnapshotInput): String = when (input) {
  is SnapshotInput.Preset -> input.name
  is SnapshotInput.Json -> error("this registry takes --preset, not --state-json")
}

private fun themed(theme: String?, content: @Composable () -> Unit): @Composable () -> Unit =
  { DayfoldTheme(darkTheme = theme == "dark") { content() } }

// Component scenes (no Scaffold of their own) get the theme background so the PNG
// isn't transparent-over-black.
private fun themedSurface(theme: String?, content: @Composable () -> Unit): @Composable () -> Unit =
  {
    DayfoldTheme(darkTheme = theme == "dark") {
      Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column { content() } }
    }
  }

fun main(argv: Array<String>) {
  clientSnapshots.runCli(argv)   // argv = OPTIONS only, no leading "snapshot"
  kotlin.system.exitProcess(0)   // Skiko leaves non-daemon threads alive
}
