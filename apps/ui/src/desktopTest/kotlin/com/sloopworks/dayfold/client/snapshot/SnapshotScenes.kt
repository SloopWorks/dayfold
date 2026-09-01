package com.sloopworks.dayfold.client.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.AccountScreen
import com.sloopworks.dayfold.client.ResponseScopeStep
import com.sloopworks.dayfold.client.ResponseSheetContent
import com.sloopworks.dayfold.client.ResponseStep
import com.sloopworks.dayfold.client.ResponseSurface
import com.sloopworks.dayfold.client.SmartContentModel
import com.sloopworks.dayfold.client.SmartContentScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Title
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.AvatarPickerContent
import com.sloopworks.dayfold.client.CalendarNotificationOwner
import com.sloopworks.dayfold.client.CalendarImportProposal
import com.sloopworks.dayfold.client.EventInstant
import com.sloopworks.dayfold.client.HubVisibilityChoice
import com.sloopworks.dayfold.client.ImportDestination
import com.sloopworks.dayfold.client.ImportFieldDiff
import com.sloopworks.dayfold.client.StructuredLocation
import com.sloopworks.dayfold.client.DeviceCalendar
import com.sloopworks.dayfold.client.features.calendar.CalendarAccountGroup
import com.sloopworks.dayfold.client.features.calendar.CalendarAmbiguousMatchScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarChooserScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarCheckFooterLine
import com.sloopworks.dayfold.client.features.calendar.CalendarCheckNowCard
import com.sloopworks.dayfold.client.features.calendar.CalendarDeniedScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarDetailsDifferScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarImportApplyScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarImportAudienceExistingHubScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarImportAudienceNewHubScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarImportConfirmScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarImportDestinationScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarImportFieldsScreen
import com.sloopworks.dayfold.client.features.calendar.ImportApplyAction
import com.sloopworks.dayfold.client.features.calendar.ImportApplyKind
import com.sloopworks.dayfold.client.features.calendar.ImportDestinationRow
import com.sloopworks.dayfold.client.features.calendar.audienceLine
import com.sloopworks.dayfold.client.features.calendar.CalendarMatchedSummaryScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarNoCalendarsScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarPrimerScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarReturnPhase
import com.sloopworks.dayfold.client.features.calendar.CalendarReturnScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarReviewListScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarSettingsOffScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarSettingsOnScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarSuggestedMatchScreen
import com.sloopworks.dayfold.client.features.calendar.MatchedChecklistItem
import com.sloopworks.dayfold.client.features.calendar.PrefillRow
import com.sloopworks.dayfold.client.features.calendar.PrefillSheetContent
import com.sloopworks.dayfold.client.features.calendar.ResetLocalMatchesSheetContent
import com.sloopworks.dayfold.client.AuthorizeDeviceScreen
import com.sloopworks.dayfold.client.CapReachedState
import com.sloopworks.dayfold.client.CreateFamilyScreen
import com.sloopworks.dayfold.client.DeviceApprovedConfirm
import com.sloopworks.dayfold.client.DeviceDeniedScreen
import com.sloopworks.dayfold.client.DeviceExpiredScreen
import com.sloopworks.dayfold.client.DeviceFinishingScreen
import com.sloopworks.dayfold.client.DeviceState
import com.sloopworks.dayfold.client.DeviceResumeScreen
import com.sloopworks.dayfold.client.DevicesScreen
import com.sloopworks.dayfold.client.EnterCodeScreen
import com.sloopworks.dayfold.client.FamilyNullState
import com.sloopworks.dayfold.client.FeedScreen
import com.sloopworks.dayfold.client.HubAudience
import com.sloopworks.dayfold.client.HubAudienceMember
import com.sloopworks.dayfold.client.HubDetailScreen
import com.sloopworks.dayfold.client.HubState
import com.sloopworks.dayfold.client.HubListScreen
import com.sloopworks.dayfold.client.NavigationState
import com.sloopworks.dayfold.client.HubPeopleContent
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
import com.sloopworks.dayfold.client.SmartBriefingsPreviewFixtures
import com.sloopworks.dayfold.client.SmartBriefingsPreviewScreen
import com.sloopworks.dayfold.client.SmartBriefingsPreviewStep
import com.sloopworks.dayfold.client.SplashScreen
import com.sloopworks.dayfold.client.TabShell
import com.sloopworks.dayfold.client.TimelineCard
import com.sloopworks.dayfold.client.TimelineDetail
import com.sloopworks.dayfold.client.TimelineScale
import com.sloopworks.dayfold.client.cards.DetailScreen
import com.sloopworks.dayfold.client.currentDetailCard
import com.sloopworks.dayfold.client.accountViewState
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
        "canonical", "enriched" -> SnapshotStates.hubTree(p).let { AppState(hubs = HubState(currentHubId = it.hub.id, currentHubTree = it)) }
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
          Route.Hubs, reduceMotion = true, barVisible = state.hubs.timelineDetail == null, onNow = {}, onHubs = {},
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
          hubsContent = { HubListScreen(AppState(hubs = HubState(hubs = SnapshotStates.ENRICHED_HUBS)), now = SNAPSHOT_NOW) },
        )
      }
    }
  }

  scene("detail") {
    presets("file", "link", "invite", "contact", "geo", "email")
    render { args ->
      val id = presetName(args.input)
      val state = SnapshotStates.TYPED_FEED.copy(navigation = SnapshotStates.TYPED_FEED.navigation.copy(detailStack = listOf(id)))
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
    presets("default", "signout-busy", "smart-briefings")
    render { args ->
      themed(args.theme) {
        val preset = presetName(args.input)
        val app = SnapshotStates.ACCOUNT_STATE.copy(
          session = SnapshotStates.ACCOUNT_STATE.session.copy(signOutBusy = preset == "signout-busy"),
        )
        AccountScreen(
          accountViewState(app),
          smartBriefingsPreviewAvailable = preset == "smart-briefings",
        )
      }
    }
  }

  // ADR 0064 — Settings › Smart content. Presets mirror the design's GAP-5 and GAP-6 views:
  // the three rule provenances side by side (where "distinguished by sub-line copy, never
  // colour alone" is actually visible), the pending/offline state, and the empty case.
  scene("smart-content") {
    presets("default", "offline", "empty")
    render { args ->
      themed(args.theme) {
        val preset = presetName(args.input)
        SmartContentScreen(
          model = if (preset == "empty") SmartContentModel() else SmartContentFixtures.model(pending = preset == "offline"),
          memberNames = SmartContentFixtures.names,
          offline = preset == "offline",
          onRemove = {},
          onBack = {},
        )
      }
    }
  }

  // The sheet's CONTENT, not the ModalBottomSheet wrapper: a headless single-frame render
  // never paints a dialog's separate compose scene, so scening the wrapper goldens an empty
  // frame (same reason AvatarPickerContent is split out).
  scene("response-sheet") {
    presets("now", "hub", "scope")
    render { args ->
      themed(args.theme) {
        when (presetName(args.input)) {
          "hub" -> ResponseSheetContent(SmartContentFixtures.sheet(ResponseSurface.HUB), onVerb = {})
          "scope" -> ResponseScopeStep(
            sheet = SmartContentFixtures.sheet(ResponseSurface.NOW).copy(step = ResponseStep.SCOPE),
            onScope = {}, onAudience = {}, onOpenRoutines = {}, onCommit = {},
          )
          else -> ResponseSheetContent(SmartContentFixtures.sheet(ResponseSurface.NOW), onVerb = {})
        }
      }
    }
  }

  scene("smart-briefings") {
    presets(
      "entry", "adult", "provider", "sources", "access", "schedule", "privacy", "handoff", "waiting",
      "active", "partial", "draft", "conflict", "offline", "revoke", "revoke-pending", "revoke-failed", "revoked",
    )
    render { args ->
      val state = when (presetName(args.input)) {
        "entry" -> SmartBriefingsPreviewFixtures.ownerEntry
        "adult" -> SmartBriefingsPreviewFixtures.adultEntry
        "provider" -> SmartBriefingsPreviewFixtures.configure(SmartBriefingsPreviewStep.Provider).copy(provider = null)
        "sources" -> SmartBriefingsPreviewFixtures.configure(SmartBriefingsPreviewStep.Sources)
        "access" -> SmartBriefingsPreviewFixtures.configure(SmartBriefingsPreviewStep.Access)
        "schedule" -> SmartBriefingsPreviewFixtures.configure(SmartBriefingsPreviewStep.Schedule)
        "privacy" -> SmartBriefingsPreviewFixtures.privacy
        "handoff" -> SmartBriefingsPreviewFixtures.handoff
        "waiting" -> SmartBriefingsPreviewFixtures.waiting
        "active" -> SmartBriefingsPreviewFixtures.active
        "partial" -> SmartBriefingsPreviewFixtures.partial
        "draft" -> SmartBriefingsPreviewFixtures.draft
        "conflict" -> SmartBriefingsPreviewFixtures.conflict
        "offline" -> SmartBriefingsPreviewFixtures.offline
        "revoke" -> SmartBriefingsPreviewFixtures.revoke
        "revoke-pending" -> SmartBriefingsPreviewFixtures.revokePending
        "revoke-failed" -> SmartBriefingsPreviewFixtures.revokeFailed
        "revoked" -> SmartBriefingsPreviewFixtures.revoked
        else -> error("unknown smart-briefings preset")
      }
      themed(args.theme) {
        Surface(Modifier.width(411.dp).height(891.dp), color = MaterialTheme.colorScheme.background) {
          Box(Modifier.fillMaxSize()) { SmartBriefingsPreviewScreen(state) }
        }
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

  // ADR 0053 DC5 — the manager People sheet content (see HubPeopleContent's doc comment
  // for why the ModalBottomSheet wrapper itself isn't scened, mirrors AvatarPickerContent).
  scene("hub-people") {
    defaults { height = 380 }
    presets("manager")
    render { args ->
      themedSurface(args.theme) {
        val audience = HubAudience(
          visibility = "restricted",
          canManage = true,
          members = listOf(
            HubAudienceMember(uid = "u_maya", displayName = "Maya Jackson", role = "adult", permitted = true, participationRole = "co_owner", isAuthor = true),
            HubAudienceMember(uid = "u_leo", displayName = "Leo Jackson", role = "child", permitted = true, participationRole = "viewer", avatarRef = "avatar:leaf-01"),
            HubAudienceMember(uid = "u_sam", displayName = "Sam Rivera", role = "adult", permitted = true, participationRole = "contributor"),
          ),
        )
        HubPeopleContent(audience, onSetRole = { _, _ -> }, onRemove = {}, onSetVisibility = {})
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
          "entercode" -> EnterCodeScreen(AppState(navigation = NavigationState(route = Route.EnterCode)))
          "entercode-error" -> EnterCodeScreen(AppState(navigation = NavigationState(route = Route.EnterCode), devices = DeviceState(error = "Too many tries — wait about 15 minutes.")))
          "entercode-scan" -> EnterCodeScreen(AppState(navigation = NavigationState(route = Route.EnterCode)), onScan = {})
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
        TimelineDetail(tl = tl, scale = scale, nowIso = SnapshotStates.TIMELINE_NOW, tz = NY, onBack = {}, onAction = {}, autoScrollToNow = false)
      }
    }
  }

  // ── Calendar Check (WI-447, ADR 0063) — settings/permission/prefill/return surfaces ──────
  scene("calendar-settings") {
    presets("off", "on")
    render { args ->
      themed(args.theme) {
        when (presetName(args.input)) {
          "off" -> CalendarSettingsOffScreen(onBack = {}, onSetUp = {})
          "on" -> CalendarSettingsOnScreen(
            includedCalendars = SnapshotStates.CALENDAR_ACCOUNTS,
            lastCheckLabel = "Today, 9:32 AM",
            onBack = {}, onToggleOff = {}, onChangeCalendars = {}, onEventTimeAlerts = {}, onResetLocalMatches = {}, onTurnOff = {},
          )
          else -> error("unknown calendar-settings preset")
        }
      }
    }
  }

  scene("calendar-primer") {
    presets("default")
    render { args -> themed(args.theme) { CalendarPrimerScreen(onNotNow = {}, onContinue = {}) } }
  }

  scene("calendar-chooser") {
    presets("default")
    render { args ->
      themed(args.theme) {
        CalendarChooserScreen(
          groups = listOf(
            CalendarAccountGroup("Google · p•••@gmail.com", SnapshotStates.CALENDAR_ACCOUNTS + DeviceCalendar("birthdays", "Birthdays", "#B98A4A", "Google · p•••@gmail.com")),
            CalendarAccountGroup("Work · masked account", listOf(DeviceCalendar("work", "Work", "#8A8F98", "Work · masked account"))),
          ),
          selectedIds = setOf("family", "personal"),
          onBack = {}, onToggle = {}, onIncludeSelected = {},
        )
      }
    }
  }

  scene("calendar-denied") {
    presets("default")
    render { args -> themed(args.theme) { CalendarDeniedScreen(onBack = {}, onOpenSettings = {}, onKeepOff = {}) } }
  }

  scene("calendar-no-calendars") {
    presets("default")
    render { args -> themed(args.theme) { CalendarNoCalendarsScreen(onBack = {}, onDoneForNow = {}) } }
  }

  scene("calendar-prefill") {
    presets("default")
    render { args ->
      themed(args.theme) {
        PrefillSheetContent(
          eventTitle = "Maya's dance recital",
          rows = listOf(
            PrefillRow(Icons.Filled.Title, "TITLE", "Maya's dance recital"),
            PrefillRow(Icons.Filled.Schedule, "STARTS", "Sat, Jun 27 · 3:00 PM · Pacific Time"),
            PrefillRow(Icons.Filled.HourglassBottom, "ENDS", "No end time yet", incomplete = true),
            PrefillRow(Icons.Filled.LocationOn, "LOCATION", "Cascade Community Theater, 410 Alder St"),
            PrefillRow(Icons.Filled.Notes, "NOTES", "Doors 2:30. Costume bag + tights. + a link back to the Hub"),
          ),
          onCancel = {}, onOpenCalendarApp = {},
        )
      }
    }
  }

  scene("calendar-return") {
    presets("checking", "added", "canceled", "permission-changed", "unconfirmed")
    render { args ->
      val phase = when (presetName(args.input)) {
        "checking" -> CalendarReturnPhase.CHECKING
        "added" -> CalendarReturnPhase.ADDED
        "canceled" -> CalendarReturnPhase.CANCELED
        "permission-changed" -> CalendarReturnPhase.PERMISSION_CHANGED
        "unconfirmed" -> CalendarReturnPhase.UNCONFIRMED
        else -> error("unknown calendar-return preset")
      }
      themed(args.theme) {
        CalendarReturnScreen(
          phase = phase, hubTitle = "Maya's dance recital",
          dateLabel = "Saturday, June 27", timeRangeLabel = "3:00 – 5:00 PM", locationLabel = "Cascade Community Theater",
          matchedCalendarName = if (phase == CalendarReturnPhase.ADDED) "Family · Sat 3:00 PM" else null,
          onBack = {},
        )
      }
    }
  }

  scene("calendar-reset") {
    presets("default")
    render { args -> themed(args.theme) { ResetLocalMatchesSheetContent(onCancel = {}, onReset = {}) } }
  }

  // ── Calendar Check (WI-446, ADR 0063) — Now card + review flow ───────────────────────────
  scene("calendar-now") {
    presets("two-gaps", "busy", "all-clear", "offline")
    render { args ->
      val p = presetName(args.input)
      themedSurface(args.theme) {
        Box(Modifier.padding(16.dp)) {
          SnapshotStates.calendarNowItem(p)?.let { CalendarCheckNowCard(it, onReview = {}) }
          SnapshotStates.calendarNowFooter(p)?.let { CalendarCheckFooterLine(it) }
        }
      }
    }
  }

  scene("calendar-review") {
    presets("list")
    render { args ->
      themed(args.theme) {
        CalendarReviewListScreen(
          ui = SnapshotStates.calendarReviewList(presetName(args.input)), compareLabel = "Compared on this phone · just now",
          onBack = {}, onAddToCalendar = {}, onIgnoreDayfoldOnly = {}, onOpenHub = {}, onOpenNeedsReview = {},
          onKeepCalendarOnly = {}, onAddToHub = {}, onOpenIgnored = {},
        )
      }
    }
  }

  scene("calendar-match") {
    presets("suggested", "ambiguous")
    render { args ->
      themed(args.theme) {
        when (presetName(args.input)) {
          "suggested" -> CalendarSuggestedMatchScreen(SnapshotStates.CALENDAR_SUGGESTED, onBack = {}, onKeepSeparate = {}, onConfirmMatch = {})
          "ambiguous" -> CalendarAmbiguousMatchScreen(SnapshotStates.CALENDAR_AMBIGUOUS, onBack = {}, onLeaveUnresolved = {}, onMatchSelected = {})
          else -> error("unknown calendar-match preset")
        }
      }
    }
  }

  scene("calendar-differ") {
    presets("default")
    render { args -> themed(args.theme) { CalendarDetailsDifferScreen(SnapshotStates.CALENDAR_DIFFER, onBack = {}, onFieldChoice = { _, _ -> }) } }
  }

  scene("calendar-matched") {
    presets("default")
    render { args ->
      themed(args.theme) {
        CalendarMatchedSummaryScreen(
          hubTitle = "Soccer — Leo", monthAbbrev = "Jun", dayNumber = "20", dateLabel = "Saturday, June 20",
          timeLocationLabel = "4:00 PM · Riverside Park", calendarName = "Family", calendarDotColor = "#7B9E6B",
          lastCheckedLabel = "Last checked today, 9:32 AM · on this phone",
          onBack = {}, onOpenInCalendar = {}, onUnlink = {},
          checklistTitle = "GETTING READY · 1 OF 3",
          checklistItems = listOf(
            MatchedChecklistItem("Wash the jersey", done = false),
            MatchedChecklistItem("Orange slices", done = false),
            MatchedChecklistItem("Pack cleats", done = true),
          ),
        )
      }
    }
  }

  // ── CAL-10 (ADR 0063 §6) — reviewed Calendar→Dayfold import wizard ──
  scene("calendar-import") {
    presets("destination", "fields", "audience-new", "audience-existing", "confirm", "apply-saved", "apply-conflict")
    render { args ->
      val proposal = CalendarImportProposal(
        proposalId = "prop-1", title = "Grandma's 80th lunch",
        start = EventInstant.Timed("2026-06-28T12:00:00-07:00"), end = EventInstant.Timed("2026-06-28T14:00:00-07:00"),
        timezone = "Pacific Time", location = StructuredLocation("Harvest Table", "88 Vine St"),
      )
      themedSurface(args.theme) {
        run {
          when (presetName(args.input)) {
            "destination" -> CalendarImportDestinationScreen(
              existingHubs = listOf(ImportDestinationRow("hub-beach", "Beach Week", "contributor"), ImportDestinationRow("hub-leo", "Leo's birthday", "co_owner")),
              onChooseNewHub = {}, onChooseExistingHub = {}, onClose = {},
            )
            "fields" -> CalendarImportFieldsScreen(
              proposal = proposal, descriptionAvailable = true, descriptionIncluded = false,
              onToggleDescription = {}, onBack = {}, onNext = {},
            )
            "audience-new" -> CalendarImportAudienceNewHubScreen(
              visibility = HubVisibilityChoice.RESTRICTED, familyMemberNames = "Pat, Maya, Leo and Sam can see it",
              onSelect = {}, onBack = {}, onNext = {},
            )
            "audience-existing" -> CalendarImportAudienceExistingHubScreen(
              hubTitle = "Beach Week", role = "contributor", namedAudience = listOf("Pat", "Maya", "Leo", "Sam"),
              widerThanSource = true, onBack = {}, onNext = {},
            )
            "confirm" -> CalendarImportConfirmScreen(
              proposal = proposal, audienceLine = ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("u1")).audienceLine(),
              confirmLabel = "Create the Hub",
              onBack = {}, onConfirm = {},
            )
            "apply-saved" -> CalendarImportApplyScreen(
              kind = ImportApplyKind.SAVED, title = "Hub created — only you can see it", meta = "Grandma's 80th lunch · Sun, Jun 28",
              body = "The event and your new Hub are linked on this phone.", diffs = emptyList(),
              footer = "Sharing later names exactly who will see it.",
              actions = listOf(ImportApplyAction("Open Hub", true, {}), ImportApplyAction("Share with family", false, {})),
            )
            "apply-conflict" -> CalendarImportApplyScreen(
              kind = ImportApplyKind.VERSION_CONFLICT, title = "Beach Week changed while you reviewed", meta = null,
              body = "Someone edited that Hub since you started. Refresh, then confirm against the current version.",
              diffs = emptyList(), footer = "Dayfold never merges silently.",
              actions = listOf(ImportApplyAction("Refresh and re-confirm", true, {}), ImportApplyAction("Discard import", false, {})),
            )
            else -> error("unknown calendar-import preset")
          }
        }
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
