package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.reduxkotlin.Store

/**
 * ADR 0064 — runtime-owned response effects (ADR 0058). The reducer stays pure; every effect
 * — the optimistic DB write, the outbox enqueue, ULID minting, clock reads, undo — lives here.
 *
 * Every response is an OPTIMISTIC write in the States-P1 vocabulary: the row lands locally at
 * once and the receipt is honest about timing. A rule cannot stop a run that already happened,
 * so the offline copy says "takes effect next run" rather than implying instant effect.
 */
class ResponseEngine(
  private val store: Store<AppState>,
  private val contentStore: ContentStore,
  private val syncEngine: SyncEngine,
  private val isOnline: () -> Boolean,
  private val nowProvider: () -> String = { kotlin.time.Clock.System.now().toString() },
  private val idProvider: () -> String = { Ulid.next() },
  private val databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

  private fun viewerUserId(): String? = store.state.session.session?.userId

  /**
   * "Don't add this again". [audience] defaults to personal at every call site — family-wide
   * is only ever reached through the sheet's explicit second choice, never the swipe path.
   */
  suspend fun mute(
    subjectRef: String,
    matchScope: MatchScope,
    audience: AudienceScope,
    label: String,
    sublabel: String? = null,
  ) {
    val viewer = viewerUserId() ?: return
    write(
      ContentResponse(
        id = idProvider(),
        kind = ResponseKind.MUTE,
        subjectRef = subjectRef,
        matchScope = matchScope,
        audienceScope = audience,
        userId = if (audience == AudienceScope.PERSONAL) viewer else null,
        createdBy = viewer,
        label = label,
        sublabel = sublabel,
        note = null,
        pending = true,
      ),
      onlineMessage = "Muted",
      offlineMessage = "Muted — will sync when you're online",
    )
  }

  /**
   * "Mark done" — completion, not dismissal. Always family-wide on a concrete subject, mirroring
   * the server's shape check: a resolved task should leave every member's Now, not just the Now
   * of whoever happened to tap it.
   */
  suspend fun markDone(subjectRef: String, label: String, note: String? = null) {
    val viewer = viewerUserId() ?: return
    write(
      ContentResponse(
        id = idProvider(),
        kind = ResponseKind.DONE,
        subjectRef = subjectRef,
        matchScope = MatchScope.SUBJECT,
        audienceScope = AudienceScope.FAMILY,
        userId = null,
        createdBy = viewer,
        label = label,
        sublabel = null,
        note = note,
        pending = true,
      ),
      onlineMessage = "Marked done",
      offlineMessage = "Marked done — will sync when you're online",
    )
  }

  private suspend fun write(row: ContentResponse, onlineMessage: String, offlineMessage: String) {
    val nowIso = nowProvider()
    val offline = !isOnline()
    withContext(databaseDispatcher) {
      contentStore.upsertResponseLocal(row)
      contentStore.enqueueResponseOp(
        opId = row.id, id = row.id, type = "upsert",
        payload = responseWireJson(row), nowIso = nowIso,
      )
      reload()
    }
    store.dispatch(
      ResponseReceiptShown(
        ResponseReceipt(
          responseId = row.id,
          message = if (offline) offlineMessage else onlineMessage,
          undoable = true,
          offline = offline,
        ),
      ),
    )
    syncEngine.requestSync(SyncReason.OUTBOX_MUTATION)
  }

  /** Remove a rule from Settings. Optimistic, like every other response write. */
  suspend fun removeResponse(id: String) {
    val nowIso = nowProvider()
    withContext(databaseDispatcher) {
      contentStore.deleteResponseLocal(id)
      contentStore.enqueueResponseOp(
        opId = idProvider(), id = id, type = "delete", payload = "", nowIso = nowIso,
      )
      reload()
    }
    syncEngine.requestSync(SyncReason.OUTBOX_MUTATION)
  }

  /**
   * Undo the last response. Works offline: the queued write has not left the device, so it is
   * simply dropped — no compensating request, nothing for the server to reconcile.
   */
  suspend fun undoLastResponse() {
    val id = store.state.responses.lastReceipt?.responseId ?: return
    withContext(databaseDispatcher) {
      contentStore.dropQueuedOpsFor(id)
      contentStore.deleteResponseLocal(id)
      reload()
    }
    store.dispatch(ResponseReceiptDismissed)
  }

  /**
   * Record that this subject has been offered the swipe escalation. Tier-0, never synced —
   * one offer per subject, ever, because a repeat offer is a nag.
   */
  suspend fun recordResponseOffer(subjectRef: String) {
    val nowIso = nowProvider()
    withContext(databaseDispatcher) { contentStore.recordResponseOffer(subjectRef, nowIso) }
  }

  suspend fun wasResponseOffered(subjectRef: String): Boolean =
    withContext(databaseDispatcher) { contentStore.wasResponseOffered(subjectRef) }

  /** DB→store bridge. ResponsesLoaded is the sole writer of state.responses.rules. */
  fun reload() {
    store.dispatch(ResponsesLoaded(contentStore.allResponses()))
  }
}

/**
 * The wire body for a response PUT. Hand-built rather than serialized from [ContentResponse]
 * because the wire is snake_case with string enums and carries no `pending` — that flag is
 * local UI state and must never be sent.
 */
internal fun responseWireJson(r: ContentResponse): String = buildString {
  append('{')
  append("\"kind\":\"").append(r.kind.wire).append("\",")
  append("\"subject_ref\":").append(jsonString(r.subjectRef)).append(',')
  append("\"match_scope\":\"").append(r.matchScope.wire).append("\",")
  append("\"audience_scope\":\"").append(r.audienceScope.wire).append("\",")
  append("\"label\":").append(jsonString(r.label))
  if (r.sublabel != null) append(",\"sublabel\":").append(jsonString(r.sublabel))
  if (r.note != null) append(",\"note\":").append(jsonString(r.note))
  append('}')
}

/** Minimal JSON string escaping — labels and notes are user text and WILL contain quotes. */
private fun jsonString(s: String): String = buildString {
  append('"')
  for (ch in s) when (ch) {
    '"' -> append("\\\"")
    '\\' -> append("\\\\")
    '\n' -> append("\\n")
    '\r' -> append("\\r")
    '\t' -> append("\\t")
    else -> if (ch < ' ') append("\\u").append(ch.code.toString(16).padStart(4, '0')) else append(ch)
  }
  append('"')
}
