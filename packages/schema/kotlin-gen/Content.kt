// To parse the JSON, install kotlin's serialization plugin and do:
//
// val json    = Json { allowStructuredMapKeys = true }
// val content = json.parse(Content.serializer(), jsonString)

package com.familyai.schema

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

@Serializable
data class Content (
    @SerialName("Block")
    val block: Block? = null,

    @SerialName("BriefingCard")
    val briefingCard: BriefingCard? = null,

    @SerialName("Hub")
    val hub: Hub? = null,

    @SerialName("Place")
    val place: Place? = null,

    @SerialName("Section")
    val section: WrapperSchema? = null,

    @SerialName("SyncResponse")
    val syncResponse: SyncResponse? = null
)

@Serializable
data class Block (
    val actions: List<ActionElement>? = null,

    /**
     * long-form markdown (text/markdown blocks); inline ≤1MB at M0, else spill to body_ref (06,
     * M1)
     */
    @SerialName("body_md")
    val bodyMd: String? = null,

    /**
     * object-storage KEY when spilled (M1); never a URL; XOR with body_md
     */
    @SerialName("body_ref")
    val bodyRef: String? = null,

    val id: String,
    val ord: Long? = null,

    /**
     * structured fields for non-markdown block types; variant by `type` (see $comment)
     */
    val payload: BlockPayload? = null,

    val provenance: Provenance,
    val triggers: List<TriggerElement>? = null,
    val type: BlockType,
    val version: Long? = null
)

/**
 * ADR 0016 RESERVED (bounded-now: buttons + structured asks; not built at MVP).
 */
@Serializable
data class ActionElement (
    @SerialName("action_id")
    val actionID: String,

    val label: String,
    val params: JsonObject? = null
)

/**
 * structured fields for non-markdown block types; variant by `type` (see $comment)
 */
@Serializable
data class BlockPayload (
    val label: String? = null,
    val source: String? = null,
    val url: String? = null,
    val items: List<Item>? = null,
    val kind: String? = null,

    /**
     * url | fileRef (links+small refs at MVP)
     */
    val ref: String? = null,

    val date: String? = null,
    val email: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val role: String? = null,
    val address: String? = null,

    @SerialName("mapUrl")
    val mapURL: String? = null
)

@Serializable
data class Item (
    val assignee: String? = null,
    val done: Boolean? = null,
    val due: String? = null,
    val text: String? = null,
    val amount: Double? = null,
    val label: String? = null,
    val paid: Boolean? = null
)

@Serializable
data class Provenance (
    val at: String,

    /**
     * which credential pushed this (audit)
     */
    @SerialName("credential_id")
    val credentialID: String? = null,

    /**
     * claude | email | user | <url>
     */
    val source: String
)

/**
 * ADR 0014 — matched ON-DEVICE; live position never leaves.
 *
 * schema slot; matching DEFERRED
 */
@Serializable
data class TriggerElement (
    val geo: TriggerGeo? = null,

    @SerialName("when")
    val wrapperSchemaWhen: When? = null,

    val activity: Activity? = null
)

@Serializable
data class Activity (
    val kind: ActivityKind? = null
)

@Serializable
enum class ActivityKind(val value: String) {
    @SerialName("biking") Biking("biking"),
    @SerialName("driving") Driving("driving"),
    @SerialName("running") Running("running"),
    @SerialName("walking") Walking("walking");
}

@Serializable
data class TriggerGeo (
    val label: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,

    @SerialName("place_ref")
    val placeRef: String? = null,

    @SerialName("radius_m")
    val radiusM: Long? = null
)

@Serializable
data class When (
    @SerialName("alert_offset")
    val alertOffset: String? = null,

    val at: String? = null,
    val recurring: String? = null,
    val relative: String? = null,
    val window: JsonObject? = null
)

@Serializable
enum class BlockType(val value: String) {
    @SerialName("budget") Budget("budget"),
    @SerialName("checklist") Checklist("checklist"),
    @SerialName("contact") Contact("contact"),
    @SerialName("document") Document("document"),
    @SerialName("link") Link("link"),
    @SerialName("location") Location("location"),
    @SerialName("markdown") Markdown("markdown"),
    @SerialName("milestone") Milestone("milestone"),
    @SerialName("text") Text("text");
}

/**
 * the 'Now' surface
 */
