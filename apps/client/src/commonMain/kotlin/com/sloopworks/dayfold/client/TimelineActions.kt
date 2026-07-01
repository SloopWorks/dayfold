package com.sloopworks.dayfold.client
import com.sloopworks.dayfold.client.cards.CardAction

fun Attachment.toCardAction(): CardAction? = when (kind) {
  "call" -> tel?.let { CardAction.Call(it) }
  "nav"  -> query?.let { CardAction.Navigate(it) }
  "link" -> url?.let { CardAction.OpenUrl(it) }
  "open" -> ref?.let { CardAction.OpenHub(it.hubId, it.blockId) }
  else   -> null
}
