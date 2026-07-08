package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HubArrivalIndexTest {
  // s1(ord0): b1,b2 ; s2(ord1): b3 — item order: [status][cd?][honesty?] then per
  // section [header][blocks]. Helper must match HubDetailScreen's emission exactly.
  private val tree = HubTree(
    hub = Hub(id = "h1", title = "Party", status = "active", visibility = "family"),
    sections = listOf(
      HubSection(id = "s2", hubId = "h1", title = "Day of", ord = 1),   // out of order on purpose
      HubSection(id = "s1", hubId = "h1", title = "Shopping", ord = 0),
    ),
    blocks = listOf(
      HubBlock(id = "b2", sectionId = "s1", type = "text", ord = 1),
      HubBlock(id = "b1", sectionId = "s1", type = "text", ord = 0),
      HubBlock(id = "b3", sectionId = "s2", type = "text", ord = 0),
    ),
  )

  @Test fun indexesBlocksRespectingHeaderPrelude() {
    // no countdown/honesty: status(0) s1hdr(1) b1(2) b2(3) s2hdr(4) b3(5)
    assertEquals(2, focusedBlockItemIndex(tree, "b1", hasCountdown = false, restricted = false))
    assertEquals(3, focusedBlockItemIndex(tree, "b2", hasCountdown = false, restricted = false))
    assertEquals(5, focusedBlockItemIndex(tree, "b3", hasCountdown = false, restricted = false))
  }

  @Test fun indexesSectionHeadersForSectionDeepLinks() {
    // A deep-link may target a SECTION (blk-* vs sec-* are distinct id namespaces). It resolves
    // to the section HEADER item so the section scrolls to the top: s1hdr(1), s2hdr(4).
    assertEquals(1, focusedBlockItemIndex(tree, "s1", hasCountdown = false, restricted = false))
    assertEquals(4, focusedBlockItemIndex(tree, "s2", hasCountdown = false, restricted = false))
  }

  @Test fun preludeItemsShiftTheIndex() {
    // +countdown +honesty → prelude = 3: b2 = 3(prelude) + 1(s1hdr) + 1(pos) = 5
    assertEquals(5, focusedBlockItemIndex(tree, "b2", hasCountdown = true, restricted = true))
  }

  @Test fun nullFocusOrMissingBlockYieldsNull() {
    assertNull(focusedBlockItemIndex(tree, null, hasCountdown = false, restricted = false))
    assertNull(focusedBlockItemIndex(tree, "nope", hasCountdown = false, restricted = false))
  }

  @Test fun timelineCardShiftsIndexByOne() {
    // hasTimelineCard=true must return exactly 1 more than hasTimelineCard=false
    val withoutTl = focusedBlockItemIndex(tree, "b3", hasCountdown = false, restricted = false, hasTimelineCard = false)
    val withTl    = focusedBlockItemIndex(tree, "b3", hasCountdown = false, restricted = false, hasTimelineCard = true)
    assertEquals(withoutTl!! + 1, withTl)
  }

  @Test fun emptySectionRendersNothingAndDoesNotShiftTheIndex() {
    // an empty section (created via CLI, blocks not pushed yet) renders no header,
    // so it must not occupy an item slot before a later focused block.
    val withEmpty = HubTree(
      hub = Hub(id = "h1", title = "Party", status = "active", visibility = "family"),
      sections = listOf(
        HubSection(id = "empty", hubId = "h1", title = "Coming soon", ord = 0),   // no blocks
        HubSection(id = "s1", hubId = "h1", title = "Shopping", ord = 1),
      ),
      blocks = listOf(HubBlock(id = "b1", sectionId = "s1", type = "text", ord = 0)),
    )
    // status(0), [empty skipped], s1hdr(1), b1(2)
    assertEquals(2, focusedBlockItemIndex(withEmpty, "b1", hasCountdown = false, restricted = false))
  }

  // The bug: a W5-hidden block before the target was still counted, so the arrival scroll
  // overshot by the number of hidden blocks. The helper must mirror the render's partitionHidden.
  @Test fun `hidden blocks before the target shift the index down (mirror partitionHidden)`() {
    // hide b1 → render: status(0) s1hdr(1) b2(2) s2hdr(3) b3(4). b3 was 5 without hiding.
    assertEquals(2, focusedBlockItemIndex(tree, "b2", hasCountdown = false, restricted = false, hiddenIds = setOf("b1")))
    assertEquals(4, focusedBlockItemIndex(tree, "b3", hasCountdown = false, restricted = false, hiddenIds = setOf("b1")))
    // section header index also shifts: s2 header now at 3 (was 4)
    assertEquals(3, focusedBlockItemIndex(tree, "s2", hasCountdown = false, restricted = false, hiddenIds = setOf("b1")))
  }

  @Test fun `a fully-hidden section renders no header and is not a scroll target`() {
    // hide b3 (s2's only block) → s2 renders nothing. targeting it (or its block) → null (no scroll).
    assertNull(focusedBlockItemIndex(tree, "s2", hasCountdown = false, restricted = false, hiddenIds = setOf("b3")))
    assertNull(focusedBlockItemIndex(tree, "b3", hasCountdown = false, restricted = false, hiddenIds = setOf("b3")))
  }

  @Test fun `preceding sync-queue banners shift every index by their count`() {
    val base = focusedBlockItemIndex(tree, "b1", hasCountdown = false, restricted = false)!!
    assertEquals(base + 2, focusedBlockItemIndex(tree, "b1", hasCountdown = false, restricted = false, precedingBanners = 2))
  }
}
