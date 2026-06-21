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
  @Test fun signInBusy() = snap("auth-signin-busy") { SignInScreen(busy = true) }
  @Test fun signInError() = snap("auth-signin-error") { SignInScreen(error = "Couldn't reach Dayfold. Try again.") }
  @Test fun createFamily() = snap("auth-createfamily") { CreateFamilyScreen(initialName = "The Jacksons") }
  @Test fun createFamilyDark() = snap("auth-createfamily-dark", dark = true) { CreateFamilyScreen(initialName = "The Jacksons") }
  @Test fun familyNull() = snap("auth-familynull") { FamilyNullState() }
  @Test fun familyNullDark() = snap("auth-familynull-dark", dark = true) { FamilyNullState() }
  @Test fun splash() = snap("auth-splash") { SplashScreen() }

  private val acctState = AppState(
    session = Session("a", "r"),
    families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
    activeFamilyId = "fam1", route = Route.Account,
  )
  @Test fun account() = snap("auth-account") { AccountScreen(acctState) }
  @Test fun accountDark() = snap("auth-account-dark", dark = true) { AccountScreen(acctState) }

  // invitee-join (slice-2b)
  @Test fun joinEntry() = snap("auth-join-entry") { JoinInviteScreen(AppState(route = Route.JoinInvite)) }
  @Test fun joinWaiting() = snap("auth-join-waiting") {
    JoinInviteScreen(AppState(route = Route.JoinInvite, joinOutcome = "waiting", joinFamilyName = "The Riveras"))
  }
  @Test fun joinLocked() = snap("auth-join-locked") {
    JoinInviteScreen(AppState(route = Route.JoinInvite, joinOutcome = "locked"))
  }
  @Test fun joinError() = snap("auth-join-error", dark = true) {
    JoinInviteScreen(AppState(route = Route.JoinInvite, joinOutcome = "error"))
  }

  @Test fun members() = snap("auth-members") {
    MembersScreen(AppState(
      families = listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")),
      activeFamilyId = "fam1",
      pendingApprovals = listOf(PendingMember("u9", "Sam Rivera")),
      members = listOf(
        FamilyMember("u1", "Pat Jackson", role = "owner", status = "active"),
        FamilyMember("u2", "Maya Jackson", role = "adult", status = "active"),
      ),
    ))
  }

  @Test fun devices() = snap("auth-devices") {
    DevicesScreen(AppState(devices = listOf(
      DeviceCredential("c1", kind = "app", label = "iPhone 15 Pro", current = true),
      DeviceCredential("c2", kind = "cli", label = "claude-code · CI", lastUsedAt = "2026-06-19T09:00:00Z", lastUsedIp = "San Jose"),
    )))
  }
}