@Serializable
data class BriefingCard (
    val actions: List<ActionElement>? = null,

    /**
     * limited inline markdown only (1MB cap, F8)
     */
    @SerialName("body_md")
    val bodyMd: String? = null,

    @SerialName("expires_at")
    val expiresAt: String? = null,

    /**
     * parent Hub id — the adaptive supporting pane's 'PART OF THIS HUB' (ADR 0022; CL-10).
     * Optional.
     */
    val hubRef: String? = null,

    val id: String,
    val kind: BriefingCardKind,

    @SerialName("not_before")
    val notBefore: String? = null,

    /**
     * [E2E-ciphertext at M1] typed content payload, variant selected by `type` (ADR 0022 D1).
     * Inline oneOf (no internal $ref) so codegen emits TYPED variants, never z.any.
     */
    val payload: BriefingCardPayload? = null,

    /**
     * honesty chip (ADR 0014/0015) — a claim allowed ONLY where a real schema/API/client
     * boundary enforces it.
     */
    val privacy: Privacy? = null,

    val provenance: Provenance,

    /**
     * cross-links to other cards in THIS family (CL-8). targetId resolves client-side vs the
     * local cache; title/sub are author-denormalized so a row renders without resolving.
     * Same-tenant only (rides authorizeTenant).
     */
    val related: List<Related>? = null,

    /**
     * section header for the RELATED rows (e.g. 'FROM THE SAME EMAIL'). CL-8.
     */
    val relatedKicker: String? = null,

    /**
     * deep-link into a hub (resolved client-side vs local cache, nearest-ancestor)
     */
    val target: Target? = null,

    val title: String,
    val triggers: List<TriggerElement>? = null,

    /**
     * content type (ADR 0022 D1) — drives the Now-card / detail layout. OPTIONAL for
     * back-compat with kind-only M0 cards.
     */
    val type: TargetTypeEnum? = null,

    val version: Long? = null
)

@Serializable
enum class BriefingCardKind(val value: String) {
    @SerialName("action") Action("action"),
    @SerialName("countdown") Countdown("countdown"),
    @SerialName("info") Info("info"),
    @SerialName("weather") Weather("weather");
}

/**
 * [E2E-ciphertext at M1] typed content payload, variant selected by `type` (ADR 0022 D1).
 * Inline oneOf (no internal $ref) so codegen emits TYPED variants, never z.any.
 */
@Serializable
data class BriefingCardPayload (
    val file: File? = null,
    val link: Link? = null,
    val invite: Invite? = null,
    val contact: Contact? = null,
    val geo: PayloadGeo? = null,
    val email: Email? = null
)

@Serializable
data class Contact (
    val address: String? = null,
    val company: String? = null,
    val deliveryWindow: String? = null,
    val email: String? = null,
    val hours: String? = null,

    @SerialName("linkedEventId")
    val linkedEventID: String? = null,

    val name: String? = null,
    val phone: String? = null,
    val role: String? = null
)

@Serializable
data class Email (
    val attachments: List<Attachment>? = null,

    /**
     * [E2E-ciphertext] authored over the operator's OWN mail (CLI/Claude) — never a server-side
     * Gmail restricted-scope read (Guardrail 3)
     */
    val bodyExcerpt: String? = null,

    val date: String? = null,
    val from: String? = null,
    val fromAddr: String? = null,
    val labels: List<String>? = null,
    val subject: String? = null,
    val threadLen: Long? = null
)

@Serializable
data class Attachment (
    val mime: String? = null,
    val name: String? = null,
    val size: Long? = null
)

@Serializable
data class File (
    /**
     * url | opaque storage ref
     */
    val docRef: String? = null,

    val filename: String? = null,
    val mime: String? = null,
    val modified: String? = null,
    val owner: String? = null,
    val pages: Long? = null,
    val sharedWith: List<String>? = null,
    val size: Long? = null,
    val source: String? = null
)

@Serializable
data class PayloadGeo (
    val address: String? = null,
    val distance: String? = null,
    val etaMin: Long? = null,
    val label: String? = null,
    val lat: Double? = null,
    val leaveBy: String? = null,

    @SerialName("linkedEventId")
    val linkedEventID: String? = null,

    val lng: Double? = null,
    val parking: String? = null,
    val travelMode: String? = null
)

@Serializable
data class Invite (
    val confirmedCount: Long? = null,
    val eventName: String? = null,
    val guestCount: Long? = null,
    val host: String? = null,
    val notes: String? = null,
    val place: String? = null,
    val rsvpBy: String? = null,

    /**
     * display-of-state at M0 (no write path; ADR 0020/0016)
     */
    val rsvpState: RsvpState? = null,

    val startAt: String? = null
)

/**
 * display-of-state at M0 (no write path; ADR 0020/0016)
 */
@Serializable
enum class RsvpState(val value: String) {
    @SerialName("no") No("no"),
    @SerialName("none") None("none"),
    @SerialName("yes") Yes("yes");
}

