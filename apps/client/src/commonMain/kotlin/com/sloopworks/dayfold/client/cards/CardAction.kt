package com.sloopworks.dayfold.client.cards

// CL-5 (ADR 0022) — the closed, typed union of things a Now card can ask for.
// The UI builds a CardAction and hands it to `onAction`; the PLATFORM EFFECT
// LAYER (expect/actual PlatformActions — a CL-6 prerequisite shared with
// tap-to-detail) performs it. Modeling actions as a vetted union (not freeform
// URLs in the UI) keeps scheme-vetting out of the composables and at one seam.
//
// M0 is read-only (ADR 0020): every action here is either an OS handoff
// (open/call/message/email/navigate/copy/share) or in-app nav (OpenDetail).
// Nothing writes our backend — no RSVP/mutate action exists by design.
sealed interface CardAction {
  /** In-app: open this card's full detail (CL-6 builds the screen + nav). */
  data class OpenDetail(val cardId: String) : CardAction
  /** OS handoff: open an https URL (form, doc, link). */
  data class OpenUrl(val url: String) : CardAction
  /** OS handoff: dial a phone number (tel:). */
  data class Call(val number: String) : CardAction
  /** OS handoff: SMS a phone number (sms:). */
  data class Message(val number: String) : CardAction
  /** OS handoff: compose mail (mailto:). */
  data class Email(val mailto: String) : CardAction
  /** OS handoff: navigate to an address/place (geo:/maps) — no position leak (ADR 0014). */
  data class Navigate(val query: String) : CardAction
  /** Clipboard. */
  data class Copy(val text: String) : CardAction
  /** Share sheet. */
  data class Share(val text: String) : CardAction
}
