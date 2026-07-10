package com.sloopworks.dayfold.client.fake

import com.sloopworks.dayfold.client.Changes
import com.sloopworks.dayfold.client.DeviceCredential
import com.sloopworks.dayfold.client.FamilyMember
import com.sloopworks.dayfold.client.Hub
import com.sloopworks.dayfold.client.HubAudience
import com.sloopworks.dayfold.client.HubAudienceMember
import com.sloopworks.dayfold.client.HubTree
import com.sloopworks.dayfold.client.MeProfile
import com.sloopworks.dayfold.client.PendingDevice
import com.sloopworks.dayfold.client.PendingMember
import com.sloopworks.dayfold.client.Session
import com.sloopworks.dayfold.client.SyncResponse
import com.sloopworks.dayfold.client.WhoamiResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// ── Fake backend (debug-only) ────────────────────────────────────────────────
// A pure, platform-agnostic router that answers the dayfold API surface from a
// canned [FakeBackendData] scenario. No ktor here (so it ships nowhere
// release-sensitive and is unit-testable in commonTest like SampleData): the thin
// MockEngine adapter that turns this into an HttpClient lives in the debug/desktop
// shells (androidApp/src/debug, client/desktopMain).
//
// It serializes the SAME @Serializable wire models the real server returns, so
// snake/camel field names (next_cursor, family_id, hub_ref, …) come out correct by
// construction — there is no hand-written JSON to drift.
//
// CRITICAL (verified against SyncEngine/HubEngine):
//  • /sync MUST return has_more=false → the drain loop terminates.
//  • hub DETAIL is fed from /sync changes (sections+blocks), NOT /tree — so each
//    scenario's hubs/sections/blocks all travel in `sync.changes`. /tree is served
//    too (for tests/parity) by deriving it from the same changes.
//  • Never 401 on an authed route (it would trigger a refresh round-trip); model
//    errors with other statuses instead (e.g. sync→500).

/** One canned response: an HTTP status + a JSON body (empty for 204). */
data class FakeResponse(val status: Int, val json: String)

/** A named, selectable fake backend. */
data class FakeScenario(val id: String, val label: String, val data: FakeBackendData)

/**
 * The canned state one scenario serves. All domain objects (so the router just
 * serializes them). `sync.changes` carries cards + hubs + sections + blocks.
 */
data class FakeBackendData(
  val session: Session = Session("fake-access", "fake-refresh", "fake-user"),
  val whoami: WhoamiResponse,
  val sync: SyncResponse,
  val members: List<FamilyMember> = emptyList(),
  val approvals: List<PendingMember> = emptyList(),
  val devices: List<DeviceCredential> = emptyList(),
  val pendingDevice: PendingDevice? = null,
  val audiences: Map<String, HubAudience> = emptyMap(),
  val newFamilyId: String = "fam_fake_new",
  /** GET/PATCH /auth/me — the caller's own profile (task 4). Both routes serve this
   *  same canned value (the fake router is stateless — a PATCH doesn't persist). */
  val me: MeProfile = MeProfile(userId = "fake-user", displayName = "Pat", avatarColor = "teal", avatarRef = "avatar:fox-01"),
  /** Override the /sync status to model an error path (e.g. 500). 200 = serve `sync`. */
  val syncStatus: Int = 200,
  /** Artificial per-request delay (debug-only) so loading states are observable. */
  val latencyMs: Long = 0,
)

// Local envelope DTOs mirroring the server shapes the client decodes (the client's
// own copies are private). Field names match AuthClient's private *Resp types.
@Serializable private data class TokenOut(val access: String, val refresh: String)
@Serializable private data class CreateFamilyOut(val familyId: String)
@Serializable private data class MembersOut(val members: List<FamilyMember>)
@Serializable private data class InvitesOut(val pending: List<PendingMember>)
@Serializable private data class CredsOut(val credentials: List<DeviceCredential>)
@Serializable private data class MeOut(
  val user_id: String, val display_name: String?, val avatar_color: String?, val avatar_ref: String?,
)

