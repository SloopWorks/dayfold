package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlinx.datetime.TimeZone
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

// ADR 0045 — snapshot + behavioral tests for the day-scale TimelineCard.
// Uses 7 move-in-day stops so doneCount=3, window=[Elevator slot, Lunch, Bookstore run],
// tailCount=1 ("1 more"), which exercises all card sections.
@OptIn(ExperimentalTestApi::class)
class TimelineCardSnapshotTest {

    private val ny = TimeZone.of("America/New_York")

    private fun dayModel(): TimelineCardModel {
        val tl = Timeline(
            tz = "America/New_York",
            stops = listOf(
                Stop("2026-08-24T07:30:00-04:00", "Car loaded"),
                Stop("2026-08-24T08:00:00-04:00", "Keys pickup"),
                Stop("2026-08-24T09:50:00-04:00", "Checked in"),
                Stop("2026-08-24T11:00:00-04:00", "Elevator slot"),
                Stop("2026-08-24T12:30:00-04:00", "Lunch break"),
                Stop("2026-08-24T13:00:00-04:00", "Bookstore run"),
                Stop("2026-08-24T14:00:00-04:00", "Final walkthrough"),
            )
        )
        return presentTimelineCard(tl, "2026-08-24T10:40:00-04:00", ny)!!
    }

    private fun shot(name: String, dark: Boolean, model: TimelineCardModel = dayModel()) = runComposeUiTest {
        setContent {
            DayfoldTheme(darkTheme = dark) {
                Box(
                    Modifier
                        .width(390.dp)
                        .background(Color(0xFFE9DDD7))
                        .padding(16.dp)
                ) {
                    TimelineCard(model, onOpen = {})
                }
            }
        }
        val img = onRoot().captureToImage()
        assertTrue(img.width > 0 && img.height > 0, "snapshot has no pixels")
        File("build/snapshots").apply { mkdirs() }
            .let { dir -> ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png")) }
    }

    private fun hubModel(): TimelineCardModel {
        // 5 date-only stops spanning May-Sep 2026 → dateOnlyCount=5 ≥ 3 → Hub scale.
        // Aug 25 is the Next stop (midnight Aug 25 > nowIso 10am Aug 24 in NY).
        val tl = Timeline(
            tz = "America/New_York",
            stops = listOf(
                Stop("2026-05-01", "Planning phase"),
                Stop("2026-06-01", "Design complete"),
                Stop("2026-07-01", "Dev alpha"),
                Stop("2026-08-25", "Move-in day"),
                Stop("2026-09-15", "Launch"),
            )
        )
        return presentTimelineCard(tl, "2026-08-24T10:00:00-04:00", ny)!!
    }

    private fun hubCollapsedModel(): TimelineCardModel {
        // 8 months: Jan–Apr past (Done), Sep–Dec future → leading Done-run of 4 collapses to ✓4.
        val tl = Timeline(
            tz = "America/New_York",
            stops = listOf(
                Stop("2026-01-15", "Kickoff"), Stop("2026-02-15", "Research"),
                Stop("2026-03-15", "Design"), Stop("2026-04-15", "Alpha"),
                Stop("2026-09-15", "Move-in day"), Stop("2026-10-15", "Midterms"),
                Stop("2026-11-15", "Break"), Stop("2026-12-15", "Finals"),
            )
        )
        return presentTimelineCard(tl, "2026-08-24T10:00:00-04:00", ny)!!
    }

    // ── Snapshot tests ─────────────────────────────────────────────────────────

    @Test fun dayLight() = shot("timeline-card-day-light", false)
    @Test fun dayDark()  = shot("timeline-card-day-dark",  true)
    @Test fun hubLight() = shot("timeline-card-hub-light", false, hubModel())
    @Test fun hubDark()  = shot("timeline-card-hub-dark",  true,  hubModel())
    @Test fun hubCollapsedLight() = shot("timeline-card-hub-collapsed-light", false, hubCollapsedModel())
    @Test fun hubCollapsedDark()  = shot("timeline-card-hub-collapsed-dark",  true,  hubCollapsedModel())

    // ── Behavioral assertions ─────────────────────────────────────────────────
    // Verify the card renders real content — not just non-empty pixels.

    @Test fun showsDoneCount() = runComposeUiTest {
        setContent { DayfoldTheme { TimelineCard(dayModel(), onOpen = {}) } }
        // 3 stops are before nowIso (07:30, 08:00, 09:50 all < 10:40)
        onNodeWithText("3 done", substring = true).assertExists()
    }

    @Test fun showsNowTimeLabel() = runComposeUiTest {
        setContent { DayfoldTheme { TimelineCard(dayModel(), onOpen = {}) } }
        // clockTime("2026-08-24T10:40:00-04:00") = "10:40" (12-hr no am/pm)
        onNodeWithText("10:40", substring = true).assertExists()
    }

    @Test fun showsWindowedStop() = runComposeUiTest {
        setContent { DayfoldTheme { TimelineCard(dayModel(), onOpen = {}) } }
        // "Elevator slot" is the Next stop — first of the 3-item window
        onNodeWithText("Elevator", substring = true).assertExists()
    }

    @Test fun showsTail() = runComposeUiTest {
        setContent { DayfoldTheme { TimelineCard(dayModel(), onOpen = {}) } }
        // tailCount = 1 ("Final walkthrough" falls outside the 3-item window)
        onNodeWithText("1 more", substring = true).assertExists()
    }

    @Test fun showsProvenanceChip() = runComposeUiTest {
        setContent { DayfoldTheme { TimelineCard(dayModel(), onOpen = {}) } }
        onNodeWithText("Added to this hub").assertExists()
    }

    // ── Hub behavioral assertions ─────────────────────────────────────────────

    @Test fun hubShowsMonthLabel() = runComposeUiTest {
        setContent { DayfoldTheme { TimelineCard(hubModel(), onOpen = {}) } }
        // Spine node for August: SpineNode(label="AUGUST", …).take(3) = "AUG"
        onNodeWithText("AUG", substring = true).assertExists()
    }

    @Test fun hubShowsNextTitle() = runComposeUiTest {
        setContent { DayfoldTheme { TimelineCard(hubModel(), onOpen = {}) } }
        // nextCallout.stop.title = "Move-in day"
        onNodeWithText("Move-in day", substring = true).assertExists()
    }

    @Test fun hubShowsProvenanceChip() = runComposeUiTest {
        setContent { DayfoldTheme { TimelineCard(hubModel(), onOpen = {}) } }
        onNodeWithText("Added to this hub").assertExists()
    }

    @Test fun hubCollapsedShowsCheckN() = runComposeUiTest {
        setContent { DayfoldTheme { TimelineCard(hubCollapsedModel(), onOpen = {}) } }
        // leading Done-run of 4 collapses into a single "✓4" node
        onNodeWithText("✓4").assertExists()
    }
}
