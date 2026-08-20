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
    mute(viewer, subjectRef, matchScope, audience, label, sublabel)
  }

  internal suspend fun mute(
    context: FamilySessionContext,
    subjectRef: String,
    matchScope: MatchScope,
    audience: AudienceScope,
    label: String,
    sublabel: String? = null,
  ) {
    val viewer = context.actorUserId() ?: return
    mute(viewer, subjectRef, matchScope, audience, label, sublabel)
  }

  private suspend fun mute(
    viewer: String,
    subjectRef: String,
    matchScope: MatchScope,
    audience: AudienceScope,
    label: String,
    sublabel: String?,
  ) {
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
      undoable = true,
    )
  }

  /**
   * "Mark done" — completion, not dismissal. Always family-wide on a concrete subject, mirroring
   * the server's shape check: a resolved task should leave every member's Now, not just the Now
   * of whoever happened to tap it.
   */
  suspend fun markDone(subjectRef: String, label: String, note: String? = null) {
    val viewer = viewerUserId() ?: return
    markDone(viewer, subjectRef, label, note)
  }

  internal suspend fun markDone(
    context: FamilySessionContext,
    subjectRef: String,
    label: String,
    note: String? = null,
  ) {
    val viewer = context.actorUserId() ?: return
    markDone(viewer, subjectRef, label, note)
  }

  private suspend fun markDone(viewer: String, subjectRef: String, label: String, note: String?) {
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
      undoable = false,
    )
  }

  private suspend fun write(
    row: ContentResponse,
    onlineMessage: String,
    offlineMessage: String,
    undoable: Boolean,
  ) {
    val nowIso = nowProvider()
    val persisted = if (row.createdAt.isBlank()) row.copy(createdAt = nowIso) else row
    val offline = !isOnline()
    withContext(databaseDispatcher) {
      contentStore.upsertResponseLocal(persisted)
      contentStore.enqueueResponseOp(
        opId = persisted.id, id = persisted.id, type = "upsert",
        payload = responseWireJson(persisted), nowIso = nowIso,
      )
      reload()
    }
    store.dispatch(
      ResponseReceiptShown(
        ResponseReceipt(
          responseId = persisted.id,
          message = if (offline) offlineMessage else onlineMessage,
          undoable = undoable,
          offline = offline,
        ),
      ),
    )
    syncEngine.requestSync(SyncReason.OUTBOX_MUTATION)
  }

  /** Remove a rule from Settings. Optimistic, like every other response write. */
  suspend fun removeResponse(id: String) {
    removeResponseInternal(id)
  }

  internal suspend fun removeResponse(context: FamilySessionContext, id: String) {
    if (context.actorUserId() == null) return
    removeResponseInternal(id)
  }

  private suspend fun removeResponseInternal(id: String) {
    val nowIso = nowProvider()
    val enqueued = withContext(databaseDispatcher) {
      val queued = contentStore.enqueueResponseDelete(idProvider(), id, nowIso)
      reload()
      queued
    }
    if (!enqueued) return
    syncEngine.requestSync(SyncReason.OUTBOX_MUTATION)
  }

  /**
   * Undo the last response by appending a compensating delete. The original PUT may already be
   * in-flight or acknowledged, so preserving it and ordering the DELETE is the only convergent path.
   */
  suspend fun undoLastResponse() {
    undoLastResponseInternal()
  }

  internal suspend fun undoLastResponse(context: FamilySessionContext) {
    if (context.actorUserId() == null) return
    undoLastResponseInternal()
  }

  private suspend fun undoLastResponseInternal() {
    val id = store.state.responses.lastReceipt?.responseId ?: return
    val nowIso = nowProvider()
    withContext(databaseDispatcher) {
      contentStore.enqueueResponseDelete(idProvider(), id, nowIso)
      reload()
    }
    store.dispatch(ResponseReceiptDismissed)
    syncEngine.requestSync(SyncReason.OUTBOX_MUTATION)
  }

  /**
   * Record that this subject has been offered the swipe escalation. Tier-0, never synced —
   * one offer per subject, ever, because a repeat offer is a nag.
   */
  suspend fun recordResponseOffer(subjectRef: String) {
    recordResponseOfferInternal(subjectRef)
  }

  internal suspend fun recordResponseOffer(context: FamilySessionContext, subjectRef: String) {
    if (context.actorUserId() == null) return
    recordResponseOfferInternal(subjectRef)
  }

  private suspend fun recordResponseOfferInternal(subjectRef: String) {
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
