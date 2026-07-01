package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlinx.datetime.TimeZone
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

// ADR 0045 — snapshot + behavioral tests for TimelineDetail (day + hub scales, light mode).
// Exercises grouped sticky headers, NOW line, entry rows, attachment chips,
// assignee avatars, and the provenance footnote.
@OptIn(ExperimentalTestApi::class)
class TimelineDetailSnapshotTest {

    private val ny = TimeZone.of("America/New_York")
    private val nowIso = "2026-08-24T10:40:00-04:00"

    // Day timeline: 6 stops across MORNING + AFTERNOON, 3 done before nowIso,
    // "Elevator slot" is Next, attachment chips and an assignee.
    private fun dayTimeline() = Timeline(
        title = "Move-in day",
        tz = "America/New_York",
        stops = listOf(
            Stop(
                at = "2026-08-24T07:30:00-04:00",
                title = "Car loaded",
                sub = "Boxes, mini-fridge, bedding",
                assignee = "Pat",
                done = true,
            ),
            Stop(at = "2026-08-24T08:00:00-04:00", title = "Left home", done = true),
            Stop(
                at = "2026-08-24T09:50:00-04:00",
                title = "Checked in",
                done = true,
                attachments = listOf(
                    Attachment(kind = "nav", label = "Map", query = "Henderson Hall")
                ),
            ),
            Stop(
                at = "2026-08-24T11:00:00-04:00",
                title = "Elevator slot",
                sub = "20-min window — grab the loading cart",
                attachments = listOf(
                    Attachment(kind = "link", label = "Booklist", url = "https://example.com")
                ),
            ),
            Stop(at = "2026-08-24T12:30:00-04:00", title = "Lunch break", sub = "Campus dining hall"),
            Stop(at = "2026-08-24T14:00:00-04:00", title = "Bookstore run"),
        ),
    )

    // Hub timeline: 5 date-only stops spanning May–Sep 2026. "Move-in day" is major.
    private fun hubTimeline() = Timeline(
        title = "College Roadmap",
        tz = "America/New_York",
        stops = listOf(
            Stop(at = "2026-05-01", title = "Enrollment deposit paid", assignee = "Pat", done = true),
            Stop(at = "2026-06-12", title = "Housing application submitted", assignee = "Maya", done = true),
            Stop(at = "2026-07-20", title = "Orientation completed", done = true),
            Stop(
                at = "2026-08-24",
                title = "Move-in day",
                major = true,
                sub = "Henderson Hall, room 214",
                assignee = "Pat + Maya",
                attachments = listOf(
                    Attachment(kind = "nav", label = "Map", query = "Henderson Hall")
                ),
            ),
            Stop(at = "2026-09-15", title = "Family weekend"),
            Stop(at = "2026-10-01", title = "Graduation open house", major = true),
        ),
    )

    private fun shot(name: String, tl: Timeline, scale: TimelineScale) = runComposeUiTest {
        setContent {
            DayfoldTheme(darkTheme = false) {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(
                        tl = tl,
                        scale = scale,
                        nowIso = nowIso,
                        tz = ny,
                        onBack = {},
                        onAction = {},
                    )
                }
            }
        }
        val img = onRoot().captureToImage()
        assertTrue(img.width > 0 && img.height > 0, "snapshot $name has no pixels")
        File("build/snapshots").apply { mkdirs() }
            .let { dir -> ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png")) }
    }

    // Variant that scrolls to a given list index before capturing, to show below-fold items.
    private fun shotScrolled(name: String, tl: Timeline, scale: TimelineScale, scrollToIdx: Int) =
        runComposeUiTest {
            setContent {
                DayfoldTheme(darkTheme = false) {
                    Box(Modifier.width(390.dp).height(780.dp)) {
                        TimelineDetail(
                            tl = tl,
                            scale = scale,
                            nowIso = nowIso,
                            tz = ny,
                            onBack = {},
                            onAction = {},
                        )
                    }
                }
            }
            onNode(hasScrollAction()).performScrollToIndex(scrollToIdx)
            val img = onRoot().captureToImage()
            assertTrue(img.width > 0 && img.height > 0, "snapshot $name has no pixels")
            File("build/snapshots").apply { mkdirs() }
                .let { dir -> ImageIO.write(img.toAwtImage(), "png", File(dir, "$name.png")) }
        }

