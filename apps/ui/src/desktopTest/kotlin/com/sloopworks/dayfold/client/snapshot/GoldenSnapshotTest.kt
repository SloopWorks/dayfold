package com.sloopworks.dayfold.client.snapshot

import org.reduxkotlin.snapshot.assertGolden
import java.io.File
import kotlin.test.Test

// Committed-golden regression gate with PER-OS golden sets (snapshots/macos + snapshots/linux).
// Why: macOS (CoreText) and linux (FreeType) rasterize the same fonts with slightly different
// glyph advances — bold-dense scenes drift 2-3%, and long paragraphs can flip a line-wrap,
// shifting whole layouts (measured up to 22%). Cross-OS tolerance can't gate that honestly,
// so each OS verifies goldens recorded on that OS: macos = the local dev/agent loop,
// linux = CI (ubuntu). The 4% tolerance absorbs same-OS/cross-arch Skiko AA only.
// Re-record after an INTENTIONAL visual change:
//   macOS:  cd apps && ./gradlew :client:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true
//   linux:  same command inside an ubuntu container (see processes/agent-dev-loop.md)
// then EYEBALL the changed PNGs before committing.
class GoldenSnapshotTest {
  private fun golden(scene: String, preset: String, theme: String? = null) {
    val name = if (theme != null) "$scene-$preset-$theme" else "$scene-$preset"
    clientSnapshots.assertGolden(
      scene = scene, preset = preset, theme = theme,
      goldenDir = GOLDEN_DIR, name = name, maxDiffPercent = 4.0,
    )
  }

  // ── feed ──────────────────────────────────────────────────────────────────
  @Test fun feedBusy() = golden("feed", "busy")
  @Test fun feedBusyDark() = golden("feed", "busy", theme = "dark")
  @Test fun feedTyped() = golden("feed", "typed")
  @Test fun feedTypedDark() = golden("feed", "typed", theme = "dark")
  @Test fun feedEmpty() = golden("feed", "empty")
  @Test fun feedCaughtUp() = golden("feed", "caught-up")
  @Test fun feedCaughtUpDark() = golden("feed", "caught-up", theme = "dark")
  @Test fun feedSyncing() = golden("feed", "syncing")
  @Test fun feedOffline() = golden("feed", "offline")
  @Test fun feedOfflineDark() = golden("feed", "offline", theme = "dark")
  @Test fun feedEnriched() = golden("feed", "enriched")
  @Test fun feedEnrichedPair() = golden("feed", "enriched-pair")
  @Test fun feedEnrichedPairDark() = golden("feed", "enriched-pair", theme = "dark")
  @Test fun feedInviteNone() = golden("feed", "invite-none")
  @Test fun feedInviteYes() = golden("feed", "invite-yes")
  @Test fun feedInviteNo() = golden("feed", "invite-no")

  // ── detail (typed cards) ──────────────────────────────────────────────────
  @Test fun detailFile() = golden("detail", "file")
  @Test fun detailFileDark() = golden("detail", "file", theme = "dark")
  @Test fun detailLink() = golden("detail", "link")
  @Test fun detailLinkDark() = golden("detail", "link", theme = "dark")
  @Test fun detailInvite() = golden("detail", "invite")
  @Test fun detailInviteDark() = golden("detail", "invite", theme = "dark")
  @Test fun detailContact() = golden("detail", "contact")
  @Test fun detailContactDark() = golden("detail", "contact", theme = "dark")
  @Test fun detailGeo() = golden("detail", "geo")
  @Test fun detailGeoDark() = golden("detail", "geo", theme = "dark")
  @Test fun detailEmail() = golden("detail", "email")
  @Test fun detailEmailDark() = golden("detail", "email", theme = "dark")

