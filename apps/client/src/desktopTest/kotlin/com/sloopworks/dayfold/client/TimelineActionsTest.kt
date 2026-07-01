package com.sloopworks.dayfold.client
import com.sloopworks.dayfold.client.cards.CardAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineActionsTest {
  @Test fun `maps each kind`() {
    assertEquals(CardAction.Call("18005551212"), Attachment("call","Call",tel="18005551212").toCardAction())
    assertEquals(CardAction.Navigate("Henderson Hall"), Attachment("nav","Map",query="Henderson Hall").toCardAction())
    assertEquals(CardAction.OpenUrl("https://x.test"), Attachment("link","List",url="https://x.test").toCardAction())
    assertEquals(CardAction.OpenHub("h1","b2"), Attachment("open","List",ref=AttachmentRef("h1",blockId="b2")).toCardAction())
  }
  @Test fun `missing field → null`() {
    assertNull(Attachment("call","Call").toCardAction())
    assertNull(Attachment("open","x").toCardAction())
  }
}
