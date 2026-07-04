package com.sloopworks.dayfold.client
import com.sloopworks.dayfold.client.cards.CardAction

fun Attachment.toCardAction(): CardAction? = when (kind) {
  "call" -> tel?.let { CardAction.Call(it) }
  "nav"  -> query?.let { CardAction.Navigate(it) }
  "link" -> url?.let { CardAction.OpenUrl(it) }
  // focus target: a specific block when given, else the section (most refs are section-level).
  // blk-* / sec-* are distinct id namespaces, so one focus id resolves either on arrival.
  "open" -> ref?.let { CardAction.OpenHub(it.hubId, it.blockId ?: it.sectionId) }
  else   -> null
}
