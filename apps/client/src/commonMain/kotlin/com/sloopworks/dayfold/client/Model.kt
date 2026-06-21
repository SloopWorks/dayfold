package com.sloopworks.dayfold.client

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
  // CL-8 related-edges. `related_kicker` arrives snake from the DB-shaped /sync row
  // (like target_*/hub_ref). `related` jsonb decodes verbatim (edge keys are camel).
  val related: List<RelatedRef>? = null,
  @SerialName("related_kicker") val relatedKicker: String? = null,
)

// A cross-link to another card in THIS family (CL-8). targetId resolves
// client-side vs the local cache for navigation; title/sub are author-
// denormalized so the row renders without resolving. targetType kept String
// (forward-compat, parity with Card.type) though codegen `Related` uses an enum.
@Serializable
data class RelatedRef(
  val relation: String,
  val targetId: String,
  val targetType: String? = null,
  val title: String? = null,
  val sub: String? = null,
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

// ── AUTH-S5: client identity + session (ADR 0011/0021/0023) ──────────────────
// A backend-minted session (ADR 0011: we mint our own tokens, NOT Firebase's).
// access = short EdDSA JWT (5m); refresh = opaque rotating (45d). userId is the
// `sub`, surfaced for display only — never trusted for authz (re-resolved server
// -side per request).
@Serializable
data class Session(val access: String, val refresh: String, val userId: String? = null)

// One row of the caller's M:N membership (from GET /auth/whoami → families[]).
// status: "active" (approved) | "pending" (owner approval outstanding).
@Serializable
data class FamilyMembership(
  @SerialName("family_id") val familyId: String,
  val name: String = "",
  val role: String = "adult",          // owner | adult (teen 14+ deferred, ADR 0005)
  val status: String = "active",       // active | pending
)

// The app's first navigation surface (ADR 0013: f(state)→UI, no nav library).
// Family-null is a Feed SUBSTATE (the active family has no members yet), not a
// route — keeps the gate minimal.
enum class Route { Loading, SignIn, CreateFamily, Feed, Account, JoinInvite, Members, Devices }

// Redux state (client state tree). The feed cursor lives in the DB (sync_meta),
// not here — the store is a projection of the DB. The auth fields below are the
// only client-held session state; the access token is attached per request and
// re-validated server-side (never trusted locally for authz).
data class AppState(
  // feed surface
  val cards: List<Card> = emptyList(),
  val syncing: Boolean = false,
  val error: String? = null,
  // CL-6 nav: a STACK of card ids (top = current detail, empty = feed). A stack
  // so related-edges (CL-8) chain detail→detail. Nav is app state (ADR 0013), not
  // a side channel. Not persisted at M0 → cold start returns to feed (restoring
  // an open detail across process death is not an M0 requirement).
  val detailStack: List<String> = emptyList(),
  // auth / session (S5)
  val session: Session? = null,
  val families: List<FamilyMembership> = emptyList(),
  val activeFamilyId: String? = null,
  val route: Route = Route.Loading,
  val authBusy: Boolean = false,
  val authError: String? = null,
  // invitee-join (S5 slice-2). outcome: null | waiting | expired | locked |
  // already | removed | error — the join screen renders the matching A8b state.
  val joinBusy: Boolean = false,
  val joinOutcome: String? = null,
  val joinFamilyName: String? = null,
  // owner-side approvals (S6). The pending-member queue + a busy flag.
  val pendingApprovals: List<PendingMember> = emptyList(),
  val approvalsBusy: Boolean = false,
  // the active member roster (GET /members).
  val members: List<FamilyMember> = emptyList(),
  // connected devices/apps — the caller's credentials (GET /auth/me/credentials).
  val devices: List<DeviceCredential> = emptyList(),
)

// Actions. Card data reaches the store ONLY via CardsLoaded (the DB→store bridge);
// SyncStarted/SyncSucceeded/SyncFailed carry sync STATUS only.
sealed interface Action
data object SyncStarted : Action
data object SyncSucceeded : Action
data class SyncFailed(val message: String) : Action
data class CardsLoaded(val cards: List<Card>) : Action
// CL-6 nav (push if not already top / pop one level).
data class NavToDetail(val cardId: String) : Action
data object NavBack : Action

// Auth actions (S5). All I/O lives in AuthEngine (suspend, mutex-guarded like
// SyncEngine); the reducer is pure and derives `route`/`activeFamilyId` from
// (session, families) via routeFor()/activeFamilyIdFor().
data object AuthRestoring : Action                          // cold-start: reading the token store
data class SessionRestored(val session: Session?) : Action // null → SignIn; non-null → Loading (whoami next)
data class SignInRequested(val provider: String) : Action  // "google" | "apple" (dev build → dev-token)
data class SignInSucceeded(val session: Session) : Action  // → Loading until MembershipsLoaded
data class SignInFailed(val message: String) : Action
data class SessionRotated(val session: Session) : Action    // refresh swapped the tokens; no nav change
data class MembershipsLoaded(val families: List<FamilyMembership>) : Action // → Feed | CreateFamily
data class CreateFamilyRequested(val name: String) : Action
data class FamilyCreated(val familyId: String, val name: String) : Action   // → Feed (owner, active)
data class AuthOpFailed(val message: String) : Action
data object OpenAccount : Action                           // Feed → Account (signed-in overlay)
data object CloseAccount : Action                          // Account → back to the route gate (Feed)
data object SignOutRequested : Action
data object SignedOut : Action                             // clears session + feed → SignIn
// invitee-join (S5 slice-2). RedeemRequested is an effect trigger (AuthEngine);
// InviteRedeemed/Rejected carry the outcome the join screen renders.
data object OpenJoinInvite : Action                           // CreateFamily → the paste-invite screen
data class RedeemRequested(val token: String) : Action
data class InviteRedeemed(val familyName: String?) : Action   // success → pending, waiting for approval
data class InviteRejected(val reason: String) : Action        // expired | locked | already | removed | error
data object JoinDismissed : Action                            // leave the join flow → back to the gate
// owner-side approvals (S6). Load the queue; resolve removes one (approved/declined).
data object OpenMembers : Action                              // → the family members/approvals screen
data class RosterLoaded(val members: List<FamilyMember>) : Action  // active member roster (GET /members)
data class MemberRemoved(val uid: String) : Action            // owner removed a member → drop from roster
data object OpenDevices : Action                              // → the connected-devices screen
data class DevicesLoaded(val devices: List<DeviceCredential>) : Action  // connected devices/apps
data class DeviceRevoked(val id: String) : Action             // revoked a credential → drop from the list
data object ApprovalsRequested : Action
data class ApprovalsLoaded(val pending: List<PendingMember>) : Action
data class MemberResolved(val uid: String) : Action           // approved or declined → drop from the queue
data object ApprovalsFailed : Action