  // ── hub detail ────────────────────────────────────────────────────────────
  @Test fun hubCanonical() = golden("hub-detail", "canonical")
  @Test fun hubCanonicalDark() = golden("hub-detail", "canonical", theme = "dark")
  @Test fun hubEnriched() = golden("hub-detail", "enriched")
  @Test fun hubEnrichedDark() = golden("hub-detail", "enriched", theme = "dark")
  @Test fun hubChecklist() = golden("hub-detail", "checklist")
  @Test fun hubChecklistDark() = golden("hub-detail", "checklist", theme = "dark")
  @Test fun hubEnrichedLogo() = golden("hub-detail", "enriched-logo")
  @Test fun hubEnrichedLogoDark() = golden("hub-detail", "enriched-logo", theme = "dark")
  @Test fun hubEnrichedPhoto() = golden("hub-detail", "enriched-photo")
  @Test fun hubTimelineCard() = golden("hub-detail", "timeline-card")
  @Test fun hubTimelineOverlay() = golden("hub-detail", "timeline-overlay")
  @Test fun hubTimelineHidden() = golden("hub-detail", "timeline-hidden")
  @Test fun hubTimelineNudge() = golden("hub-detail", "timeline-nudge")
  @Test fun hubDerivedTimeline() = golden("hub-detail", "derived-timeline")

  // ── hub list ──────────────────────────────────────────────────────────────
  @Test fun hubListEnriched() = golden("hub-list", "enriched")
  @Test fun hubListEnrichedDark() = golden("hub-list", "enriched", theme = "dark")

  // ── auth / onboarding ─────────────────────────────────────────────────────
  @Test fun authSignIn() = golden("auth", "signin")
  @Test fun authSignInDark() = golden("auth", "signin", theme = "dark")
  @Test fun authSignInBusy() = golden("auth", "signin-busy")
  @Test fun authSignInError() = golden("auth", "signin-error")
  @Test fun authCreateFamily() = golden("auth", "createfamily")
  @Test fun authCreateFamilyDark() = golden("auth", "createfamily", theme = "dark")
  @Test fun authFamilyNull() = golden("auth", "familynull")
  @Test fun authFamilyNullDark() = golden("auth", "familynull", theme = "dark")
  @Test fun authSplash() = golden("auth", "splash")

  // ── account ───────────────────────────────────────────────────────────────
  @Test fun accountDefault() = golden("account", "default")
  @Test fun accountDefaultDark() = golden("account", "default", theme = "dark")
  @Test fun accountSignOutBusy() = golden("account", "signout-busy")

  // ── invitee join ──────────────────────────────────────────────────────────
  @Test fun joinEntry() = golden("join", "entry")
  @Test fun joinWaiting() = golden("join", "waiting")
  @Test fun joinLocked() = golden("join", "locked")
  @Test fun joinErrorDark() = golden("join", "error", theme = "dark")

  // ── members ───────────────────────────────────────────────────────────────
  @Test fun membersRoster() = golden("members", "roster")
  @Test fun membersRosterDark() = golden("members", "roster", theme = "dark")
  @Test fun membersLoading() = golden("members", "loading")
  @Test fun membersError() = golden("members", "error")
  @Test fun membersRowBusy() = golden("members", "row-busy")

  // ── devices ───────────────────────────────────────────────────────────────
  @Test fun devicesList() = golden("devices", "list")
  @Test fun devicesListDark() = golden("devices", "list", theme = "dark")
  @Test fun devicesLoading() = golden("devices", "loading")
  @Test fun devicesError() = golden("devices", "error")
  @Test fun devicesRowBusy() = golden("devices", "row-busy")

  // ── CLI/device approval ───────────────────────────────────────────────────
  @Test fun approvalEnterCode() = golden("device-approval", "entercode")
  @Test fun approvalEnterCodeDark() = golden("device-approval", "entercode", theme = "dark")
  @Test fun approvalEnterCodeError() = golden("device-approval", "entercode-error")
  @Test fun approvalEnterCodeScan() = golden("device-approval", "entercode-scan")
  @Test fun approvalEnterCodeScanDark() = golden("device-approval", "entercode-scan", theme = "dark")
  @Test fun approvalAuthorizeDatacenter() = golden("device-approval", "authorize-datacenter")
  @Test fun approvalAuthorizeDatacenterDark() = golden("device-approval", "authorize-datacenter", theme = "dark")
  @Test fun approvalAuthorizeResidential() = golden("device-approval", "authorize-residential")
  @Test fun approvalAuthorizeMultiOwner() = golden("device-approval", "authorize-multiowner")
  @Test fun approvalDenied() = golden("device-approval", "denied")
  @Test fun approvalDeniedDark() = golden("device-approval", "denied", theme = "dark")
  @Test fun approvalExpired() = golden("device-approval", "expired")
  @Test fun approvalExpiredDark() = golden("device-approval", "expired", theme = "dark")
  @Test fun approvalApproved() = golden("device-approval", "approved")
  @Test fun approvalResume() = golden("device-approval", "resume")
  @Test fun approvalResumeDark() = golden("device-approval", "resume", theme = "dark")
  @Test fun approvalFinishing() = golden("device-approval", "finishing")

