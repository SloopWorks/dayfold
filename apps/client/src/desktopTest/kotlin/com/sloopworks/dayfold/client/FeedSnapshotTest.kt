package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.theme.DayfoldTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

// Compose UI snapshots — render FeedScreen(state) off-screen under DayfoldTheme
// and WRITE the PNG to apps/client/build/snapshots/ so a future agent session
// can `Read` the image to verify the UI without re-deriving the adb-screencap
// flow (cheaper loop). Golden-diff = rk snapshot (CL-SNAP), ADR 0019.
@OptIn(ExperimentalTestApi::class)
class FeedSnapshotTest {
  private fun snapshot(name: String, state: AppState, dark: Boolean = false) = runComposeUiTest {
    setContent { DayfoldTheme(darkTheme = dark) { FeedScreen(state) } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0, "snapshot has no pixels")
    val dir = File("build/snapshots").apply { mkdirs() }
    ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png"))
  }

  @Test
  fun populatedFeedDarkSnapshot() = snapshot(
    "feed-populated-dark",
    AppState(cards = listOf(
      Card("a", kind = "action", title = "Party Saturday — order groceries?",
        bodyMd = "Tap [the list](https://instacart.com) to reorder.",
        provenance = Provenance("claude"), notBefore = "2026-06-18T09:00:00Z"),
      Card("c", kind = "countdown", title = "Maya starts college",
        bodyMd = "12 days", provenance = Provenance("claude")),
    )),
    dark = true,
  )

  @Test
  fun populatedFeedSnapshot() = snapshot(
    "feed-populated",
    AppState(cards = listOf(
      Card("a", kind = "action", title = "Party Saturday — order groceries?",
        bodyMd = "Tap [the list](https://instacart.com) to reorder.",
        provenance = Provenance("claude"), notBefore = "2026-06-18T09:00:00Z"),
      Card("b", kind = "weather", title = "Rain at soccer 4pm — pack jackets",
        provenance = Provenance("claude"), notBefore = "2026-06-18T15:00:00Z"),
      Card("c", kind = "countdown", title = "Maya starts college",
        bodyMd = "12 days", provenance = Provenance("claude")),
    )),
  )

  @Test
  fun emptyFeedSnapshot() = snapshot("feed-empty", AppState())   // first-run (new family: no hubs, no roster)

  // ── #164: the four posture states for an empty feed (ADR 0008) ─────────────
  // An ESTABLISHED family (has a hub) with no cards → "all caught up", NOT onboarding —
  // the headline misframing fix. Syncing → skeleton. (feed-empty above = first-run.)
  private val caughtUp = AppState(hubs = listOf(Hub(id = "h1", title = "Starting College", status = "active", visibility = "family")))
  @Test fun caughtUpSnapshot() = snapshot("feed-caught-up", caughtUp)
  @Test fun caughtUpDarkSnapshot() = snapshot("feed-caught-up-dark", caughtUp, dark = true)
  @Test fun syncingSnapshot() = snapshot("feed-syncing", AppState(syncing = true))
  // offline / sync error (4th posture state): recoverable — reason + Try again, never a dead-end
  private val offline = AppState(error = "No internet connection")
  @Test fun offlineSnapshot() = snapshot("feed-offline", offline)
  @Test fun offlineDarkSnapshot() = snapshot("feed-offline-dark", offline, dark = true)

  // The misframing fix, asserted on the branch logic (not just pixels): an established
  // family's empty feed reads "all caught up" with a path to Hubs; a first-run family's does NOT.
  @Test fun `established empty feed shows caught-up, not onboarding`() = runComposeUiTest {
    setContent { DayfoldTheme { FeedScreen(caughtUp) } }
    onNodeWithText("You're all caught up").assertIsDisplayed()
    onNodeWithText("Your hubs are here").assertIsDisplayed()
  }

  @Test fun `first-run empty feed is NOT misframed as caught-up`() = runComposeUiTest {
    setContent { DayfoldTheme { FeedScreen(AppState()) } }
    onNodeWithText("You're all caught up").assertDoesNotExist()
  }

  // ── CL-5: the 6 typed Now cards, light + dark ──────────────────────────────

  private val typedFeed = AppState(cards = listOf(
    Card("file", kind = "action", title = "Permission slip — sign by Thursday",
      provenance = Provenance("email"), type = "file", privacy = CardPrivacy("on_device"),
      payload = Payload(file = FilePayload(filename = "permission.pdf", mime = "application/pdf", size = 240000, pages = 2,
        docRef = "https://drive.example/abc"))),
    Card("link", kind = "action", title = "Soccer registration closes Friday",
      provenance = Provenance("user"), type = "link",
      payload = Payload(link = LinkPayload(url = "https://riversideyouth.org/reg", domain = "riversideyouth.org", kind = "form", fieldCount = 8))),
    Card("invite", kind = "action", title = "Maya's party — reply by Thursday",
      provenance = Provenance("email"), type = "invite", privacy = CardPrivacy("on_device"),
      payload = Payload(invite = InvitePayload(eventName = "Maya's party", host = "The Garcias", rsvpState = "none", guestCount = 12, confirmedCount = 8))),
    Card("contact", kind = "action", title = "Jake's Rentals delivers at 1pm",
      provenance = Provenance("email"), type = "contact",
      payload = Payload(contact = ContactPayload(name = "Jake's Rentals", company = "Jake's Rentals", role = "Bouncy castle", phone = "+15551234567"))),
    Card("geo", kind = "action", title = "Riverside Park — 8 min away",
      provenance = Provenance("user"), type = "geo",
      payload = Payload(geo = GeoPayload(label = "Riverside Park", address = "Shelter B", etaMin = 8, travelMode = "driving"))),
    Card("email", kind = "action", title = "School RSVP needs a reply by Thursday",
      provenance = Provenance("email"), type = "email",
      payload = Payload(email = EmailPayload(from = "Lincoln Elementary", fromAddr = "office@lincoln.edu", subject = "Field trip permission", threadLen = 2))),
  ))

