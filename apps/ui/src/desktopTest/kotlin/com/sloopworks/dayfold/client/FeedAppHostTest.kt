package com.sloopworks.dayfold.client

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.cards.CardAction
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.rememberSelectorStore

// CL-7: host integration — FeedApp renders the feed, then DetailScreen once a
// card is open, through the AnimatedContent host. Exercises the remembered
// OpenDetail→dispatch(NavToDetail) handler end-to-end (not just the reducer).
@OptIn(ExperimentalTestApi::class)
class FeedAppHostTest {
  private fun typed() = Card(
    id = "f", kind = "action", title = "Permission slip", provenance = Provenance("email"),
    type = "file", payload = Payload(file = FilePayload(filename = "p.pdf", pages = 2)),
  )

  private fun shot(name: String, block: (org.reduxkotlin.Store<AppState>) -> Unit) = runComposeUiTest {
    // route=Feed so FeedApp renders the CONTENT host (past the AUTH-S5 route gate).
    val store = createTestAppStore(AppState(navigation = NavigationState(route = Route.Feed)), debug = false)
    store.dispatch(CardsLoaded(listOf(typed())))
    block(store)
    setContent { TestFeedApp(store) }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0)
    ImageIO.write(img.toAwtImage(), "png", File("build/snapshots".also { File(it).mkdirs() }, "$name.png"))
  }

  @Test fun hostRendersFeed() = shot("host-feed") { /* no nav → feed */ }

  @Test fun hostRendersDetailWhenOpen() = shot("host-detail") { store ->
    store.dispatch(NavToDetail("f"))
    assertTrue(store.state.detailStack == listOf("f"))
  }

  @Test fun hubRowTapCarriesTheProjectedFamilyAndHubToCommands() = runComposeUiTest {
    val store = createTestAppStore(
      AppState(
        navigation = NavigationState(route = Route.Hubs),
        session = SessionState(activeFamilyId = "family-1"),
        hubs = HubState(hubs = listOf(Hub(id = "hub-1", title = "College move", status = "active"))),
      ),
      debug = false,
    )
    var opened: Pair<String, String>? = null
    val base = StableDayfoldCommands(DayfoldCommands.navigationOnly(store))
    val commands = object : StableDayfoldCommands by base {
      override fun openHub(
        familyId: String,
        hubId: String,
        focusBlockId: String?,
        returnDestination: HubReturnDestination,
      ) {
        opened = familyId to hubId
      }
    }

    setContent {
      FeedApp(
        store = rememberSelectorStore(store),
        commands = commands,
        platformActions = StablePlatformActions.noOp(),
      )
    }
    onNodeWithText("College move").performClick()

    assertEquals("family-1" to "hub-1", opened)
  }

  // S6-D: FeedApp hosts the device-approval routes without crashing (each outcome).
  private fun hostShot(name: String, initial: AppState) = runComposeUiTest {
    val store = createTestAppStore(initial, debug = false)
    setContent { TestFeedApp(store) }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0)
    ImageIO.write(img.toAwtImage(), "png", File("build/snapshots".also { File(it).mkdirs() }, "$name.png"))
  }

  private val ownerFam = FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")
  private fun authedAt(route: Route, outcome: String? = null) = AppState(
    session = SessionState(session = Session("a", "r"), families = listOf(ownerFam), activeFamilyId = "fam1"),
    navigation = NavigationState(route = route), devices = DeviceState(outcome = outcome,
    pendingDevice = PendingDevice("WDJF-7K2P", client = "Dayfold CLI", originKind = "residential")),
  )

  @Test fun hostRendersEnterCode() = hostShot("host-entercode", authedAt(Route.EnterCode).copy(devices = DeviceState()))
  @Test fun hostRendersAuthorize() = hostShot("host-authorize", authedAt(Route.AuthorizeDevice))
  @Test fun hostRendersDenied() = hostShot("host-device-denied", authedAt(Route.AuthorizeDevice, "denied"))
  @Test fun hostRendersExpired() = hostShot("host-device-expired", authedAt(Route.AuthorizeDevice, "expired"))
  @Test fun hostRendersApproved() = hostShot("host-device-approved", authedAt(Route.AuthorizeDevice, "approved"))

  // Phase 2 scan + deep-link host arms (render without crashing).
  @Test fun hostRendersScanPrimer() = hostShot("host-scan-primer", authedAt(Route.ScanPrimer).copy(devices = DeviceState()))
  @Test fun hostRendersScanDevice() = hostShot("host-scan-device", authedAt(Route.ScanDevice).copy(devices = DeviceState()))
  @Test fun hostRendersScanDenied() = hostShot("host-scan-denied", authedAt(Route.ScanDenied).copy(devices = DeviceState()))
  @Test fun hostRendersDeviceResume() = hostShot(
    "host-deviceresume",
    AppState(navigation = NavigationState(route = Route.SignIn), session = SessionState(pendingInviteLink = "WDJF-7K2P")),
  )
  @Test fun hostRendersFinishing() = hostShot(
    "host-devicefinishing",
    AppState(navigation = NavigationState(route = Route.Feed), devices = DeviceState(resuming = true)),
  )

  @Test fun routeCardAction_splits_openDetail_from_platform_handoffs() = runComposeUiTest {
    val store = createTestAppStore(debug = false)
    store.dispatch(CardsLoaded(listOf(typed())))
    var performed: CardAction? = null
    val commands = StableDayfoldCommands(DayfoldCommands.navigationOnly(store))
    val platformActions = StablePlatformActions.noOp(onPerform = { performed = it })
    lateinit var selectorStore: SelectorStore<AppState>
    setContent { selectorStore = rememberSelectorStore(store) }
    waitForIdle()

    // OpenDetail → in-app nav (store), NOT the platform layer
    routeCardAction(selectorStore, commands, platformActions, CardAction.OpenDetail("f"))
    assertTrue(currentDetailCard(store.state)?.id == "f")
    assertTrue(performed == null)

    // every other CardAction → the shell's PlatformActions, NOT the store
    routeCardAction(selectorStore, commands, platformActions, CardAction.Call("+15550142"))
    assertTrue(performed is CardAction.Call)
    assertTrue(store.state.detailStack == listOf("f")) // unchanged by the handoff
  }
}
