package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
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

// ADR 0046 — the derived timeline renders through the same card + detail with the honest
// "From this hub's dates" provenance and per-stop source tags. A hub with no authored timeline.
@OptIn(ExperimentalTestApi::class)
class DerivedTimelineSnapshotTest {
    private val ny = TimeZone.of("America/New_York")
    private val nowIso = "2026-08-24T14:40:00Z"   // move-in day, 10:40 ET

    private fun b(id: String, type: String, payload: BlockPayload? = null, triggers: List<BlockTrigger>? = null) =
        HubBlock(id = id, sectionId = "s", type = type, payload = payload, triggers = triggers)

    // A hub with NO authored timeline, but plenty of dated blocks → derives a both-scales timeline.
    private fun tree() = HubTree(
        hub = Hub(id = "h", title = "Maya starts college", countdownTo = "2026-08-24", startAt = "2026-05-01"),
        blocks = listOf(
            b("m1", "milestone", BlockPayload(date = "2026-06-12", label = "Housing confirmed")),
            b("c1", "checklist", BlockPayload(items = listOf(
                ChecklistItem(id = "i1", text = "Car loaded", due = "2026-08-24T07:30:00-04:00", done = true, assignee = "Pat"),
                ChecklistItem(id = "i2", text = "Bookstore & student ID", due = "2026-08-24T14:00:00-04:00"),
            ))),
            b("loc", "location", BlockPayload(label = "Checked in"),
                triggers = listOf(BlockTrigger(whenTrigger = TriggerWhen(at = "2026-08-24T09:50:00-04:00")))),
        ),
    )

    private fun derived() = deriveTimeline(tree(), ny)!!

    @Test fun cardLight() = runComposeUiTest {
        val model = presentTimelineCard(derived(), nowIso, ny)!!
        setContent {
            DayfoldTheme(darkTheme = false) {
                Box(Modifier.width(390.dp).background(Color(0xFFE9DDD7)).padding(16.dp)) {
                    TimelineCard(model) {}
                }
            }
        }
        onNodeWithText("From this hub’s dates").assertExists()   // honest derived chip, not "Added to this hub"
        val img = onRoot().captureToImage()
        assertTrue(img.width > 0)
        File("build/snapshots").apply { mkdirs() }
            .let { d -> ImageIO.write(img.toAwtImage(), "png", File(d, "derived-timeline-card-light.png")) }
    }

    @Test fun detailLight() = runComposeUiTest {
        setContent {
            DayfoldTheme(darkTheme = false) {
                Box(Modifier.width(390.dp).height(820.dp)) {
                    TimelineDetail(derived(), TimelineScale.Day, nowIso, ny, {}, {})
                }
            }
        }
        val img = onRoot().captureToImage()
        assertTrue(img.width > 0)
        File("build/snapshots").apply { mkdirs() }
            .let { d -> ImageIO.write(img.toAwtImage(), "png", File(d, "derived-timeline-detail-light.png")) }
        // the honest derived footnote (distinct from the authored "added to this hub's plan").
        // Per-stop source tags (hub date / checklist / pickup) are visible in the PNG.
        onNodeWithText("Nothing was authored here", substring = true).assertExists()
    }
}
