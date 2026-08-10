package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

// WI-461 (ADR 0063) — the wiring between the Calendar Check entry point and the effect it names.
// This is the exact bug class the epic shipped with: CalendarSettingsHost/CalendarReviewHost, the
// Route, the reducer arms, and the Now card's onOpenCalendarReview param all existed — and nothing
// connected them, which no unit test on any one piece would have caught (mirrors ResponseWiringTest).
@OptIn(ExperimentalTestApi::class)
class CalendarEntryPointWiringTest {
  private fun accountState() = AccountViewState(
    activeFamily = null, pendingApprovalCount = 0, displayName = "Zoe", avatarColor = null,
    avatarRef = null, avatarBusy = false, avatarError = null, nameError = null,
    proximityEnabled = false, signOutBusy = false, calendarCheckEnabled = false,
  )

  @Test fun theSettingsRowIsAbsentWhenTheEntryPointFlagIsOff() = runComposeUiTest {
    setContent {
      MaterialTheme { AccountScreen(state = accountState(), calendarCheckAvailable = false) }
    }
    onNodeWithTag("account-calendar-check").assertDoesNotExist()
  }

  @Test fun tappingTheSettingsRowDispatchesTheOpenAction() = runComposeUiTest {
    var opened = false
    setContent {
      MaterialTheme {
        AccountScreen(
          state = accountState(),
          calendarCheckAvailable = true,
          onOpenCalendarSettings = { opened = true },
        )
      }
    }
    onNodeWithTag("account-calendar-check").performClick()
    assertTrue(opened)
  }

  private fun calendarCheckCandidate(subjectKey: String, title: String) =
    DayfoldEventCandidate(subjectKey, title, "2026-08-10T09:00:00Z", null, false, "UTC", null, "v1", null)

  // End-to-end through the real store + FeedApp: the Now card's "Review" pill must actually open
  // CalendarReviewHost, not silently no-op into the default {} (the exact regression this WI fixes —
  // FeedScreen/ContentHost/FeedApp each already had the pieces; nothing threaded them together).
  @Test fun theNowCardReviewTapOpensTheRealReviewFlow() = runComposeUiTest {
    val calendar = CalendarState(
      check = CalendarCheckState(
        results = ReconcileResult(dayfoldOnly = listOf(calendarCheckCandidate("hub:h1", "Dentist"))),
      ),
    )
    val store = createTestAppStore(
      AppState(navigation = NavigationState(route = Route.Feed), calendar = calendar),
      debug = false,
    )
    setContent { TestFeedApp(store) }

    onNodeWithContentDescription("Review calendar check items").performClick()

    assertTrue(store.state.calendar.check.reviewOpen)
    onNodeWithText("Compared on this phone").assertIsDisplayed()
  }
}
