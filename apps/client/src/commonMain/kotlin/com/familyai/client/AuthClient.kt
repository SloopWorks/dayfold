package com.familyai.client

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// AUTH-S5 T2 — transport for the auth/onboarding endpoints (ADR 0011/0021/0023).
// Same posture as SyncClient: ktor in commonMain, no ContentNegotiation plugin —
// bodies are encoded/decoded explicitly with kotlinx-serialization. All I/O; no
// state. AuthEngine (T4) sequences these and dispatches actions.
//
// Firebase-stubbed at S5: sign-in goes through the gated dev-token endpoint
// (POST /auth/dev-token, local/test only — the server hard-refuses it in
// prod/preview, ADR 0021 §4). S2 swaps that one call for a Firebase ID-token
// verify behind the same Google/Apple buttons; the rest is unchanged.
// Non-2xx from an auth endpoint. `status` lets the engine branch — notably
// 401 → refresh-and-retry (the access token is short-lived, 5m).
class AuthHttpException(val status: Int, val endpoint: String) :
  Exception("$endpoint HTTP $status")

class AuthClient(
  private val api: String,
  private val http: HttpClient = HttpClient(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  @Serializable private data class DevTokenReq(val provider: String, @SerialName("provider_uid") val providerUid: String)
  @Serializable private data class CreateFamilyReq(val name: String)
  @Serializable private data class RefreshReq(val refresh: String)
  @Serializable private data class TokenResp(val access: String, val refresh: String)
  @Serializable private data class CreateFamilyResp(val familyId: String)
  @Serializable private data class RedeemReq(val token: String)
  @Serializable private data class RedeemResp(
    @SerialName("family_id") val familyId: String? = null,
    @SerialName("family_name") val familyName: String? = null,
    val role: String? = null,
  )
  @Serializable private data class ConflictResp(val type: String? = null)
  @Serializable private data class ApprovalsResp(val pending: List<PendingMember> = emptyList())

  /** POST /auth/dev-token (Bearer DEV_AUTH_SECRET) → a real backend session. Dev/test only. */
  suspend fun devToken(provider: String, providerUid: String, devSecret: String): Session {
    val resp = http.post("$api/auth/dev-token") {
      header("authorization", "Bearer $devSecret")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(DevTokenReq.serializer(), DevTokenReq(provider, providerUid)))
    }
    if (resp.status.value != 200) throw AuthHttpException(resp.status.value, "dev-token")
    val t = json.decodeFromString(TokenResp.serializer(), resp.bodyAsText())
    return Session(access = t.access, refresh = t.refresh)
  }

  /** GET /auth/whoami (Bearer access) → the caller's memberships. */
  suspend fun whoami(access: String): WhoamiResponse {
    val resp = http.get("$api/auth/whoami") { header("authorization", "Bearer $access") }
    if (resp.status.value != 200) throw AuthHttpException(resp.status.value, "whoami")
    return json.decodeFromString(WhoamiResponse.serializer(), resp.bodyAsText())
  }

  /** POST /families (Bearer access) → new family id (caller becomes owner). */
  suspend fun createFamily(access: String, name: String): String {
    val resp = http.post("$api/families") {
      header("authorization", "Bearer $access")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(CreateFamilyReq.serializer(), CreateFamilyReq(name)))
    }
    if (resp.status.value !in 200..201) throw AuthHttpException(resp.status.value, "create-family")
    return json.decodeFromString(CreateFamilyResp.serializer(), resp.bodyAsText()).familyId
  }

  /** POST /auth/refresh → rotated session (reuse-detection revokes the lineage server-side). */
  suspend fun refresh(refreshToken: String): Session {
    val resp = http.post("$api/auth/refresh") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RefreshReq.serializer(), RefreshReq(refreshToken)))
    }
    if (resp.status.value != 200) throw AuthHttpException(resp.status.value, "refresh")
    val t = json.decodeFromString(TokenResp.serializer(), resp.bodyAsText())
    return Session(access = t.access, refresh = t.refresh)
  }

  /** POST /auth/signout (Bearer access) — revokes the credential + all its refresh tokens. */
  suspend fun signout(access: String) {
    val resp = http.post("$api/auth/signout") { header("authorization", "Bearer $access") }
    if (resp.status.value !in 200..204) throw AuthHttpException(resp.status.value, "signout")
  }

  /**
   * POST /invites:redeem (Bearer access) — claim an invite token. Success creates
   * a PENDING membership (every invite is owner-approved, ADR 0011) → the invitee
   * waits. Maps the server's status codes to a [RedeemResult]; 401 / 5xx throw
   * AuthHttpException (→ the engine surfaces a transient "couldn't join" retry).
   */
  suspend fun redeemInvite(access: String, token: String): RedeemResult {
    val resp = http.post("$api/invites:redeem") {
      header("authorization", "Bearer $access")
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(RedeemReq.serializer(), RedeemReq(token)))
    }
    return when (resp.status.value) {
      200 -> json.decodeFromString(RedeemResp.serializer(), resp.bodyAsText())
        .let { RedeemResult.Pending(it.familyId, it.familyName, it.role) }
      404 -> RedeemResult.Expired                      // uniform: expired/revoked/exhausted/invalid
      429 -> RedeemResult.Locked                       // rate-limited or pending-cap full
      409 -> if (json.decodeFromString(ConflictResp.serializer(), resp.bodyAsText()).type == "already-member")
        RedeemResult.AlreadyMember else RedeemResult.Removed
      else -> throw AuthHttpException(resp.status.value, "invites:redeem")
    }
  }

  /** GET /families/{fid}/invites (owner-gated) → the pending-approval queue. */
  suspend fun familyApprovals(access: String, fid: String): List<PendingMember> {
    val resp = http.get("$api/families/$fid/invites") { header("authorization", "Bearer $access") }
    if (resp.status.value != 200) throw AuthHttpException(resp.status.value, "family-invites")
    return json.decodeFromString(ApprovalsResp.serializer(), resp.bodyAsText()).pending
  }

  /** Owner approves a pending member → their membership goes active. */
  suspend fun approveMember(access: String, fid: String, uid: String) = memberAction(access, fid, uid, "approve")

  /** Owner declines a pending member. */
  suspend fun declineMember(access: String, fid: String, uid: String) = memberAction(access, fid, uid, "decline")

  private suspend fun memberAction(access: String, fid: String, uid: String, action: String) {
    val resp = http.post("$api/families/$fid/members/$uid:$action") { header("authorization", "Bearer $access") }
    // 204 done · 200 already-active (idempotent) — both fine; 4xx/5xx surface.
    if (resp.status.value !in 200..204) throw AuthHttpException(resp.status.value, "member-$action")
  }
}

// A member awaiting the owner's approval (GET /families/{fid}/invites → pending[]).
@Serializable
data class PendingMember(
  val uid: String,
  @SerialName("display_name") val displayName: String? = null,
  val role: String = "adult",
  val provider: String? = null,
  @SerialName("requested_at") val requestedAt: String? = null,
)

// Outcome of redeeming an invite (server status → typed result). Pending =
// success: a membership awaiting owner approval (familyId/Name null when the
// invitee had already requested — the server returns {status:"pending"} only).
sealed interface RedeemResult {
  data class Pending(val familyId: String?, val familyName: String?, val role: String?) : RedeemResult
  data object Expired : RedeemResult        // 404 — uniform invalid/expired/revoked/exhausted
  data object Locked : RedeemResult         // 429 — too many attempts / pending-cap full
  data object AlreadyMember : RedeemResult  // 409 — already an active member
  data object Removed : RedeemResult        // 409 — previously removed from this family
}

// GET /auth/whoami response. family_id = the access token's scoped family (may be
// null pre-family); families = every membership (active + pending).
@Serializable
data class WhoamiResponse(
  @SerialName("family_id") val familyId: String? = null,
  val families: List<FamilyMembership> = emptyList(),
)
