package com.sloopworks.dayfold.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Retains the latest external Hub target until a current family runtime can admit it.
 *
 * The actor owns all mutable state, so notification callbacks may submit from any platform thread.
 * Targets stay outside Redux (and therefore DevTools/SWIP serialization), while the supplied family
 * generation is revalidated immediately before delivery. A successful delivery acknowledges only
 * after the navigation action has been admitted.
 */
internal class PendingExternalHubTargetCoordinator(
  scope: CoroutineScope,
  private val isCurrent: (FamilySessionContext) -> Boolean,
  private val deliver: suspend (
    family: FamilySessionContext,
    target: DeepLinkTarget,
    onAdmitted: () -> Unit,
  ) -> Boolean,
) {
  private class Submission(
    val target: DeepLinkTarget,
    val onAdmitted: () -> Unit,
    val onDiscarded: () -> Unit,
  )

  private sealed interface Event {
    class Submit(val submission: Submission) : Event
    class FamilyBound(
      val family: FamilySessionContext,
      val completed: CompletableDeferred<Unit>,
    ) : Event
    class Clear(val completed: CompletableDeferred<Unit>) : Event
    class Barrier(val completed: CompletableDeferred<Unit>) : Event
  }

  private val events = Channel<Event>(Channel.UNLIMITED)

  private val processor = scope.launch(start = CoroutineStart.UNDISPATCHED) {
    var boundFamily: FamilySessionContext? = null
    var pending: Submission? = null
    var retainWhileUnbound = true

    fun discard(submission: Submission?) {
      if (submission == null) return
      try {
        submission.onDiscarded()
      } catch (error: Exception) {
        Log.e("runtime", error) { "external Hub discard acknowledgement failed" }
      }
    }

    suspend fun deliverPending() {
      val submission = pending ?: return
      val family = boundFamily ?: return
      if (!isCurrent(family)) {
        boundFamily = null
        discard(pending)
        pending = null
        retainWhileUnbound = false
        return
      }

      val admitted = try {
        deliver(family, submission.target, submission.onAdmitted)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        Log.e("runtime", error) { "external Hub target delivery failed" }
        false
      }
      if (admitted) {
        pending = null
      } else {
        // Admission can close between the generation check and HubEngine's commit. This target
        // was already associated with the old bound tenant, so it must not cross into the next
        // family merely because its payload has no family id.
        boundFamily = null
        discard(pending)
        pending = null
        retainWhileUnbound = false
      }
    }

    for (event in events) {
      when (event) {
        is Event.Submit -> {
          // Before a family is usable, newest intent wins deterministically. Once usable, the
          // actor processes each warm submission to completion before receiving the next one.
          if (boundFamily != null || retainWhileUnbound) {
            discard(pending)
            pending = event.submission
            deliverPending()
          } else {
            discard(event.submission)
          }
        }
        is Event.FamilyBound -> {
          val previous = boundFamily
          if (
            previous != null &&
            (
              previous.authContext.identityEpoch != event.family.authContext.identityEpoch ||
                previous.familyId != event.family.familyId ||
                previous.familyRevision != event.family.familyRevision
            )
          ) {
            discard(pending)
            pending = null
          }
          boundFamily = event.family
          retainWhileUnbound = true
          deliverPending()
          event.completed.complete(Unit)
        }
        is Event.Clear -> {
          boundFamily = null
          discard(pending)
          pending = null
          // A running graph that crossed a terminal identity boundary must not retain taps from
          // old notifications while signed out. A newly constructed graph starts permissive so
          // its cold-start tap can wait for restore to determine whether an identity exists.
          retainWhileUnbound = false
          event.completed.complete(Unit)
        }
        is Event.Barrier -> event.completed.complete(Unit)
      }
    }
  }

  init {
    processor.invokeOnCompletion { events.cancel() }
  }

  /** Submits an immutable target from a platform callback without blocking that callback thread. */
  fun submit(
    target: DeepLinkTarget,
    onDiscarded: () -> Unit = {},
    onAdmitted: () -> Unit = {},
  ) {
    val submission = Submission(target, onAdmitted, onDiscarded)
    if (events.trySend(Event.Submit(submission)).isFailure) {
      try {
        onDiscarded()
      } catch (error: Exception) {
        Log.e("runtime", error) { "external Hub discard acknowledgement failed" }
      }
    }
  }

  /** Publishes a family only after its runtime work and Hub admission have been installed. */
  suspend fun familyBound(family: FamilySessionContext) {
    val completed = CompletableDeferred<Unit>()
    if (events.trySend(Event.FamilyBound(family, completed)).isFailure) return
    select {
      completed.onAwait { }
      processor.onJoin { }
    }
  }

  /** Clears identity-bound delivery state and rejects unbound taps until another family binds. */
  suspend fun clear() {
    val completed = CompletableDeferred<Unit>()
    if (events.trySend(Event.Clear(completed)).isFailure) return
    select {
      completed.onAwait { }
      processor.onJoin { }
    }
  }

  /** Deterministic test barrier; production behavior never polls or sleeps. */
  internal suspend fun awaitIdle() {
    val completed = CompletableDeferred<Unit>()
    if (events.trySend(Event.Barrier(completed)).isFailure) return
    select {
      completed.onAwait { }
      processor.onJoin { }
    }
  }
}
