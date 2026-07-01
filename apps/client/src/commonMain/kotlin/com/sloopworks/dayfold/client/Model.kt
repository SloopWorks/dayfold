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
  // ADR 0043 §2b — bounded author weight/hint fed to the on-device Priority & Ordering Engine.
  // The device decides final position (no author ordinal); the engine CLAMPS it (no top-pin spam).
  val importance: Double? = null,
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
  val media: CardMedia? = null,                              // ADR 0036 visual enrichment
)

// ADR 0036 — card visual enrichment (icon + accent on the kind chip + optional thumb).
@Serializable
data class CardMedia(
  val icon: String? = null,           // curated NAME → glyph via CuratedIcons
  val accentColor: String? = null,    // #RRGGBB decorative seed
  val thumbnailUrl: String? = null,
  val imageAlt: String? = null,
  val imageFit: String? = null,       // cover | contain
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
  val attachments: List<EmailAttachment>? = null, val labels: List<String>? = null,
)

@Serializable
data class EmailAttachment(val name: String? = null, val mime: String? = null, val size: Long? = null)

// honesty chip (ADR 0014/0015) — stored verbatim; the client asserts nothing.
@Serializable
data class CardPrivacy(val storage: String? = null)

@Serializable data class Changes(val cards: List<Card> = emptyList(), val hubs: List<Hub> = emptyList(), val sections: List<HubSection> = emptyList(), val blocks: List<HubBlock> = emptyList(), val places: List<Place> = emptyList())
@Serializable data class Tombstone(val type: String, val id: String)

@Serializable
data class SyncResponse(
  val changes: Changes = Changes(),
  val tombstones: List<Tombstone> = emptyList(),
  @SerialName("next_cursor") val nextCursor: String? = null,
  @SerialName("has_more") val hasMore: Boolean = false,
  // ADR 0040 §3 — stale-cursor directive: the caller's cursor was older than the tombstone-
  // retention floor, so the server reset the scan to -∞. The client must WIPE its synced cache
  // (preserving the outbox + local hidden set) before applying, then rebuild from this page.
  @SerialName("full_resync") val fullResync: Boolean = false,
)

// ── Hubs (ADR 0006 render · ADR 0030 visibility) ─────────────────────────────
// Wire DTOs for the hub content API. GET /families/:fid/hubs returns a BARE ARRAY
// of hub rows; GET /families/:fid/hubs/:id/tree returns {hub, sections, blocks}.
// Field names are the DB-shaped snake_case the server serves (like the /sync rows).
@Serializable
data class Hub(
  val id: String,
  val type: String? = null,                                 // template key: vacation|party-event|medical|…
  val title: String,
  val status: String = "active",                            // planning | active | archived
  @SerialName("start_at") val startAt: String? = null,
  @SerialName("end_at") val endAt: String? = null,
  @SerialName("countdown_to") val countdownTo: String? = null,
  val visibility: String = "family",                        // family | restricted (ADR 0030)
  @SerialName("created_by") val createdBy: String? = null,  // resolved author user id (null = legacy token)
  val media: HubMedia? = null,                              // ADR 0036 visual enrichment (null = unenriched)
  val timeline: Timeline? = null,                           // ADR 0045 — authored stop list (camelCase wire, no @SerialName needed)
)

// ADR 0036 — hub visual enrichment. URLs are https + host-allowlisted (validated
// server-side + by MediaValidation before any Coil load); icon ∈ curated set;
// accentColor is a decorative SEED (harmonized by AccentRoles), never body text.
@Serializable
data class HubMedia(
  val heroUrl: String? = null,
  val thumbnailUrl: String? = null,
  val heroFit: String? = null,        // cover | contain
  val imageAlt: String? = null,
  val icon: String? = null,           // curated NAME → glyph via CuratedIcons
  val accentColor: String? = null,    // #RRGGBB decorative seed
)

@Serializable
data class HubSection(
  val id: String,
  @SerialName("hub_id") val hubId: String? = null,
  val title: String? = null,
  val ord: Long = 0,
)

// ADR 0045 — Hub timeline: authored stop list. Wire is camelCase → no @SerialName needed.
// Attachment is a flat bag: exactly one of tel/query/url/ref is set per kind (call/nav/link/open).
@Serializable
data class Timeline(
  val title: String? = null,
  val tz: String,
  val stops: List<Stop> = emptyList(),
)

