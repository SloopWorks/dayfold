package com.sloopworks.dayfold.client.cards

import com.sloopworks.dayfold.client.Card

// Pure (no Composer) detail derivations — the DETAILS meta rows + the actions row.
// Off the recomposition hot path; unit-tested (golden-stable). Dates are shown as
// authored (ISO) at M0; relative formatting is a follow. (CL-6, ADR 0022.)

data class MetaRow(val label: String, val value: String)

enum class ActionStyle { Filled, Tonal, Outlined }
data class DetailAction(val label: String, val action: CardAction, val style: ActionStyle)

private fun sizePages(size: Long?, pages: Long?): String? {
  val s = size?.let { "${(it + 512) / 1024} KB" }
  val p = pages?.let { "$it pages" }
  return listOfNotNull(s, p).joinToString(" · ").ifBlank { null }
}

/** DETAILS label/value rows derived from the typed payload (only present fields). */
fun detailMeta(card: Card): List<MetaRow> {
  val p = card.payload ?: return emptyList()
  return when (card.type) {
    "file" -> listOfNotNull(
      p.file?.filename?.let { MetaRow("File", it) },
      p.file?.source?.let { MetaRow("Source", it) },
      sizePages(p.file?.size, p.file?.pages)?.let { MetaRow("Size", it) },
      p.file?.modified?.let { MetaRow("Modified", it) },
      p.file?.owner?.let { MetaRow("Owner", it) },
    )
    "link" -> listOfNotNull(
      p.link?.url?.let { MetaRow("URL", it) },
      p.link?.domain?.let { MetaRow("Site", it) },
      p.link?.let { l -> l.kind?.let { k -> MetaRow("Type", if (k == "form" && l.fieldCount != null) "Form · ${l.fieldCount} fields" else k) } },
      p.link?.closesAt?.let { MetaRow("Closes", it) },
      p.link?.savedAt?.let { MetaRow("Saved", it) },
    )
    "invite" -> listOfNotNull(
      p.invite?.startAt?.let { MetaRow("When", it) },
      p.invite?.place?.let { MetaRow("Where", it) },
      p.invite?.rsvpBy?.let { MetaRow("RSVP by", it) },
      p.invite?.let { i -> i.guestCount?.let { MetaRow("Guests", listOfNotNull("$it", i.confirmedCount?.let { c -> "$c confirmed" }).joinToString(" · ")) } },
      p.invite?.notes?.let { MetaRow("Note", it) },
    )
    "contact" -> listOfNotNull(
      p.contact?.phone?.let { MetaRow("Phone", it) },
      p.contact?.email?.let { MetaRow("Email", it) },
      p.contact?.address?.let { MetaRow("Address", it) },
      p.contact?.hours?.let { MetaRow("Hours", it) },
      p.contact?.deliveryWindow?.let { MetaRow("Window", it) },
    )
    "geo" -> listOfNotNull(
      p.geo?.address?.let { MetaRow("Address", it) },
      p.geo?.let { g -> g.etaMin?.let { MetaRow("Drive", listOfNotNull("$it min", g.distance).joinToString(" · ")) } },
      p.geo?.leaveBy?.let { MetaRow("Leave by", it) },
      p.geo?.parking?.let { MetaRow("Parking", it) },
      p.geo?.travelMode?.let { MetaRow("Mode", it) },
    )
    "email" -> listOfNotNull(
      p.email?.from?.let { MetaRow("From", it) },
      p.email?.subject?.let { MetaRow("Subject", it) },
      p.email?.date?.let { MetaRow("Date", it) },
      p.email?.threadLen?.let { MetaRow("Thread", "$it messages") },
      p.email?.attachments?.takeIf { it.isNotEmpty() }?.let { MetaRow("Attachments", "${it.size}") },
    )
    else -> emptyList()
  }
}

/** The detail actions row — SAFE handoffs only (ADR 0020 read-only): no
 *  Add-to-Hub / Save / RSVP-write (backend mutations). Missing target → skipped. */
fun detailActions(card: Card): List<DetailAction> {
  val p = card.payload ?: return emptyList()
  fun https(s: String?) = s?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
  return when (card.type) {
    "file" -> listOfNotNull(
      https(p.file?.docRef)?.let { DetailAction("Open", CardAction.OpenUrl(it), ActionStyle.Filled) },
      DetailAction("Share", CardAction.Share(card.title), ActionStyle.Tonal),
    )
    "link" -> listOfNotNull(
      https(p.link?.url)?.let { DetailAction(if (p.link?.kind == "form") "Open form" else "Open", CardAction.OpenUrl(it), ActionStyle.Filled) },
      https(p.link?.url)?.let { DetailAction("Copy link", CardAction.Copy(it), ActionStyle.Tonal) },
    )
    "invite" -> listOfNotNull(
      p.invite?.place?.let { DetailAction("Directions", CardAction.Navigate(it), ActionStyle.Tonal) },
      DetailAction("Share", CardAction.Share(card.title), ActionStyle.Outlined),
    )
    "contact" -> listOfNotNull(
      (p.contact?.address)?.let { DetailAction("Directions", CardAction.Navigate(it), ActionStyle.Filled) },
      p.contact?.phone?.let { DetailAction("Copy number", CardAction.Copy(it), ActionStyle.Tonal) },
    )
    "geo" -> listOfNotNull(
      (p.geo?.address ?: p.geo?.label)?.let { DetailAction("Navigate", CardAction.Navigate(it), ActionStyle.Filled) },
      p.geo?.address?.let { DetailAction("Copy address", CardAction.Copy(it), ActionStyle.Tonal) },
    )
    "email" -> listOfNotNull(
      p.email?.fromAddr?.let { DetailAction("Reply", CardAction.Email("mailto:$it"), ActionStyle.Filled) },
      DetailAction("Share", CardAction.Share(card.title), ActionStyle.Tonal),
    )
    else -> emptyList()
  }
}