class FakeBackend(
  val data: FakeBackendData,
  private val json: Json = Json { encodeDefaults = false; explicitNulls = false },
) {
  /**
   * Route one request. [path] is the URL's encoded path (no host/query); [userCode]
   * is the `user_code` query param (only the device-pending route reads a query).
   * Unknown routes → 404. The host the client used is irrelevant (MockEngine routes
   * by path), so scenarios match any family id.
   */
  fun handle(method: String, path: String, userCode: String?): FakeResponse {
    val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
    fun <T> ok(ser: kotlinx.serialization.KSerializer<T>, value: T) =
      FakeResponse(200, json.encodeToString(ser, value))
    val noContent = FakeResponse(204, "")

    // ── auth / session ──
    when (path) {
      "/auth/dev-token", "/auth/firebase", "/auth/refresh" ->
        return ok(TokenOut.serializer(), TokenOut(data.session.access, data.session.refresh))
      "/auth/signout" -> return noContent
      "/auth/whoami" -> return ok(WhoamiResponse.serializer(), data.whoami)
      // GET/PATCH share one canned profile (stateless router — a PATCH doesn't persist).
      "/auth/me" -> return ok(MeOut.serializer(), MeOut(data.me.userId, data.me.displayName, data.me.avatarColor, data.me.avatarRef))
      "/auth/me/credentials" -> return ok(CredsOut.serializer(), CredsOut(data.devices))
      "/device/pending" ->
        return data.pendingDevice?.let { ok(PendingDevice.serializer(), it) }
          ?: FakeResponse(404, "")
      "/families" -> return ok(CreateFamilyOut.serializer(), CreateFamilyOut(data.newFamilyId))
      "/invites:redeem" -> return FakeResponse(404, "")   // invitee flow not modelled (uniform expired)
    }
    // DELETE /auth/me/credentials/{id}
    if (segs.size == 4 && segs[0] == "auth" && segs[1] == "me" && segs[2] == "credentials") return noContent

    // ── family-scoped ──
    if (segs.size >= 2 && segs[0] == "families") {
      val tail = segs.drop(2)   // after families/{fid}
      when {
        tail == listOf("sync") ->
          return if (data.syncStatus != 200) FakeResponse(data.syncStatus, "")
          else ok(SyncResponse.serializer(), data.sync)
        tail == listOf("hubs") ->
          return ok(ListSerializer(Hub.serializer()), data.sync.changes.hubs)
        tail.size == 3 && tail[0] == "hubs" && tail[2] == "tree" ->
          return treeFor(tail[1])?.let { ok(HubTree.serializer(), it) } ?: FakeResponse(404, "")
        tail.size == 3 && tail[0] == "hubs" && tail[2] == "audience" ->
          return ok(HubAudience.serializer(), audienceFor(tail[1]))
        // PUT/DELETE …/hubs/{id}/participants/{uid} (ADR 0053 DC2/DC4) — the fake
        // router is stateless (like /auth/me PATCH), so these just ack; the
        // subsequent audience reload re-serves audienceFor()'s canned roster. PUT
        // acks 200 (the real route returns the upserted row json — HubClient only
        // checks status, so an empty object is enough); DELETE acks 204 like the API.
        tail.size == 4 && tail[0] == "hubs" && tail[2] == "participants" && method == "PUT" -> return FakeResponse(200, "{}")
        tail.size == 4 && tail[0] == "hubs" && tail[2] == "participants" && method == "DELETE" -> return FakeResponse(204, "")
        // PUT …/hubs/{id}/visibility (ADR 0053 DC2/DC4) — same stateless 200 ack.
        tail.size == 3 && tail[0] == "hubs" && tail[2] == "visibility" && method == "PUT" -> return FakeResponse(200, "{}")
        tail == listOf("members") && method == "GET" ->
          return ok(MembersOut.serializer(), MembersOut(data.members))
        tail == listOf("invites") ->
          return ok(InvitesOut.serializer(), InvitesOut(data.approvals))
        // member approve/decline (POST …/members/{uid}:action) + remove (DELETE …/members/{uid})
        tail.size == 2 && tail[0] == "members" -> return noContent
        // device approve/deny (POST …/device/{action})
        tail.size == 2 && tail[0] == "device" -> return noContent
      }
    }
    return FakeResponse(404, "")
  }

  // Derive a hub's tree from the same changes that feed the DB (parity with /sync).
  private fun treeFor(hubId: String): HubTree? {
    val hub = data.sync.changes.hubs.firstOrNull { it.id == hubId } ?: return null
    val sections = data.sync.changes.sections.filter { it.hubId == hubId }
    val sectionIds = sections.map { it.id }.toSet()
    val blocks = data.sync.changes.blocks.filter { it.sectionId in sectionIds }
    return HubTree(hub = hub, sections = sections, blocks = blocks)
  }

  // Scenario-supplied audience, else a permissive default (everyone sees it, and the
  // caller can manage it — matches the "everyone permitted" posture of this default).
  // participationRole stays null (no explicit allow-list row) in the default; a
  // scenario that needs ADR 0053 role chips/gating supplies data.audiences[hubId]
  // directly with real participation_role/can_manage values.
  private fun audienceFor(hubId: String): HubAudience =
    data.audiences[hubId] ?: HubAudience(
      visibility = "family",
      members = data.members.map {
        HubAudienceMember(
          uid = it.uid, displayName = it.displayName, avatarColor = it.avatarColor,
          avatarRef = it.avatarRef, role = it.role, permitted = true, participationRole = null,
        )
      },
      canManage = true,
    )
}
