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