@Serializable
data class Link (
    val closesAt: String? = null,
    val domain: String? = null,
    val favicon: String? = null,
    val fieldCount: Long? = null,
    val kind: LinkKind? = null,

    /**
     * author-stamped OG; server never fetches the URL (no SSRF)
     */
    val ogDesc: String? = null,

    val savedAt: String? = null,
    val title: String? = null,
    val url: String? = null
)

@Serializable
enum class LinkKind(val value: String) {
    @SerialName("form") Form("form"),
    @SerialName("page") Page("page");
}

/**
 * honesty chip (ADR 0014/0015) — a claim allowed ONLY where a real schema/API/client
 * boundary enforces it.
 */
@Serializable
data class Privacy (
    val storage: Storage? = null
)

@Serializable
enum class Storage(val value: String) {
    @SerialName("in_browser") InBrowser("in_browser"),
    @SerialName("location_local") LocationLocal("location_local"),
    @SerialName("matched_on_device") MatchedOnDevice("matched_on_device"),
    @SerialName("on_device") OnDevice("on_device");
}

@Serializable
data class Related (
    /**
     * same-email | same-thread | same-hub | same-trip | attachment | contact-of
     */
    val relation: String,

    val sub: String? = null,

    @SerialName("targetId")
    val targetID: String,

    val targetType: TargetTypeEnum,
    val title: String? = null
)

/**
 * content type (ADR 0022 D1) — drives the Now-card / detail layout. OPTIONAL for
 * back-compat with kind-only M0 cards.
 */
@Serializable
enum class TargetTypeEnum(val value: String) {
    @SerialName("contact") Contact("contact"),
    @SerialName("email") Email("email"),
    @SerialName("file") File("file"),
    @SerialName("geo") Geo("geo"),
    @SerialName("invite") Invite("invite"),
    @SerialName("link") Link("link");
}

/**
 * deep-link into a hub (resolved client-side vs local cache, nearest-ancestor)
 */
@Serializable
data class Target (
    @SerialName("blockId")
    val blockID: String? = null,

    @SerialName("hubId")
    val hubID: String? = null,

    @SerialName("sectionId")
    val sectionID: String? = null
)

@Serializable
data class Hub (
    @SerialName("countdown_to")
    val countdownTo: String? = null,

    @SerialName("end_at")
    val endAt: String? = null,

    val id: String,
    val sections: List<WrapperSchema>? = null,

    @SerialName("start_at")
    val startAt: String? = null,

    val status: Status? = null,

    /**
     * [CONTENT/E2E-hole]
     */
    val title: String,

    /**
     * bounded template-catalog key (ADR 0004/0006):
     * vacation|starting-college|move|party-event|new-baby|medical|school-year — app-validated
     */
    val type: String,

    val version: Long? = null
)

@Serializable
data class WrapperSchema (
    val blocks: List<Block>? = null,
    val id: String,
    val ord: Long? = null,

    /**
     * [CONTENT/E2E-hole]
     */
    val title: String? = null,

    val version: Long? = null
)

@Serializable
enum class Status(val value: String) {
    @SerialName("active") Active("active"),
    @SerialName("archived") Archived("archived"),
    @SerialName("planning") Planning("planning");
}

/**
 * ADR 0014 reusable named place; family content (encrypted at rest, never live position)
 */
@Serializable
data class Place (
    val id: String,

    /**
     * category (drives the place icon in the UI; design alignment)
     */
    val kind: PlaceKind? = null,

    val label: String,
    val lat: Double,
    val lng: Double,

    @SerialName("radius_m")
    val radiusM: Long? = null,

    val version: Long? = null
)

/**
 * category (drives the place icon in the UI; design alignment)
 */
@Serializable
enum class PlaceKind(val value: String) {
    @SerialName("home") Home("home"),
    @SerialName("other") Other("other"),
    @SerialName("school") School("school"),
    @SerialName("store") Store("store");
}

/**
 * GET /families/{fid}/sync (03 §sync)
 */
@Serializable
data class SyncResponse (
    val changes: Changes,

    @SerialName("has_more")
    val hasMore: Boolean,

    @SerialName("next_cursor")
    val nextCursor: String? = null,

    val tombstones: List<Tombstone>
)

@Serializable
data class Changes (
    val blocks: List<Block>? = null,
    val cards: List<BriefingCard>? = null,
    val hubs: List<Hub>? = null,
    val places: List<Place>? = null,
    val sections: List<WrapperSchema>? = null
)

@Serializable
data class Tombstone (
    val id: String,
    val type: TombstoneType
)

@Serializable
enum class TombstoneType(val value: String) {
    @SerialName("block") Block("block"),
    @SerialName("card") Card("card"),
    @SerialName("hub") Hub("hub"),
    @SerialName("place") Place("place"),
    @SerialName("section") Section("section");
}
