package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

// ADR 0045 — integration snapshot tests: the hoisted TimelineCard appears in the hub
// dossier (Task 13a, static wiring), and the TimelineDetail overlay replaces it when
// state.timelineDetail is set. Uses a day-scale intraday timeline (7 stops, "Move-in day").
@OptIn(ExperimentalTestApi::class)
class HubTimelineIntegrationSnapshotTest {

    // nowIso = 10:40 AM ET (= 14:40 UTC) on move-in day → 3 stops done, "Elevator slot" is Next
    private val nowIso = "2026-08-24T14:40:00Z"

    private fun dayTimeline() = Timeline(
        title = "Move-in day",
        tz = "America/New_York",
        stops = listOf(
            Stop("2026-08-24T07:30:00-04:00", "Car loaded", done = true),
            Stop("2026-08-24T08:00:00-04:00", "Keys pickup", done = true),
            Stop("2026-08-24T09:50:00-04:00", "Checked in", done = true),
            Stop("2026-08-24T11:00:00-04:00", "Elevator slot"),
            Stop("2026-08-24T12:30:00-04:00", "Lunch break"),
            Stop("2026-08-24T13:00:00-04:00", "Bookstore run"),
            Stop("2026-08-24T14:00:00-04:00", "Final walkthrough"),
        ),
    )

    private fun timelineHub() = Hub(
        id = "h1",
        type = "starting-college",
        title = "Move-in Day Hub",
        status = "active",
        visibility = "family",
        timeline = dayTimeline(),
    )

    /** State with hub open + timeline present — no detail overlay yet. */
    private fun cardState() = AppState(
        route = Route.Hubs,
        currentHubId = "h1",
        currentHubTree = HubTree(hub = timelineHub()),
    )

    /** Same hub, but with the timeline detail overlay open at Day scale. */
    private fun detailState() = cardState().copy(timelineDetail = TimelineScale.Day)

    // ── (1) Hoisted card in dossier ───────────────────────────────────────────

    @Test fun timelineCardAppearsInDossier() = runComposeUiTest {
        val state = cardState()
        setContent {
            DayfoldTheme(darkTheme = false) {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    HubDetailScreen(state)
                }
            }
        }
        // The day-scale TimelineCard footer always shows "Open timeline"
        onNodeWithText("Open timeline", substring = true).assertExists()
        // Snapshot for eyeball review
        val img = onRoot().captureToImage()
        assertTrue(img.width > 0 && img.height > 0, "card snapshot has no pixels")
        File("build/snapshots").apply { mkdirs() }
            .let { dir -> ImageIO.write(img.toAwtImage(), "png", File(dir, "hub-timeline-integration-card.png")) }
    }

    // ── (2) Detail overlay replaces the dossier ───────────────────────────────

    @Test fun timelineDetailOverlayRenders() = runComposeUiTest {
        val state = detailState()
        setContent {
            DayfoldTheme(darkTheme = false) {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    HubDetailScreen(state)
                }
            }
        }
        // Capture snapshot first (written regardless of assertion outcome for visual inspection)
        val img = onRoot().captureToImage()
        assertTrue(img.width > 0 && img.height > 0, "detail snapshot has no pixels")
        File("build/snapshots").apply { mkdirs() }
            .let { dir -> ImageIO.write(img.toAwtImage(), "png", File(dir, "hub-timeline-integration-detail.png")) }
        // TimelineDetail renders a MORNING group header (unique to the detail — the dossier does not).
        onNodeWithText("MORNING", substring = true).assertExists()
    }

    // ── (4) Derived fallback (ADR 0046) — no authored timeline, but dated blocks ──

    @Test fun derivedTimelineCardAppearsWhenNoAuthoredTimeline() = runComposeUiTest {
        // hub has NO timeline, but a countdown + a milestone + a checklist due → derives one.
        val hub = Hub(id = "h2", type = "party-event", title = "Maya's party", status = "active",
            visibility = "family", countdownTo = "2026-08-24")
        val blocks = listOf(
            HubBlock(id = "m", sectionId = "s", type = "milestone",
                payload = BlockPayload(date = "2026-08-20", label = "Order cake")),
            HubBlock(id = "c", sectionId = "s", type = "checklist",
                payload = BlockPayload(items = listOf(ChecklistItem(id = "i", text = "Buy balloons", due = "2026-08-22")))),
        )
        val state = AppState(route = Route.Hubs, currentHubId = "h2",
            currentHubTree = HubTree(hub = hub, blocks = blocks))
        setContent {
            DayfoldTheme(darkTheme = false) {
                Box(Modifier.width(390.dp).height(780.dp)) { HubDetailScreen(state) }
            }
        }
        // the honest derived chip — not the authored "Added to this hub"
        onNodeWithText("From this hub’s dates", substring = true).assertExists()
        onNodeWithText("Added to this hub", substring = true).assertDoesNotExist()
    }

    @Test fun singleDatedBlockShowsNudgeNotACard() = runComposeUiTest {
        // only the hub countdown is dated → one stop → "No timeline yet" nudge, no card.
        val hub = Hub(id = "h3", type = "vacation", title = "Cape Cod", status = "active",
            visibility = "family", countdownTo = "2026-09-01")
        val state = AppState(route = Route.Hubs, currentHubId = "h3", currentHubTree = HubTree(hub = hub))
        setContent {
            DayfoldTheme(darkTheme = false) {
                Box(Modifier.width(390.dp).height(780.dp)) { HubDetailScreen(state) }
            }
        }
        onNodeWithText("No timeline yet", substring = true).assertExists()
        onNodeWithText("Open timeline", substring = true).assertDoesNotExist()
    }

    // ── (3) Hide for me (W5) — card leaves the dossier, recoverable in "Hidden for you" ──

    @Test fun hiddenTimelineLeavesDossierAndIsRecoverable() = runComposeUiTest {
        // Member hid the timeline: the synthetic id "timeline:<hubId>" is in hiddenIds.
        val state = cardState().copy(hiddenIds = setOf("timeline:h1"), showHidden = true)
        setContent {
            DayfoldTheme(darkTheme = false) {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    HubDetailScreen(state)
                }
            }
        }
        // The hoisted card is gone…
        onNodeWithText("Open timeline", substring = true).assertDoesNotExist()
        // …but it's in the "Hidden for you" section with an Unhide path.
        onNodeWithText("Hidden for you", substring = true).assertExists()
        onNodeWithText("Unhide", substring = true).assertExists()
        val img = onRoot().captureToImage()
        assertTrue(img.width > 0 && img.height > 0, "hidden snapshot has no pixels")
        File("build/snapshots").apply { mkdirs() }
            .let { dir -> ImageIO.write(img.toAwtImage(), "png", File(dir, "hub-timeline-integration-hidden.png")) }
    }
}
