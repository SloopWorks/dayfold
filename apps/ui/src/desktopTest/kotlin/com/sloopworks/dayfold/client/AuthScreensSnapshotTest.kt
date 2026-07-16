package com.sloopworks.dayfold.client

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

// AUTH-S5 T5 — render each onboarding screen off-screen under DayfoldTheme and
// write the PNG to build/snapshots/ so the screens can be eyeballed against the
// Dayfold mockups (signin / createfamily / familynull) without a device.
@OptIn(ExperimentalTestApi::class)
class AuthScreensSnapshotTest {
  private fun snap(name: String, dark: Boolean = false, content: @Composable () -> Unit) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { content() } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "snapshot has no pixels")
    val dir = File("build/snapshots").apply { mkdirs() }
    ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png"))
  }

  @Test fun signIn() = snap("auth-signin") { SignInScreen() }
  @Test fun signInDark() = snap("auth-signin-dark", dark = true) { SignInScreen() }
  @Test fun signInBusy() = snap("auth-signin-busy") { SignInScreen(pendingProvider = "google") }
  @Test fun signInError() = snap("auth-signin-error") { SignInScreen(error = "Couldn't reach Dayfold. Try again.") }
  @Test fun createFamily() = snap("auth-createfamily") { CreateFamilyScreen(initialName = "The Jacksons") }
  @Test fun createFamilyDark() = snap("auth-createfamily-dark", dark = true) { CreateFamilyScreen(initialName = "The Jacksons") }
  @Test fun familyNull() = snap("auth-familynull") { FamilyNullState() }
  @Test fun familyNullDark() = snap("auth-familynull-dark", dark = true) { FamilyNullState() }
  @Test fun splash() = snap("auth-splash") { SplashScreen() }

  private val acctState = AppState(
    session = SessionState(
      session = Session("a", "r"),
      families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
      activeFamilyId = "fam1",
    ),
    navigation = NavigationState(route = Route.Account),
  )
  @Test fun account() = snap("auth-account") { AccountScreen(acctState) }
  @Test fun accountDark() = snap("auth-account-dark", dark = true) { AccountScreen(acctState) }
  @Test fun accountSignOutBusy() = snap("auth-account-signout-busy") { AccountScreen(acctState, signOutBusy = true) }

  // invitee-join (slice-2b)
  @Test fun joinEntry() = snap("auth-join-entry") { JoinInviteScreen(AppState(navigation = NavigationState(route = Route.JoinInvite))) }
  @Test fun joinWaiting() = snap("auth-join-waiting") {
    JoinInviteScreen(AppState(session = SessionState(joinOutcome = "waiting", joinFamilyName = "The Riveras"), navigation = NavigationState(route = Route.JoinInvite)))
  }
  @Test fun joinLocked() = snap("auth-join-locked") {
    JoinInviteScreen(AppState(session = SessionState(joinOutcome = "locked"), navigation = NavigationState(route = Route.JoinInvite)))
  }
  @Test fun joinError() = snap("auth-join-error", dark = true) {
    JoinInviteScreen(AppState(session = SessionState(joinOutcome = "error"), navigation = NavigationState(route = Route.JoinInvite)))
  }

  @Test fun members() = snap("auth-members") {
    MembersScreen(AppState(
      session = SessionState(families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")), activeFamilyId = "fam1"),
      pendingApprovals = listOf(PendingMember("u9", "Sam Rivera")),
      members = listOf(
        FamilyMember("u1", "Pat Jackson", role = "owner", status = "active"),
        FamilyMember("u2", "Maya Jackson", role = "adult", status = "active"),
      ),
    ))
  }
  @Test fun membersLoading() = snap("members-loading") {
    MembersScreen(AppState(
      session = SessionState(families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")), activeFamilyId = "fam1"), rosterBusy = true,
    ))
  }
  @Test fun membersError() = snap("members-error") {
    MembersScreen(AppState(
      session = SessionState(families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")), activeFamilyId = "fam1"), rosterError = "Couldn't load members. Try again.",
    ))
  }
  @Test fun membersRowBusy() = snap("members-row-busy") {
    MembersScreen(AppState(
      session = SessionState(families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")), activeFamilyId = "fam1"),
      pendingApprovals = listOf(PendingMember("u9", "Sam Rivera")),
      members = listOf(FamilyMember("u1", "Pat Jackson", role = "owner", status = "active")),
      memberOpId = "u9",
    ))
  }

  @Test fun devices() = snap("auth-devices") {
    DevicesScreen(AppState(devices = listOf(
      DeviceCredential("c1", kind = "app", label = "iPhone 15 Pro", current = true),
      DeviceCredential("c2", kind = "cli", label = "claude-code · CI", lastUsedAt = "2026-06-19T09:00:00Z", lastUsedIp = "San Jose"),
    )))
  }
  @Test fun devicesLoading() = snap("devices-loading") { DevicesScreen(AppState(deviceListBusy = true)) }
  @Test fun devicesError() = snap("devices-error") { DevicesScreen(AppState(deviceListError = "Couldn't load devices. Try again.")) }
  @Test fun devicesRowBusy() = snap("devices-row-busy") {
    DevicesScreen(AppState(
      devices = listOf(
        DeviceCredential("c1", kind = "app", label = "iPhone 15 Pro", current = true),
        DeviceCredential("c2", kind = "cli", label = "claude-code · CI"),
      ),
      deviceOpId = "c2",
    ))
  }

  // ── CLI/device approval (S6-D) — A8b entercode/authorizedevice/devicedenied/deviceexpired ──
  @Test fun enterCode() = snap("device-entercode") { EnterCodeScreen(AppState(navigation = NavigationState(route = Route.EnterCode))) }
  @Test fun enterCodeDark() = snap("device-entercode-dark", dark = true) { EnterCodeScreen(AppState(navigation = NavigationState(route = Route.EnterCode))) }
  @Test fun enterCodeError() = snap("device-entercode-error") {
    EnterCodeScreen(AppState(navigation = NavigationState(route = Route.EnterCode), deviceError = "Too many tries — wait about 15 minutes."))
  }

  private fun authState(originKind: String, fams: List<FamilyMembership>) = AppState(
    session = SessionState(session = Session("a", "r"), families = fams, activeFamilyId = fams.firstOrNull()?.familyId),
    navigation = NavigationState(route = Route.AuthorizeDevice),
    pendingDevice = PendingDevice("WDJF-7K2P", client = "Dayfold CLI", originIp = "San Jose, CA · US",
      originUa = "dayfold-cli/1.0 · macOS", originKind = originKind),
  )
  private val oneOwner = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active"))
  private val twoOwner = listOf(
    FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active"),
    FamilyMembership("fam2", "Lake House", role = "owner", status = "active"),
  )

  @Test fun authorizeDatacenter() = snap("device-authorize-datacenter") { AuthorizeDeviceScreen(authState("datacenter", oneOwner)) }
  @Test fun authorizeDatacenterDark() = snap("device-authorize-datacenter-dark", dark = true) { AuthorizeDeviceScreen(authState("datacenter", oneOwner)) }
  @Test fun authorizeResidential() = snap("device-authorize-residential") { AuthorizeDeviceScreen(authState("residential", oneOwner)) }
  @Test fun authorizeMultiOwner() = snap("device-authorize-multiowner") { AuthorizeDeviceScreen(authState("residential", twoOwner)) }

  @Test fun deviceDenied() = snap("device-denied") { DeviceDeniedScreen() }
  @Test fun deviceDeniedDark() = snap("device-denied-dark", dark = true) { DeviceDeniedScreen() }
  @Test fun deviceExpired() = snap("device-expired") { DeviceExpiredScreen() }
  @Test fun deviceExpiredDark() = snap("device-expired-dark", dark = true) { DeviceExpiredScreen() }
  @Test fun deviceApproved() = snap("device-approved") { DeviceApprovedConfirm() }

  // ── Phase 2 scan + deep-link (A8b scanprimer/scandevice/scandenied/deviceresume) ──
  @Test fun enterCodeWithScanToggle() = snap("device-entercode-scan") { EnterCodeScreen(AppState(navigation = NavigationState(route = Route.EnterCode)), onScan = {}) }
  @Test fun enterCodeWithScanToggleDark() = snap("device-entercode-scan-dark", dark = true) { EnterCodeScreen(AppState(navigation = NavigationState(route = Route.EnterCode)), onScan = {}) }
  @Test fun scanPrimer() = snap("scan-primer") { ScanPrimerScreen() }
  @Test fun scanPrimerDark() = snap("scan-primer-dark", dark = true) { ScanPrimerScreen() }
  @Test fun scanDevice() = snap("scan-device") { ScanDeviceScreen() }
  @Test fun scanDenied() = snap("scan-denied") { ScanDeniedScreen() }
  @Test fun scanDeniedDark() = snap("scan-denied-dark", dark = true) { ScanDeniedScreen() }
  @Test fun deviceResume() = snap("scan-deviceresume") { DeviceResumeScreen() }
  @Test fun deviceResumeDark() = snap("scan-deviceresume-dark", dark = true) { DeviceResumeScreen() }
  @Test fun deviceFinishing() = snap("scan-devicefinishing") { DeviceFinishingScreen() }
}
