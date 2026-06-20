package com.familyai.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire DTOs for the M0 /sync envelope (feed surface). Field names match the API
// (snake_case). NOTE: keep aligned with the SyncResponse contract in
// content.schema.json — a follow-up wires these to the generated Kotlin types.
@Serializable
data class Provenance(val source: String? = null) // "claude" | "email" | "user" | <url>

@Serializable
data class Card(
  val id: String,
  val kind: String = "info",
  val title: String,
  @SerialName("body_md") val bodyMd: String? = null,
  val provenance: Provenance? = null,
  // [review F2] /sync returns the full row — keep the feed-ordering + deep-link
  // fields, not just the title. not_before drives feed order (F1); target_* is
  // the deep-link the render layer will use when Hubs land.
  @SerialName("not_before") val notBefore: String? = null,
  @SerialName("expires_at") val expiresAt: String? = null,
  @SerialName("target_hub_id") val targetHubId: String? = null,
  @SerialName("target_section_id") val targetSectionId: String? = null,
  @SerialName("target_block_id") val targetBlockId: String? = null,
  // CL-4 (ADR 0022): typed content. `type` + `hub_ref` arrive snake/raw from the
  // server's DB-shaped /sync rows (like target_*). `payload` is externally tagged
  // ({"file":{…}}). `type` kept String (forward-compat, parity with `kind`).
  val type: String? = null,
  val payload: Payload? = null,
  val privacy: CardPrivacy? = null,
  @SerialName("hub_ref") val hubRef: String? = null,
)

// Typed content payload (ADR 0022 D1). The wire is externally tagged —
// `{"file":{…}}` — so this mirrors the generated `BriefingCardPayload` wrapper
// (packages/schema/kotlin-gen): exactly one variant is set (server CL-2
// cross-validation guarantees it). All fields nullable → immutable + back-compat.
@Serializable
data class Payload(
  val file: FilePayload? = null,
  val link: LinkPayload? = null,
  val invite: InvitePayload? = null,
  val contact: ContactPayload? = null,
  val geo: GeoPayload? = null,
  val email: EmailPayload? = null,
)

@Serializable
data class FilePayload(
  val filename: String? = null, val mime: String? = null, val size: Long? = null,
  val pages: Long? = null, val source: String? = null, val owner: String? = null,
  val modified: String? = null, val sharedWith: List<String>? = null, val docRef: String? = null,
)

@Serializable
data class LinkPayload(
  val url: String? = null, val domain: String? = null, val title: String? = null,
  val ogDesc: String? = null, val favicon: String? = null, val kind: String? = null,
  val fieldCount: Long? = null, val closesAt: String? = null, val savedAt: String? = null,
)

@Serializable
data class InvitePayload(
  val eventName: String? = null, val host: String? = null, val startAt: String? = null,
  val place: String? = null, val rsvpBy: String? = null,
  // display-of-state at M0 — no write path (ADR 0020/0016)
  val rsvpState: String? = null,
  val guestCount: Long? = null, val confirmedCount: Long? = null, val notes: String? = null,
)

@Serializable
data class ContactPayload(
  val name: String? = null, val company: String? = null, val role: String? = null,
  val phone: String? = null, val email: String? = null, val address: String? = null,
  val hours: String? = null, val linkedEventId: String? = null, val deliveryWindow: String? = null,
)

@Serializable
data class GeoPayload(
  val label: String? = null, val address: String? = null, val lat: Double? = null,
  val lng: Double? = null, val etaMin: Long? = null, val distance: String? = null,
  val travelMode: String? = null, val parking: String? = null, val leaveBy: String? = null,
  val linkedEventId: String? = null,
)

@Serializable
data class EmailPayload(
  val from: String? = null, val fromAddr: String? = null, val subject: String? = null,
  val date: String? = null, val threadLen: Long? = null,
  // [E2E-ciphertext @ M1] authored over the operator's OWN mail (Guardrail 3)
  val bodyExcerpt: String? = null,
  val attachments: List<Attachment>? = null, val labels: List<String>? = null,
)

@Serializable
data class Attachment(val name: String? = null, val mime: String? = null, val size: Long? = null)

// honesty chip (ADR 0014/0015) — stored verbatim; the client asserts nothing.
@Serializable
data class CardPrivacy(val storage: String? = null)

@Serializable data class Changes(val cards: List<Card> = emptyList())
@Serializable data class Tombstone(val type: String, val id: String)

@Serializable
data class SyncResponse(
  val changes: Changes = Changes(),
  val tombstones: List<Tombstone> = emptyList(),
  @SerialName("next_cursor") val nextCursor: String? = null,
  @SerialName("has_more") val hasMore: Boolean = false,
)

// Redux state (the whole client state tree at M0 = the briefing feed). The
// cursor lives in the DB (sync_meta), not here — the store is a projection of the DB.
data class AppState(
  val cards: List<Card> = emptyList(),
  val syncing: Boolean = false,
  val error: String? = null,
)

// Actions. Card data reaches the store ONLY via CardsLoaded (the DB→store bridge);
// SyncStarted/SyncSucceeded/SyncFailed carry sync STATUS only.
sealed interface Action
data object SyncStarted : Action
data object SyncSucceeded : Action
data class SyncFailed(val message: String) : Action
data class CardsLoaded(val cards: List<Card>) : Action
