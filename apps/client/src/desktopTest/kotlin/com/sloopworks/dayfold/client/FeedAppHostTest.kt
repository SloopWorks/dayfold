package com.sloopworks.dayfold.client

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.cards.CardAction
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

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
    val store = createAppStore(AppState(route = Route.Feed), debug = false)
    store.dispatch(CardsLoaded(listOf(typed())))
    block(store)
    setContent { FeedApp(store, onPlatformAction = {}) }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0)
    ImageIO.write(img.toAwtImage(), "png", File("build/snapshots".also { File(it).mkdirs() }, "$name.png"))
  }

  @Test fun hostRendersFeed() = shot("host-feed") { /* no nav → feed */ }

  @Test fun hostRendersDetailWhenOpen() = shot("host-detail") { store ->
    store.dispatch(NavToDetail("f"))
    assertTrue(store.state.detailStack == listOf("f"))
  }

  // S6-D: FeedApp hosts the device-approval routes without crashing (each outcome).
  private fun hostShot(name: String, initial: AppState) = runComposeUiTest {
    val store = createAppStore(initial, debug = false)
    setContent { FeedApp(store) }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0)
    ImageIO.write(img.toAwtImage(), "png", File("build/snapshots".also { File(it).mkdirs() }, "$name.png"))
  }

  private val ownerFam = FamilyMembership("fam1", "The Jacksons", role = "owner", status = "active")
  private fun authedAt(route: Route, outcome: String? = null) = AppState(
    session = Session("a", "r"), families = listOf(ownerFam), activeFamilyId = "fam1",
    route = route, deviceOutcome = outcome,
    pendingDevice = PendingDevice("WDJF-7K2P", client = "Dayfold CLI", originKind = "residential"),
  )

  @Test fun hostRendersEnterCode() = hostShot("host-entercode", authedAt(Route.EnterCode).copy(pendingDevice = null))
  @Test fun hostRendersAuthorize() = hostShot("host-authorize", authedAt(Route.AuthorizeDevice))
  @Test fun hostRendersDenied() = hostShot("host-device-denied", authedAt(Route.AuthorizeDevice, "denied"))
  @Test fun hostRendersExpired() = hostShot("host-device-expired", authedAt(Route.AuthorizeDevice, "expired"))
  @Test fun hostRendersApproved() = hostShot("host-device-approved", authedAt(Route.AuthorizeDevice, "approved"))

  // Phase 2 scan + deep-link host arms (render without crashing).
  @Test fun hostRendersScanPrimer() = hostShot("host-scan-primer", authedAt(Route.ScanPrimer).copy(pendingDevice = null))
  @Test fun hostRendersScanDevice() = hostShot("host-scan-device", authedAt(Route.ScanDevice).copy(pendingDevice = null))
  @Test fun hostRendersScanDenied() = hostShot("host-scan-denied", authedAt(Route.ScanDenied).copy(pendingDevice = null))
  @Test fun hostRendersDeviceResume() = hostShot(
    "host-deviceresume",
    AppState(route = Route.SignIn, pendingDeviceLink = "WDJF-7K2P"),
  )
  @Test fun hostRendersFinishing() = hostShot(
    "host-devicefinishing",
    AppState(route = Route.Feed, deviceResuming = true),
  )

  @Test fun routeCardAction_splits_openDetail_from_platform_handoffs() {
    val store = createAppStore(debug = false)
    store.dispatch(CardsLoaded(listOf(typed())))
    var performed: CardAction? = null
    val onPlatform: (CardAction) -> Unit = { performed = it }

    // OpenDetail → in-app nav (store), NOT the platform layer
    routeCardAction(store, onPlatform, CardAction.OpenDetail("f"))
    assertTrue(currentDetailCard(store.state)?.id == "f")
    assertTrue(performed == null)

    // every other CardAction → the shell's PlatformActions, NOT the store
    routeCardAction(store, onPlatform, CardAction.Call("+15550142"))
    assertTrue(performed is CardAction.Call)
    assertTrue(store.state.detailStack == listOf("f")) // unchanged by the handoff
  }
}
