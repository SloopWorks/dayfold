package com.sloopworks.dayfold.client
import com.sloopworks.dayfold.client.cards.CardAction

fun Attachment.toCardAction(): CardAction? = when (kind) {
  "call" -> tel?.let { CardAction.Call(it) }
  "nav"  -> query?.let { CardAction.Navigate(it) }
  "link" -> url?.let { CardAction.OpenUrl(it) }
  "open" -> ref?.let {
    val arrival = when {
      it.blockId != null -> HubArrival(HubArrivalLevel.BLOCK, it.blockId, HubArrivalSource.BRIEFING)
      it.sectionId != null -> HubArrival(HubArrivalLevel.SECTION, it.sectionId, HubArrivalSource.BRIEFING)
      else -> null
    }
    CardAction.OpenHub(it.hubId, arrival)
  }
  else   -> null
}
