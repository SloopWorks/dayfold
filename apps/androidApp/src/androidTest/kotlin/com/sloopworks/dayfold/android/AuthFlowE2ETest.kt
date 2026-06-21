package com.sloopworks.dayfold.android

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.ApprovalsLoaded
import com.sloopworks.dayfold.client.FamilyCreated
import com.sloopworks.dayfold.client.FamilyMembership
import com.sloopworks.dayfold.client.FamilyMember
import com.sloopworks.dayfold.client.FeedApp
import com.sloopworks.dayfold.client.InviteRedeemed
import com.sloopworks.dayfold.client.MemberResolved
import com.sloopworks.dayfold.client.MemberRemoved
import com.sloopworks.dayfold.client.MembershipsLoaded
import com.sloopworks.dayfold.client.PendingMember
import com.sloopworks.dayfold.client.RedeemRequested
import com.sloopworks.dayfold.client.RosterLoaded
import com.sloopworks.dayfold.client.Route
import com.sloopworks.dayfold.client.Session
import com.sloopworks.dayfold.client.SignInSucceeded
import com.sloopworks.dayfold.client.SignedOut
import com.sloopworks.dayfold.client.DeviceCredential
import com.sloopworks.dayfold.client.DeviceRevoked
import com.sloopworks.dayfold.client.DevicesLoaded
import com.sloopworks.dayfold.client.createAppStore
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import org.junit.Rule
import org.junit.Test

// AUTH-S5 Slice B — instrumented e2e on the emulator. Drives the REAL route gate
// + Dayfold screens through the whole sign-in → create-family → feed → account →
// sign-out loop. Hermetic: the callbacks dispatch the actions the AuthEngine
// would (no network) — the engine's own logic (dev-token, whoami, refresh) is
// covered by the desktop AuthEngineTest. This proves the on-device UI wiring.
class AuthFlowE2ETest {
  @get:Rule val rule = createComposeRule()

  @Test fun signIn_createFamily_feed_account_signOut() {
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    rule.setContent {
      DayfoldTheme {
        FeedApp(
          store,
          onSignIn = {
            store.dispatch(SignInSucceeded(Session("a", "r")))
            store.dispatch(MembershipsLoaded(emptyList()))      // no family yet → CreateFamily
          },
          onCreateFamily = { name -> store.dispatch(FamilyCreated("fam1", name)) },
          onSignOut = { store.dispatch(SignedOut) },
        )
      }
    }

    // 1) Sign in
    rule.onNodeWithText("Continue with Google").assertIsDisplayed().performClick()

    // 2) Onboarding → create the family
    rule.onNodeWithText("Name your family").assertIsDisplayed()
    rule.onNode(hasSetTextAction()).performTextInput("The Jacksons")
    rule.onNodeWithText("Create family").performClick()

    // 3) Feed (empty family → null state); open the account overlay via the monogram
    rule.onNodeWithText("Your family space is ready").assertIsDisplayed()
    rule.onNodeWithText("Y").performClick()

    // 4) Account → sign out → confirm dialog → confirm
    rule.onNodeWithText("Sign out").assertIsDisplayed().performClick()
    rule.onNodeWithText("Sign out?").assertIsDisplayed()
    rule.onNodeWithTag("confirm-signout").performClick()

    // 5) Back at the sign-in screen — the loop is closed
    rule.onNodeWithText("Continue with Google").assertIsDisplayed()
  }

  @Test fun signIn_joinByInvite_waitsForApproval() {
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    rule.setContent {
      DayfoldTheme {
        FeedApp(
          store,
          onSignIn = {
            store.dispatch(SignInSucceeded(Session("a", "r")))
            store.dispatch(MembershipsLoaded(emptyList()))
          },
          onRedeemInvite = { token ->
            store.dispatch(RedeemRequested(token))
            store.dispatch(InviteRedeemed("The Riveras"))
          },
        )
      }
    }

    rule.onNodeWithText("Continue with Google").performClick()
    rule.onNodeWithText("Name your family").assertIsDisplayed()
    rule.onNodeWithText("Have an invite? Join a family").performClick()
    rule.onNodeWithText("Join a family").assertIsDisplayed()
    rule.onNode(hasSetTextAction()).performTextInput("INVITE-TOKEN-123")
    rule.onNodeWithText("Join").performClick()
    rule.onNodeWithText("Almost in").assertIsDisplayed()   // waiting for owner approval
    rule.onNodeWithText("Done").performClick()
    rule.onNodeWithText("Name your family").assertIsDisplayed()
  }

  @Test fun owner_approvesPendingMember() {
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    rule.setContent {
      DayfoldTheme {
        FeedApp(
          store,
          onSignIn = {
            store.dispatch(SignInSucceeded(Session("a", "r")))
            store.dispatch(MembershipsLoaded(listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active"))))
          },
          onLoadApprovals = { store.dispatch(ApprovalsLoaded(listOf(PendingMember("u9", "Sam Rivera")))) },
          onLoadMembers = {
            store.dispatch(RosterLoaded(listOf(
              FamilyMember("u1", "Pat Jackson", role = "owner"), FamilyMember("u2", "Maya Jackson", role = "adult"),
            )))
          },
          onApproveMember = { uid -> store.dispatch(MemberResolved(uid)) },
          onRemoveMember = { uid -> store.dispatch(MemberRemoved(uid)) },
        )
      }
    }
    rule.onNodeWithText("Continue with Google").performClick()
    rule.onNodeWithText("Y").performClick()                     // Feed → account
    rule.onNodeWithText("Members & approvals").assertIsDisplayed().performClick()
    rule.onNodeWithText("Sam Rivera").assertIsDisplayed()       // pending queue loaded
    rule.onNodeWithText("Maya Jackson").assertIsDisplayed()     // active roster loaded
    rule.onNodeWithTag("approve-u9").performClick()
    rule.onAllNodesWithText("Sam Rivera").assertCountEquals(0)  // approved → dropped
    rule.onNodeWithTag("remove-u2").performClick()
    rule.onAllNodesWithText("Maya Jackson").assertCountEquals(0) // removed → dropped
  }

  @Test fun account_revokesConnectedDevice() {
    val store = createAppStore(AppState(route = Route.SignIn), debug = false)
    rule.setContent {
      DayfoldTheme {
        FeedApp(
          store,
          onSignIn = {
            store.dispatch(SignInSucceeded(Session("a", "r")))
            store.dispatch(MembershipsLoaded(listOf(FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active"))))
          },
          onLoadDevices = {
            store.dispatch(DevicesLoaded(listOf(
              DeviceCredential("c1", kind = "app", label = "iPhone", current = true),
              DeviceCredential("c2", kind = "cli", label = "claude-code"),
            )))
          },
          onRevokeDevice = { id -> store.dispatch(DeviceRevoked(id)) },
        )
      }
    }
    rule.onNodeWithText("Continue with Google").performClick()
    rule.onNodeWithText("Y").performClick()
    rule.onNodeWithText("Connected devices").assertIsDisplayed().performClick()
    rule.onNodeWithText("claude-code").assertIsDisplayed()
    rule.onNodeWithTag("revoke-c2").performClick()
    rule.onAllNodesWithText("claude-code").assertCountEquals(0)   // revoked → dropped
  }
}