    // ── Snapshot outputs ──────────────────────────────────────────────────────

    @Test fun dayLight()  = shot("timeline-detail-day-light", dayTimeline(), TimelineScale.Day)
    @Test fun hubLight()  = shot("timeline-detail-hub-light", hubTimeline(), TimelineScale.Hub)
    // Scrolled hub snapshot: shows the October future-major stop (star glyph + T4 card).
    @Test fun hubMajorFutureLight() =
        shotScrolled("timeline-detail-hub-major-future-light", hubTimeline(), TimelineScale.Hub, 12)

    // ── Behavioral assertions — Day ───────────────────────────────────────────

    @Test fun dayShowsMorningGroup() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(dayTimeline(), TimelineScale.Day, nowIso, ny, {}, {})
                }
            }
        }
        onNodeWithText("MORNING", substring = true).assertExists()
    }

    @Test fun dayShowsEntryTitle() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(dayTimeline(), TimelineScale.Day, nowIso, ny, {}, {})
                }
            }
        }
        onNodeWithText("Elevator slot", substring = true).assertExists()
    }

    @Test fun dayShowsAttachmentChip() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(dayTimeline(), TimelineScale.Day, nowIso, ny, {}, {})
                }
            }
        }
        onNodeWithText("Booklist", substring = true).assertExists()
    }

    @Test fun dayShowsAssignee() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(dayTimeline(), TimelineScale.Day, nowIso, ny, {}, {})
                }
            }
        }
        onNodeWithText("Pat", substring = true).assertExists()
    }

    @Test fun dayShowsProvenanceNote() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(dayTimeline(), TimelineScale.Day, nowIso, ny, {}, {})
                }
            }
        }
        // Scroll to provenance item (index 9 = last in the 10-item day list).
        onNode(hasScrollAction()).performScrollToIndex(9)
        onNodeWithText("These stops were added", substring = true).assertExists()
    }

    // ── Behavioral assertions — Hub ───────────────────────────────────────────

    @Test fun hubShowsAugustGroup() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(hubTimeline(), TimelineScale.Hub, nowIso, ny, {}, {})
                }
            }
        }
        onNodeWithText("AUGUST", substring = true).assertExists()
    }

    @Test fun hubShowsEntryTitle() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(hubTimeline(), TimelineScale.Hub, nowIso, ny, {}, {})
                }
            }
        }
        onNodeWithText("Move-in day", substring = true).assertExists()
    }

    @Test fun hubShowsAttachmentChip() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(hubTimeline(), TimelineScale.Hub, nowIso, ny, {}, {})
                }
            }
        }
        onNodeWithText("Map", substring = true).assertExists()
    }

    @Test fun hubShowsAssignee() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(hubTimeline(), TimelineScale.Hub, nowIso, ny, {}, {})
                }
            }
        }
        onNodeWithText("Pat + Maya", substring = true).assertExists()
    }

    @Test fun hubShowsFutureMajorTitle() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(hubTimeline(), TimelineScale.Hub, nowIso, ny, {}, {})
                }
            }
        }
        // Scroll so the October future-major stop is visible (near bottom of list).
        onNode(hasScrollAction()).performScrollToIndex(13)
        onNodeWithText("Graduation open house", substring = true).assertExists()
    }

    @Test fun hubShowsProvenanceNote() = runComposeUiTest {
        setContent {
            DayfoldTheme {
                Box(Modifier.width(390.dp).height(780.dp)) {
                    TimelineDetail(hubTimeline(), TimelineScale.Hub, nowIso, ny, {}, {})
                }
            }
        }
        // Scroll to provenance item (index 13 = last in the 14-item hub list).
        onNode(hasScrollAction()).performScrollToIndex(13)
        onNodeWithText("These milestones were added", substring = true).assertExists()
    }
}