@Serializable
data class Stop(
  val at: String,
  val title: String,
  val sub: String? = null,
  val major: Boolean = false,
  val done: Boolean = false,
  val assignee: String? = null,
  val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class Attachment(
  val kind: String,
  val label: String,
  val tel: String? = null,
  val query: String? = null,
  val url: String? = null,
  val ref: AttachmentRef? = null,
)

@Serializable
data class AttachmentRef(
  val hubId: String,
  val sectionId: String? = null,
  val blockId: String? = null,
)

// ADR 0038/0039 — one egress-outbox row as the sender loop reads it. Not serialized
// (a local DB projection, never on the wire).
data class OutboxOp(
  val opId: String,
  val targetKind: String,
  val targetId: String,
  val type: String,
  val payload: String,
  val baseVersion: Long?,
  val attempts: Long,
)

@Serializable
data class HubBlock(
  val id: String,
  @SerialName("section_id") val sectionId: String? = null,
  val type: String,                                          // text|markdown|link|checklist|document|milestone|contact|location|budget
  @SerialName("body_md") val bodyMd: String? = null,        // text/markdown content
  val payload: BlockPayload? = null,                         // typed fields for the structured block kinds
  val provenance: Provenance? = null,
  val ord: Long = 0,
  // ADR 0038 §W4 — set-once author id (server-stamped, 5a). Drives the author-only delete
  // gate: the delete option is absent unless createdBy == the signed-in user. null = legacy
  // (pre-stamp) block / loop-authored with no member author.
  @SerialName("created_by") val createdBy: String? = null,
  val version: Long = 1,                                     // ADR 0038 — server row version (If-Match base)
  // ADR 0014/0043 Phase A — on-device trigger metadata (geo/when/activity). Decoded from /sync
  // (schema/DB-present, client-absent until now); matched ON-DEVICE by deriveNow against the live
  // clock/location — live position never leaves the device (ADR 0014). Dropped on the wire before.
  val triggers: List<BlockTrigger>? = null,
  // ADR 0038 — client-only optimistic-write state ('pending'/'failed'/null=synced). Not on the
  // wire; @Transient so it never (de)serializes — it's projected from the local hub_block row.
  @kotlinx.serialization.Transient val localState: String? = null,
)

// ADR 0014/0043 — a single on-device trigger on a hub block. Exactly one of geo/when/activity is
// set per element (the server stores them verbatim). Matched locally by deriveNow; never evaluated
// server-side. `when` is a Kotlin hard keyword → the field is `whenTrigger`, wire name "when".
@Serializable
data class BlockTrigger(
  val geo: TriggerGeo? = null,
  @SerialName("when") val whenTrigger: TriggerWhen? = null,
  val activity: TriggerActivity? = null,
)

@Serializable
data class TriggerGeo(
  val label: String? = null,
  val lat: Double? = null,
  val lng: Double? = null,
  // geo-by-reference: resolves against the synced `places` set (ADR 0014 named place); falls back
  // to inline lat/lng. Radius precedence: geo.radiusM → place.radiusM → DeriveConfig default.
  @SerialName("place_ref") val placeRef: String? = null,
  @SerialName("radius_m") val radiusM: Long? = null,
)

@Serializable
data class TriggerWhen(
  val at: String? = null,                                    // absolute instant/date the window fires at
  val relative: String? = null,
  val recurring: String? = null,
  @SerialName("alert_offset") val alertOffset: String? = null,
)

@Serializable
data class TriggerActivity(val kind: String? = null)        // biking|driving|running|walking (ADR 0014; Phase B)

// ADR 0014 reusable named place (family content; encrypted at rest at M1, never live position).
// Synced via Changes.places + a "place" tombstone. The deriver reads these for geo-proximity and to
// resolve a trigger's place_ref. Server-served (schema-present); client cached it for the first time.
@Serializable
data class Place(
  val id: String,
  val kind: String? = null,                                  // home | school | store | other
  val label: String,
  val lat: Double,
  val lng: Double,
  @SerialName("radius_m") val radiusM: Long? = null,
  val version: Long? = null,
)

// Flat, lenient block payload — the server stores each block type's fields directly
// in `payload` (not externally tagged). All nullable; the renderer reads what the
// block.type needs and degrades gracefully (ignoreUnknownKeys keeps it forward-safe).
@Serializable
data class BlockPayload(
  val items: List<ChecklistItem>? = null,                   // checklist / budget rows
  val url: String? = null, val label: String? = null, val domain: String? = null, val docRef: String? = null,  // link / document
  val ref: String? = null,                                  // document — canonical schema name (ADR 0035; alias of docRef)
  val name: String? = null, val role: String? = null, val phone: String? = null, val email: String? = null,    // contact
  val address: String? = null, val lat: Double? = null, val lng: Double? = null, val mapUrl: String? = null,    // location (mapUrl = schema name)
  val date: String? = null,                                 // milestone
  val total: Double? = null, val spent: Double? = null,     // budget summary (client) — or derived from `items` (schema)
  // ADR 0036 block media: link/document preview thumbnail, contact avatar + accent.
  val thumbnailUrl: String? = null, val thumbnailAlt: String? = null,
  val avatarUrl: String? = null, val accentColor: String? = null,
)

@Serializable
data class ChecklistItem(
  val id: String? = null,                                    // ADR 0038 — stable per-item id (the LWW merge key)
  val text: String? = null, val done: Boolean = false,
  val doneBy: String? = null,                                // ADR 0038 — who toggled (the "✓ Dad" byline + LWW tiebreak)
  val doneAt: String? = null,                                // ADR 0038 — wall-clock stamp for `done` (the LWW clock)
  val ord: Long? = null,                                     // ADR 0038 — order; loop-authoritative at M0
  val due: String? = null, val assignee: String? = null,
  // budget rows (canonical schema shape): an itemized budget uses these (ADR 0035).
  val label: String? = null, val amount: Double? = null, val paid: Boolean? = null,
)

// ADR 0043 Phase A — the deriveNow candidate set, projected from the local cache (one bridge, one
// writer). The store only holds rows the member may read (sync applied ADR 0030 visibility), so
// derived items inherit visibility for free. Hubs ride the existing state.hubs slice.
data class NowContent(
  val sections: List<HubSection> = emptyList(),
  val blocks: List<HubBlock> = emptyList(),
  val places: List<Place> = emptyList(),
)

// GET /hubs/:id/tree envelope. Blocks carry section_id; the renderer groups them.
@Serializable
data class HubTree(
  val hub: Hub,
  val sections: List<HubSection> = emptyList(),
  val blocks: List<HubBlock> = emptyList(),
)

// GET /hubs/:id/audience — "who can see this hub" (ADR 0030). The full active
// roster; `permitted` = family-visible OR author OR allow-listed.
@Serializable
data class HubAudienceMember(
  val uid: String,
  @SerialName("display_name") val displayName: String? = null,
  val role: String = "adult",
  val permitted: Boolean = false,
)

@Serializable
data class HubAudience(
  val visibility: String = "family",
  val members: List<HubAudienceMember> = emptyList(),
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
enum class Route { Loading, SignIn, AuthError, CreateFamily, Feed, Hubs, Account, JoinInvite, Members, Devices, EnterCode, AuthorizeDevice, ScanPrimer, ScanDevice, ScanDenied, Proximity }

// AUTH-S6-D: a pending device/CLI grant the owner is being asked to approve
// (GET /device/pending). No device_code / user_id / credential — only what the
// approve screen renders. originKind ∈ datacenter|residential|unknown (the
// no-vendor CIDR classifier) drives the anti-phishing warning (ADR 0011 §7).
@Serializable
data class PendingDevice(
  @SerialName("user_code") val userCode: String,
  val client: String? = null,
  @SerialName("origin_ip") val originIp: String? = null,
  @SerialName("origin_ua") val originUa: String? = null,
  @SerialName("origin_kind") val originKind: String? = null,
  @SerialName("created_at") val createdAt: String? = null,
  @SerialName("expires_at") val expiresAt: String? = null,
)

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
  // CLI/device approval (S6-D). The pending grant being reviewed + a busy flag +
  // an inline error (lookup transient/lockout). deviceOutcome is the terminal
  // screen the AuthorizeDevice route renders: null | "denied" | "expired" | "approved".
  val pendingDevice: PendingDevice? = null,
  val deviceBusy: Boolean = false,
  val deviceError: String? = null,
  val deviceOutcome: String? = null,
  // Deep-link (Phase 2): a user_code captured from an App/Universal Link before
  // the owner was signed in. Stashed here, then consumed + looked up once sign-in
  // resolves memberships (cold-install resume). Null = nothing pending.
  val pendingDeviceLink: String? = null,
  // True between consuming a stashed deep-link and the lookup resolving — the
  // Loading route then shows the "Finishing…" beat instead of the plain splash.
  val deviceResuming: Boolean = false,
  // Hubs surface (ADR 0006 render). The list + the open hub's tree. currentHubId
  // null = list, set = detail (a Hubs substate, like detailStack is for Feed).
  val hubs: List<Hub> = emptyList(),
  val hubsBusy: Boolean = false,
  val hubError: String? = null,
  val hubFilter: String = "all",                              // all | active | planning (list filter chips)
  val currentHubId: String? = null,
  val currentHubTree: HubTree? = null,
  val hubFocusBlockId: String? = null,                        // deep-link arrival: the block to highlight
  val timelineDetail: TimelineScale? = null,                  // ADR 0045 — non-null = timeline detail overlay open at this scale
  // W5 hide (ADR 0038 §W5) — DB-fed set of locally-hidden entity ids (bridge writes it);
  // showHidden is the per-view "Show hidden" toggle (reset on open/close hub). Hide is
  // local-only + personal — never synced, no family-visible signal.
  val hiddenIds: Set<String> = emptySet(),
  val showHidden: Boolean = false,
  // ADR 0043 Phase A — the derived-lane candidate inputs + LOCAL-ONLY engine state. Both are
  // DB-fed projections (sole-writer bridges, like hiddenIds); the nowFeed selector runs
  // deriveNow + rank over them at render time with an injected clock + location.
  val nowContent: NowContent = NowContent(),
  val surfacing: Map<String, SurfacingRecord> = emptyMap(),
  // ADR 0044 Phase B — device-local, NEVER-synced. notifConfig is DB-fed (sole-writer bridge, like
  // surfacing); the permission slices are OS-owned (bridged from the platform controllers + re-read on
  // resume, NOT DB-cached, NOT synced — ADR 0024). Default-off / denied (opt-in, ADR 0044 §1).
  val notifConfig: NotifConfig = NotifConfig(),
  val locationPermission: LocationPermission = LocationPermission.Denied,
  val notificationPermission: NotificationPermission = NotificationPermission.Denied,
  // "Who can see this hub" sheet (ADR 0030). audienceSheetOpen drives the overlay;
  // currentHubAudience null while loading.
  val audienceSheetOpen: Boolean = false,
  val currentHubAudience: HubAudience? = null,
  // loading-state additions (2026-06-28)
  val pendingProvider: String? = null,   // which sign-in provider button spins
  val signOutBusy: Boolean = false,
  val rosterBusy: Boolean = false,
  val rosterError: String? = null,
  val memberOpId: String? = null,        // member row currently approving/declining/removing
  val deviceListBusy: Boolean = false,
  val deviceListError: String? = null,
  val deviceOpId: String? = null,        // device row currently revoking
  val audienceError: String? = null,
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
data object Back : Action                                     // system back → up one level (resolved by backAction)

// Hubs (ADR 0006). All I/O lives in HubEngine (suspend, mutex-guarded); the reducer
// is pure. OpenHubs/OpenFeed flip the bottom-nav surface; OpenHub/CloseHub drive the
// list↔detail substate (currentHubId).
data object OpenHubs : Action                                 // bottom nav → Hubs (list)
data object OpenFeed : Action                                 // bottom nav → Feed
data class HubsLoaded(val hubs: List<Hub>) : Action
data class HubsFailed(val message: String) : Action
data class OpenHub(val hubId: String) : Action                // list → detail (loads the tree)
data class HubTreeLoaded(val tree: HubTree) : Action
data object HubNotFound : Action                              // 404 (restricted/absent) — back to list with a note
data object CloseHub : Action                                 // detail → list
data class SetHubFocus(val blockId: String?) : Action         // deep-link arrival: highlight a block
data class SetHubFilter(val filter: String) : Action          // list filter chips (all|active|planning)
// W5 hide (ADR 0038 §W5). HiddenLoaded is the DB→store bridge (sole writer of hiddenIds);
// SetShowHidden flips the per-view "Show hidden" toggle. Hide/unhide effects run in HubEngine.
data class HiddenLoaded(val ids: Set<String>) : Action
data class SetShowHidden(val show: Boolean) : Action
// ADR 0043 Phase A — DB→store bridges (sole writers of their slice). NowContentLoaded carries the
// derived-lane candidate inputs; SurfacingLoaded carries the local-only engine state.
data class NowContentLoaded(val content: NowContent) : Action
data class SurfacingLoaded(val records: Map<String, SurfacingRecord>) : Action
// ADR 0044 Phase B — device-local, never-synced bridges. NotifConfigLoaded = DB→store (sole writer of
// state.notifConfig). The permission *Loaded actions are bridged from the OS-owned platform controllers
// (NOT the DB), re-read on resume — OS permission is OS truth, never cached or synced (ADR 0024).
data class NotifConfigLoaded(val config: NotifConfig) : Action
data class LocationPermissionLoaded(val state: LocationPermission) : Action
data class NotificationPermissionLoaded(val state: NotificationPermission) : Action
data object OpenAudienceSheet : Action                        // visibility chip tap → sheet (busy, loads)
data class HubAudienceLoaded(val audience: HubAudience) : Action
data object CloseAudienceSheet : Action
// ADR 0045 — timeline detail overlay (substate within the hub detail, not a Route)
data class OpenTimelineDetail(val scale: TimelineScale) : Action
data object CloseTimelineDetail : Action

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
// Restore-path terminal outcomes — Loading must never be terminal (else the
// splash spinner wedges forever). SessionExpired = the saved session is dead
// (401 + refresh couldn't recover: revoked/expired) → clear + SignIn. RestoreFailed
// = transient (network/5xx) → an AuthError screen with Retry (session kept).
data object SessionExpired : Action
data class RestoreFailed(val message: String) : Action
data object OpenAccount : Action                           // Feed → Account (signed-in overlay)
data object CloseAccount : Action                          // Account → back to the route gate (Feed)
data object OpenProximity : Action                         // Account → Background-proximity settings (ADR 0044)
data object CloseProximity : Action                        // Proximity → back to Account
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
data class MemberOpRequested(val uid: String) : Action   // approve/decline/remove start
data object RosterRequested : Action
data class RosterFailed(val message: String) : Action
data class DeviceOpRequested(val id: String) : Action    // revoke start
data object DevicesRequested : Action
data class DevicesFailed(val message: String) : Action
data class AudienceFailed(val message: String) : Action
// CLI/device approval (S6-D). The engine drives the *Requested/*Loaded/*Failed
// effect-trigger pattern (like approvals/join); the reducer stays pure.
data object OpenEnterCode : Action                             // → EnterCode (clears the device fields)
data object DeviceLookupRequested : Action                    // engine: lookup start (busy)
data class DevicePendingLoaded(val device: PendingDevice) : Action  // 200 → AuthorizeDevice
data object DeviceLookupNotFound : Action                     // 404 → AuthorizeDevice + outcome "expired"
data class DeviceLookupFailed(val message: String) : Action   // transient/429 → stay on EnterCode, inline error
data object ApproveDeviceRequested : Action                   // engine: approve start (busy)
data object DenyDeviceRequested : Action                      // engine: deny start (busy)
data object DeviceApproved : Action                           // 204 → outcome "approved"
data object DeviceDenied : Action                             // 204 (or gone) → outcome "denied"
data object DeviceApproveExpired : Action                     // approve 404 race → outcome "expired"
data class DeviceOpFailed(val message: String) : Action       // approve/deny transient/403/429 → inline error
data object CloseDeviceFlow : Action                          // exit → routeFor(session, families)
// Deep-link (Phase 2): stash a user_code from a link tapped before sign-in;
// consume it once memberships resolve (engine then looks it up → AuthorizeDevice).
data class DeviceLinkStashed(val code: String) : Action
data object DeviceLinkConsumed : Action
// Scan flow (Phase 2): the camera path into the same lookup → approve loop.
data object OpenScan : Action                                 // EnterCode Scan tab → ScanPrimer
data object ScanPermissionGranted : Action                    // primer Allow (granted) → ScanDevice
data object ScanPermissionDenied : Action                     // permission refused → ScanDenied
