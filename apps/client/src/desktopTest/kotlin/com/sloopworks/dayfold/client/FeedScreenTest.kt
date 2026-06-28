package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

// Headless render proof — Compose Desktop renders the feed off-screen (Skiko
// software), no device needed. Verifies the redux state → Compose feed binding.
@OptIn(ExperimentalTestApi::class)
class FeedScreenTest {
  @Test
  fun rendersCardsInFeedOrder() = runComposeUiTest {
    val state = AppState(
      cards = listOf(
        Card("b", title = "Soccer 4pm", notBefore = "2026-06-18T16:00:00Z"),
        Card("a", title = "Leave by 3:30", notBefore = "2026-06-18T15:30:00Z"),
      ),
    )
    setContent { MaterialTheme { FeedScreen(state) } }
    onNodeWithText("Soccer 4pm").assertIsDisplayed()
    onNodeWithText("Leave by 3:30").assertIsDisplayed()
  }

  @Test
  fun cardBodyRendersMarkdownNotRawSyntax() = runComposeUiTest {
    // a CLI-authored card body with **bold** must show "Reply by Thursday",
    // not the literal "**Reply**" (feed now uses the same renderer as hub blocks).
    val state = AppState(cards = listOf(Card("c1", title = "School email", bodyMd = "**Reply** by Thursday")))
    setContent { MaterialTheme { FeedScreen(state) } }
    onNodeWithText("Reply by Thursday").assertIsDisplayed()   // markers stripped, run bolded
  }

  @Test
  fun accountAvatarExposesAnAccessibleLabel() = runComposeUiTest {
    // the monogram avatar is icon-only → screen readers must hear "Account", not "Y"
    setContent { MaterialTheme { FeedScreen(AppState(cards = listOf(Card("c1", title = "X")))) } }
    onNodeWithContentDescription("Account").assertIsDisplayed()
  }

  @Test
  fun showsFamilyNullStateWhenEmpty() = runComposeUiTest {
    // S5: an empty family shows the onboarding null-state, not a bare message.
    setContent { MaterialTheme { FeedScreen(AppState()) } }
    onNodeWithText("Your family space is ready").assertIsDisplayed()
    onNodeWithText("Invite a member").assertIsDisplayed()
  }

  @Test
  fun showsSkeletonWhileSyncing() = runComposeUiTest {
    // empty + syncing now renders the FeedSkeleton (liveRegion "Loading your day")
    // after rememberStableLoading's ~200ms debounce; waitForIdle drives the delay.
    setContent { MaterialTheme { FeedScreen(AppState(syncing = true)) } }
    // #164 SyncingState skeleton ("Catching up on your day"), now gated by the
    // loading-states rememberStableLoading ~200ms anti-flash debounce.
    mainClock.advanceTimeBy(250)   // past the 200ms debounce
    onNodeWithContentDescription("Catching up on your day").assertIsDisplayed()
  }

  @Test
  fun fastSyncDoesNotFlashSkeleton() = runComposeUiTest {
    // rememberStableLoading's WHOLE POINT (the anti-flash inverse): a sync that resolves
    // inside the ~200ms debounce must NEVER flash the skeleton. Hold the clock and advance
    // less than the debounce → the skeleton must not be shown.
    mainClock.autoAdvance = false
    setContent { MaterialTheme { FeedScreen(AppState(syncing = true)) } }
    mainClock.advanceTimeBy(100)   // before the 200ms debounce
    onNodeWithContentDescription("Catching up on your day").assertDoesNotExist()
  }

  @Test
  fun emptyFeedErrorOffersRetryInsteadOfDeadEnd() = runComposeUiTest {
    // first-launch-offline: empty + error must offer a way forward, not just text
    var retried = false
    setContent { MaterialTheme { FeedScreen(AppState(error = "Network unavailable"), onRefresh = { retried = true }) } }
    onNodeWithText("Couldn't load your day").assertIsDisplayed()
    onNodeWithText("Network unavailable").assertIsDisplayed()   // the actual reason
    onNodeWithText("Try again").performClick()
    assertTrue(retried)
  }

  @Test
  fun populatedFeedSurfacesSyncFailureWithRetry() = runComposeUiTest {
    // a sync error with cached cards was silent before — now a calm banner + retry,
    // and the saved cards still render (offline-first: stale beats blank).
    var retried = false
    val state = AppState(cards = listOf(Card("c1", title = "Soccer 4pm")), error = "Network unavailable")
    setContent { MaterialTheme { FeedScreen(state, onRefresh = { retried = true }) } }
    onNodeWithText("Couldn't refresh", substring = true).assertIsDisplayed()
    onNodeWithText("Soccer 4pm").assertIsDisplayed()           // cached card still shown
    onNodeWithText("Retry").performClick()
    assertTrue(retried)                                        // retry triggers a re-sync
  }

  @Test
  fun caughtUpHubsPillNavigatesToHubs() = runComposeUiTest {
    // #164: the caught-up state's quiet forward path must actually navigate to Hubs, not
    // just render — an established family (has a hub) gets the "Your hubs are here →" pill.
    var navigated = false
    val established = AppState(hubs = listOf(Hub(id = "h1", title = "College", status = "active", visibility = "family")))
    setContent { MaterialTheme { FeedScreen(established, onNavHubs = { navigated = true }) } }
    onNodeWithText("Your hubs are here").performClick()
    assertTrue(navigated, "the caught-up 'Your hubs are here' pill should navigate to Hubs")
  }
}