  @Test fun typedCardsSnapshot() = snapshot("cards-typed", typedFeed)
  @Test fun typedCardsDarkSnapshot() = snapshot("cards-typed-dark", typedFeed, dark = true)

  // ── ADR 0036 (#177): a CARD with media — the parallel of the hub-hero coverage. ──
  // The leading EnrichedThumbnail renders only when thumbnailUrl != null (no image loads
  // in headless → icon+accent tile fallback), and the kind chip goes accent-tinted. The
  // thumbnail composable + accent math had unit/hub coverage; the card render path did not.
  private val enrichedFeed = AppState(cards = listOf(
    Card("enr", kind = "action", title = "Maya's party Saturday — order the groceries?",
      provenance = Provenance("claude"),
      media = CardMedia(icon = "party", accentColor = "#C0381E",
        thumbnailUrl = "https://upload.wikimedia.org/wikipedia/commons/0/0c/Logo.jpg")),
  ))
  @Test fun enrichedCardSnapshot() = snapshot("card-enriched", enrichedFeed)

  // invite RSVP display-only: each authored state renders the right highlighted chip
  private fun inviteWith(state: String) = AppState(cards = listOf(
    Card("inv", kind = "action", title = "Maya's party", provenance = Provenance("email"),
      type = "invite", payload = Payload(invite = InvitePayload(eventName = "Maya's party", rsvpState = state))),
  ))
  @Test fun inviteRsvpNoneSnapshot() = snapshot("invite-rsvp-none", inviteWith("none"))
  @Test fun inviteRsvpYesSnapshot() = snapshot("invite-rsvp-yes", inviteWith("yes"))
  @Test fun inviteRsvpNoSnapshot() = snapshot("invite-rsvp-no", inviteWith("no"))

  // ── CL-6: DetailScreen per type, light + dark ──────────────────────────────
  // Reached by opening the corresponding typed card (detailStack = [id]).
  private fun detailState(id: String) = typedFeed.copy(detailStack = listOf(id))

  private fun detailSnap(name: String, id: String, dark: Boolean = false) = runComposeUiTest {
    setContent {
      com.sloopworks.dayfold.client.theme.DayfoldTheme(darkTheme = dark) {
        val c = currentDetailCard(detailState(id))!!
        com.sloopworks.dayfold.client.cards.DetailScreen(c, onBack = {}, onAction = {})
      }
    }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0)
    val dir = File("build/snapshots").apply { mkdirs() }
    ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png"))
  }

  @Test fun detailFileSnapshot() = detailSnap("detail-file", "file")
  @Test fun detailLinkSnapshot() = detailSnap("detail-link", "link")
  @Test fun detailInviteSnapshot() = detailSnap("detail-invite", "invite")
  @Test fun detailContactSnapshot() = detailSnap("detail-contact", "contact")
  @Test fun detailGeoSnapshot() = detailSnap("detail-geo", "geo")
  @Test fun detailEmailSnapshot() = detailSnap("detail-email", "email")
  @Test fun detailInviteDarkSnapshot() = detailSnap("detail-invite-dark", "invite", dark = true)
  @Test fun detailContactDarkSnapshot() = detailSnap("detail-contact-dark", "contact", dark = true)

  // CL-8: a detail with RELATED rows (attachment↔email + same-hub).
  @Test fun detailRelatedSnapshot() = runComposeUiTest {
    val card = Card("email", kind = "action", title = "School RSVP needs a reply", provenance = Provenance("email"),
      type = "email", payload = Payload(email = EmailPayload(from = "Lincoln Elementary", subject = "Field trip")),
      relatedKicker = "FROM THE SAME EMAIL",
      related = listOf(
        RelatedRef("attachment", "f1", "file", "permission.pdf", "240 KB · attachment"),
        RelatedRef("same-hub", "i1", "invite", "Maya's party", "Sat · 3:00 PM"),
      ))
    setContent { com.sloopworks.dayfold.client.theme.DayfoldTheme { com.sloopworks.dayfold.client.cards.DetailScreen(card, onBack = {}, onAction = {}) } }
    val img = onRoot().captureToImage()
    assertTrue(img.width > 0 && img.height > 0)
    ImageIO.write(img.toAwtImage(), "png", File("build/snapshots".also { File(it).mkdirs() }, "detail-related.png"))
  }
}
