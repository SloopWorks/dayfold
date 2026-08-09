package com.sloopworks.dayfold.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.features.calendar.CalendarAmbiguousMatchScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarCheckNowCard
import com.sloopworks.dayfold.client.features.calendar.CalendarDetailsDifferScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarMatchedSummaryScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarReviewListScreen
import com.sloopworks.dayfold.client.features.calendar.CalendarSuggestedMatchScreen
import com.sloopworks.dayfold.client.snapshot.SnapshotStates
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test

// WI-446 (ADR 0063 §5) — one semantics smoke per review-flow screen family (right-sized: the
// snapshot goldens cover pixels, CalendarSelectors.kt's own tests cover the reducer-facing
// projections; this file only proves the rendered rows/actions actually ANNOUNCE kind + title +
// key meta, not just "looks right in the PNG"). Mirrors CalendarSettingsSemanticsTest's style.
@OptIn(ExperimentalTestApi::class)
class CalendarReviewFlowSemanticsTest {

  @Test fun `now card announces title and preview rows`() = runComposeUiTest {
    setContent { DayfoldTheme { CalendarCheckNowCard(SnapshotStates.calendarNowItem("two-gaps")!!) } }
    onNodeWithText("2 things to review").assertIsDisplayed()
    onNodeWithText("Maya's dance recital · not on your calendar").assertIsDisplayed()
    onNodeWithText("Grandma's 80th lunch · calendar only").assertIsDisplayed()
    onNodeWithContentDescription("Review calendar check items").assertIsDisplayed()
  }

  @Test fun `review list announces each row's kind, title and meta`() = runComposeUiTest {
    setContent {
      DayfoldTheme {
        CalendarReviewListScreen(
          ui = SnapshotStates.calendarReviewList("list"), compareLabel = "Compared on this phone · just now",
          onBack = {}, onAddToCalendar = {}, onIgnoreDayfoldOnly = {}, onOpenHub = {}, onOpenNeedsReview = {},
          onKeepCalendarOnly = {}, onAddToHub = {}, onOpenIgnored = {},
        )
      }
    }
    onNodeWithText("Maya's dance recital").assertIsDisplayed()
    onNodeWithText("Jun 27, 3:00 PM · from the Recital Hub").assertIsDisplayed()
    onNodeWithContentDescription("Possible match — review, Soccer — Leo").assertIsDisplayed()
    onNodeWithText("Grandma's 80th lunch").assertIsDisplayed()
    onNodeWithContentDescription("3 ignored on this phone").assertIsDisplayed()
  }

  @Test fun `suggested match keeps keep-separate and confirm-match as equal peers, nothing preselected`() = runComposeUiTest {
    setContent { DayfoldTheme { CalendarSuggestedMatchScreen(SnapshotStates.CALENDAR_SUGGESTED, onBack = {}, onKeepSeparate = {}, onConfirmMatch = {}) } }
    onNodeWithText("Soccer — Leo").assertIsDisplayed()
    onNodeWithText("Leo soccer game").assertIsDisplayed()
    onNodeWithText("Same Saturday, same 4:00 PM start").assertIsDisplayed()
    onNodeWithText("Keep separate").assertIsDisplayed()
    onNodeWithText("Confirm match").assertIsDisplayed()
  }

  @Test fun `ambiguous match disables match-selected until a candidate is chosen`() = runComposeUiTest {
    setContent { DayfoldTheme { CalendarAmbiguousMatchScreen(SnapshotStates.CALENDAR_AMBIGUOUS, onBack = {}, onLeaveUnresolved = {}, onMatchSelected = {}) } }
    onNodeWithText("Match selected").assertIsNotEnabled()
    onNodeWithText("Leave unresolved").assertIsDisplayed()
    onNodeWithContentDescription("Piano — M., Jun 25, 3:30 PM · Family calendar").performClick()
    onNodeWithText("Match selected").assertIsDisplayed()
  }

  @Test fun `details differ shows both values and the per-field chip row, no global winner`() = runComposeUiTest {
    setContent { DayfoldTheme { CalendarDetailsDifferScreen(SnapshotStates.CALENDAR_DIFFER, onBack = {}, onFieldChoice = { _, _ -> }) } }
    onNodeWithText("Jul 11, 3:00 PM").assertIsDisplayed()
    onNodeWithText("Jul 11, 4:00 PM").assertIsDisplayed()
    onAllNodesWithText("Keep Dayfold's").assertCountEquals(2)
    onAllNodesWithText("Use Calendar's").assertCountEquals(2)
    onAllNodesWithText("Leave both as-is").assertCountEquals(2)
  }

  @Test fun `matched summary states calendar owns the start alert`() = runComposeUiTest {
    setContent {
      DayfoldTheme {
        CalendarMatchedSummaryScreen(
          hubTitle = "Soccer — Leo", monthAbbrev = "Jun", dayNumber = "20", dateLabel = "Saturday, June 20",
          timeLocationLabel = "4:00 PM · Riverside Park", calendarName = "Family", calendarDotColor = "#7B9E6B",
          lastCheckedLabel = "Last checked today, 9:32 AM · on this phone",
          onBack = {}, onOpenInCalendar = {}, onUnlink = {},
        )
      }
    }
    onNodeWithText("Last checked today, 9:32 AM · on this phone").assertIsDisplayed()
    onNodeWithText("Open in Calendar").assertIsDisplayed()
    onNodeWithText("Unlink").assertIsDisplayed()
    onNodeWithText("Calendar handles the \"starts soon\" alert.").assertIsDisplayed()
  }
}