  // ── scan ──────────────────────────────────────────────────────────────────
  @Test fun scanPrimer() = golden("scan", "primer")
  @Test fun scanPrimerDark() = golden("scan", "primer", theme = "dark")
  @Test fun scanDevice() = golden("scan", "device")
  @Test fun scanDenied() = golden("scan", "denied")
  @Test fun scanDeniedDark() = golden("scan", "denied", theme = "dark")

  // ── Phase-B device glue ───────────────────────────────────────────────────
  @Test fun notifQuietHeld() = golden("notif", "quiet-held")
  @Test fun notifQuietHeldDark() = golden("notif", "quiet-held", theme = "dark")
  @Test fun notifCapReached() = golden("notif", "cap-reached")
  @Test fun notifCapReachedDark() = golden("notif", "cap-reached", theme = "dark")
  @Test fun privacyAffordance() = golden("privacy", "affordance")
  @Test fun privacyAffordanceDark() = golden("privacy", "affordance", theme = "dark")
  @Test fun placesList() = golden("places", "list")
  @Test fun placesListDark() = golden("places", "list", theme = "dark")
  @Test fun placesEmpty() = golden("places", "empty")
  @Test fun proximityOn() = golden("proximity", "on")
  @Test fun proximityOnDark() = golden("proximity", "on", theme = "dark")
  @Test fun proximityOff() = golden("proximity", "off")
  @Test fun proximityDeregistering() = golden("proximity", "deregistering")
  @Test fun permissionLocPrime() = golden("permission", "locprime")
  @Test fun permissionLocPrimeDark() = golden("permission", "locprime", theme = "dark")
  @Test fun permissionAlways() = golden("permission", "always")
  @Test fun permissionNotif() = golden("permission", "notif")
  @Test fun permissionLimited() = golden("permission", "limited")
  @Test fun permissionDenied() = golden("permission", "denied")
  @Test fun permissionDowngraded() = golden("permission", "downgraded")
  @Test fun permissionDowngradedDark() = golden("permission", "downgraded", theme = "dark")
  @Test fun offlineBanner() = golden("offline-banner", "default")
  @Test fun offlineBannerDark() = golden("offline-banner", "default", theme = "dark")

  // ── loading/error kit ─────────────────────────────────────────────────────
  @Test fun kitListSkeleton() = golden("kit", "list-skeleton")
  @Test fun kitFeedSkeleton() = golden("kit", "feed-skeleton")
  @Test fun kitErrorRetry() = golden("kit", "error-retry")
  @Test fun kitErrorRetryBusy() = golden("kit", "error-retry-busy")
  @Test fun kitEmptyState() = golden("kit", "empty-state")

  // ── timelines (ADR 0045/0046) ─────────────────────────────────────────────
  @Test fun timelineCardDay() = golden("timeline-card", "day")
  @Test fun timelineCardDayDark() = golden("timeline-card", "day", theme = "dark")
  @Test fun timelineCardHub() = golden("timeline-card", "hub")
  @Test fun timelineCardHubDark() = golden("timeline-card", "hub", theme = "dark")
  @Test fun timelineCardHubCollapsed() = golden("timeline-card", "hub-collapsed")
  @Test fun timelineCardHubCollapsedDark() = golden("timeline-card", "hub-collapsed", theme = "dark")
  @Test fun timelineCardDerived() = golden("timeline-card", "derived")
  @Test fun timelineDetailDay() = golden("timeline-detail", "day")
  @Test fun timelineDetailHub() = golden("timeline-detail", "hub")
  @Test fun timelineDetailBothToggle() = golden("timeline-detail", "both-toggle")
  @Test fun timelineDetailDerived() = golden("timeline-detail", "derived")

  companion object {
    private val osTag = if (System.getProperty("os.name").lowercase().contains("mac")) "macos" else "linux"
    val GOLDEN_DIR = File("src/desktopTest/resources/snapshots/$osTag")
  }
}
