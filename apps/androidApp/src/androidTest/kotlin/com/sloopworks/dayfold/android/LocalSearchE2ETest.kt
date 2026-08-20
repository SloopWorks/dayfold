package com.sloopworks.dayfold.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.ContentState
import com.sloopworks.dayfold.client.DayfoldCommandPort
import com.sloopworks.dayfold.client.DayfoldCommands
import com.sloopworks.dayfold.client.FamilyMembership
import com.sloopworks.dayfold.client.HubArrival
import com.sloopworks.dayfold.client.HubArrivalLevel
import com.sloopworks.dayfold.client.HubArrivalSource
import com.sloopworks.dayfold.client.HubRequestKey
import com.sloopworks.dayfold.client.HubReturnDestination
import com.sloopworks.dayfold.client.HubState
import com.sloopworks.dayfold.client.HubTenantGeneration
import com.sloopworks.dayfold.client.HubTree
import com.sloopworks.dayfold.client.HubTreeLoaded
import com.sloopworks.dayfold.client.NavigationState
import com.sloopworks.dayfold.client.OpenDetailFromSearch
import com.sloopworks.dayfold.client.OpenHub
import com.sloopworks.dayfold.client.Route
import com.sloopworks.dayfold.client.SearchContentSnapshot
import com.sloopworks.dayfold.client.SearchReadiness
import com.sloopworks.dayfold.client.SearchResponse
import com.sloopworks.dayfold.client.SearchResultHandle
import com.sloopworks.dayfold.client.SearchStatus
import com.sloopworks.dayfold.client.SessionState
import com.sloopworks.dayfold.client.buildSearchCorpus
import com.sloopworks.dayfold.client.createAppStore
import com.sloopworks.dayfold.client.fake.FakeScenarios
import com.sloopworks.dayfold.client.searchCorpus
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.reduxkotlin.concurrent.NotificationContext

/**
 * API-35 instrumentation smoke over the real FeedApp and Redux navigation.
 *
 * The selectable busy-family fixture is converted into the same immutable corpus the runtime uses;
 * no DB or network fake is hidden behind this test. Matcher/corpus behavior is covered more deeply
 * in common and native tests, while this proves the Android text-input and canonical Back wiring.
 */
class LocalSearchE2ETest {
  @get:Rule val rule = createComposeRule()

  @Test
  fun exactAndCloseMatch_openCard_backRestoresSession() {
    val fixture = checkNotNull(FakeScenarios.byId("busy-family")).data
    val changes = fixture.sync.changes
    val corpusGeneration = 11L
    val bindingGeneration = 7L
    val corpus = buildSearchCorpus(
      SearchContentSnapshot(
        revision = corpusGeneration,
        readiness = SearchReadiness.READY,
        cards = changes.cards,
        hubs = changes.hubs,
        sections = changes.sections,
        blocks = changes.blocks,
      ),
    )
    val store = createAppStore(
      notificationContext = NotificationContext.Inline,
      initial = AppState(
        session = SessionState(
          session = fixture.session,
          families = fixture.whoami.families,
          activeFamilyId = fixture.whoami.familyId,
        ),
        navigation = NavigationState(route = Route.Feed),
        content = ContentState(cards = changes.cards),
        hubs = HubState(hubs = changes.hubs),
      ),
      debug = false,
    )
    val base = DayfoldCommands.navigationOnly(store)
    val status = MutableStateFlow(
      SearchStatus(
        bindingGeneration = bindingGeneration,
        corpusGeneration = corpusGeneration,
        readiness = SearchReadiness.READY,
      ),
    )
    val commands = object : DayfoldCommandPort by base {
      override val searchStatus: StateFlow<SearchStatus> = status

      override suspend fun search(query: String): SearchResponse = searchCorpus(
        corpus = corpus,
        query = query,
        bindingGeneration = bindingGeneration,
        corpusGeneration = corpusGeneration,
      )

      override fun openSearchResult(handle: SearchResultHandle) {
        val destination = handle.destination
        destination.cardId?.let {
          store.dispatch(OpenDetailFromSearch(it))
          return
        }
        destination.blockId?.let { blockId ->
          val section = changes.sections.single { it.id == destination.sectionId }
          val hub = changes.hubs.single { it.id == destination.hubId }
          val request = HubRequestKey(HubTenantGeneration(identityEpoch = 1, familyRevision = 1), requestId = 1)
          store.dispatch(OpenHub(
            hubId = hub.id,
            request = request,
            returnDestination = HubReturnDestination.SEARCH,
            arrival = HubArrival(HubArrivalLevel.BLOCK, blockId, HubArrivalSource.SEARCH),
          ))
          store.dispatch(HubTreeLoaded(
            hubId = hub.id,
            request = request,
            tree = HubTree(
              hub = hub,
              sections = changes.sections.filter { it.hubId == hub.id },
              blocks = changes.blocks.filter { it.sectionId in changes.sections
                .filter { candidate -> candidate.hubId == hub.id }
                .map { candidate -> candidate.id }
              },
            ),
          ))
        }
      }
    }

    rule.setContent {
      DayfoldTheme {
        TestFeedApp(store = store, commandsOverride = commands)
      }
    }

    rule.onNodeWithContentDescription("Search saved content").assertIsDisplayed().performClick()
    val field = rule.onNodeWithContentDescription("Search all Dayfold content")
    field.performTextInput("soccer")
    rule.waitUntil(timeoutMillis = 5_000L) {
      rule.onAllNodes(hasContentDescription("Soccer registration closes Friday", substring = true))
        .fetchSemanticsNodes().isNotEmpty()
    }

    field.performTextReplacement("socer")
    rule.waitUntil(timeoutMillis = 5_000L) {
      rule.onAllNodes(hasContentDescription("Close match", substring = true))
        .fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNodeWithText("Close matches for “socer”").assertIsDisplayed()
    rule.onNode(
      hasContentDescription("Soccer registration closes Friday", substring = true),
    ).assertIsDisplayed().performClick()

    rule.onNodeWithText("Soccer registration closes Friday").assertIsDisplayed()
    rule.onNodeWithContentDescription("Back to search").performClick()

    rule.onNodeWithContentDescription("Search all Dayfold content")
      .assertTextEquals("socer")
    rule.onNodeWithText("Close matches for “socer”").assertIsDisplayed()

    field.performTextReplacement("balloons")
    rule.waitUntil(timeoutMillis = 5_000L) {
      rule.onAllNodes(hasContentDescription("Hub item. Order cake", substring = true))
        .fetchSemanticsNodes().isNotEmpty()
    }
    rule.onNode(hasContentDescription("Hub item. Order cake", substring = true)).performClick()
    rule.onNodeWithText("SEARCH MATCH").assertIsDisplayed()
    rule.onNodeWithContentDescription("Back to search").performClick()
    rule.onNodeWithContentDescription("Search all Dayfold content").assertTextEquals("balloons")
  }
}
