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
)

@Serializable data class Changes(val cards: List<Card> = emptyList())
@Serializable data class Tombstone(val type: String, val id: String)

@Serializable
data class SyncResponse(
  val changes: Changes = Changes(),
  val tombstones: List<Tombstone> = emptyList(),
  @SerialName("next_cursor") val nextCursor: String? = null,
  @SerialName("has_more") val hasMore: Boolean = false,
)

// Redux state (the whole client state tree at M0 = the briefing feed).
data class AppState(
  val cards: List<Card> = emptyList(),
  val cursor: String? = null,
  val syncing: Boolean = false,
  val error: String? = null,
)

// Actions.
sealed interface Action
data object SyncStarted : Action
data class SyncSucceeded(val resp: SyncResponse) : Action
data class SyncFailed(val message: String) : Action
