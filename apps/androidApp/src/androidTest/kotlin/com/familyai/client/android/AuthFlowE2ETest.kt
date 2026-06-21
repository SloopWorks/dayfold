package com.familyai.client.android

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.familyai.client.AppState
import com.familyai.client.ApprovalsLoaded
import com.familyai.client.FamilyCreated
import com.familyai.client.FamilyMembership
import com.familyai.client.FeedApp
import com.familyai.client.InviteRedeemed
import com.familyai.client.MemberResolved
import com.familyai.client.MembershipsLoaded
import com.familyai.client.PendingMember
import com.familyai.client.RedeemRequested
import com.familyai.client.Route
import com.familyai.client.Session
import com.familyai.client.SignInSucceeded
import com.familyai.client.SignedOut
import com.familyai.client.createAppStore
import com.familyai.client.theme.DayfoldTheme
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
          onApproveMember = { uid -> store.dispatch(MemberResolved(uid)) },
        )
      }
    }
    rule.onNodeWithText("Continue with Google").performClick()
    rule.onNodeWithText("Y").performClick()                     // Feed → account
    rule.onNodeWithText("Members & approvals").assertIsDisplayed().performClick()
    rule.onNodeWithText("Sam Rivera").assertIsDisplayed()       // queue loaded
    rule.onNodeWithTag("approve-u9").performClick()
    rule.onAllNodesWithText("Sam Rivera").assertCountEquals(0)  // approved → dropped
  }
}
