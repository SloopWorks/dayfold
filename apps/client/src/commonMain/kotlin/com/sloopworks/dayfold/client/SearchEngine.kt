package com.sloopworks.dayfold.client

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Family-owned, query-private search coordinator.
 *
 * The engine publishes only query-free binding/corpus generations. Raw queries enter only the
 * stack of [search], and result text returns directly to the caller's non-saveable UI session.
 */
internal class SearchEngine(
  private val contentStore: ContentStore,
  private val sessionCoordinator: SessionCoordinator,
  private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val databaseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  private class FamilyOwner(
    val context: FamilySessionContext,
    val scope: CoroutineScope,
    val publication: PublicationBoundary,
  ) {
    var collector: Job? = null
  }

  private val gate = SynchronizedObject()
  private val bindingCounter = atomic(0L)
  private val corpusCounter = atomic(0L)
  private val requestCounter = atomic(0L)
  private val mutableStatus = MutableStateFlow(SearchStatus())

  private var owner: FamilyOwner? = null
  private var corpus: SearchCorpus? = null

  val status: StateFlow<SearchStatus> = mutableStatus.asStateFlow()

  /** Installs one collector in the runtime's replaceable family child. */
  internal fun bindFamilyWork(
    context: FamilySessionContext,
    ownerScope: CoroutineScope,
    publication: PublicationBoundary,
  ) {
    check(sessionCoordinator.isCurrent(context)) { "Cannot bind stale Search family work" }
    val next = FamilyOwner(context, ownerScope, publication)
    synchronized(gate) {
      check(owner == null) { "Close previous Search family admission before replacement" }
      owner = next
      corpus = null
      mutableStatus.value = SearchStatus(
        bindingGeneration = bindingCounter.incrementAndGet(),
        corpusGeneration = corpusCounter.incrementAndGet(),
        readiness = SearchReadiness.WAITING_FOR_SYNC,
      )
    }

    val collector = ownerScope.launch {
      val collectorJob = coroutineContext.job
      val checkpoint = SearchCheckpoint { collectorJob.ensureActive() }
      contentStore.searchContentFlow(databaseDispatcher).collectLatest { snapshot ->
        val built = withContext(backgroundDispatcher) { buildSearchCorpus(snapshot, checkpoint) }
        publishCorpus(next, built)
      }
    }
    synchronized(gate) {
      if (owner === next) next.collector = collector else collector.cancel()
    }
  }

  /** Synchronously rejects publication and drops all searchable/result-bearing state. */
  internal fun closeFamilyAdmission() {
    val detached = synchronized(gate) {
      val previous = owner
      owner = null
      corpus = null
      mutableStatus.value = SearchStatus(
        bindingGeneration = bindingCounter.incrementAndGet(),
        corpusGeneration = corpusCounter.incrementAndGet(),
        readiness = SearchReadiness.WAITING_FOR_SYNC,
      )
      previous
    }
    detached?.collector?.cancel()
  }

  /** Searches one immutable corpus and rejects any result that crossed a revision/family change. */
  suspend fun search(query: String): SearchResponse {
    val requestGeneration = requestCounter.incrementAndGet()
    val captured = synchronized(gate) {
      val currentOwner = owner
      val currentCorpus = corpus
      val currentStatus = mutableStatus.value
      if (currentOwner == null || currentCorpus == null) null
      else SearchCapture(currentOwner, currentCorpus, currentStatus)
    } ?: return unavailable(requestGeneration)

    val response = try {
      withContext(backgroundDispatcher) {
        val searchJob = coroutineContext.job
        searchCorpus(
          corpus = captured.corpus,
          query = query,
          requestGeneration = requestGeneration,
          bindingGeneration = captured.status.bindingGeneration,
          corpusGeneration = captured.status.corpusGeneration,
          checkpoint = SearchCheckpoint { searchJob.ensureActive() },
        )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      return unavailable(requestGeneration)
    }

    val stillCurrent = synchronized(gate) {
      owner === captured.owner &&
        corpus === captured.corpus &&
        mutableStatus.value == captured.status &&
        captured.owner.publication.isOpen
    } && sessionCoordinator.isCurrent(captured.owner.context) &&
      contentStore.searchRevision() == captured.corpus.sourceRevision
    return if (stillCurrent) response else unavailable(requestGeneration)
  }

  /** Tap-time existence/visibility check; stale handles deliberately do not navigate. */
  suspend fun resolve(handle: SearchResultHandle): ResolvedSearchResult? = withContext(databaseDispatcher) {
    val captured = synchronized(gate) {
      val currentOwner = owner
      val currentCorpus = corpus
      val currentStatus = mutableStatus.value
      if (
        currentOwner == null || currentCorpus == null ||
        handle.corpusGeneration != currentStatus.corpusGeneration ||
        !currentCorpus.contains(handle.destination)
      ) null else SearchCapture(currentOwner, currentCorpus, currentStatus)
    } ?: return@withContext null

    // Re-read one writer-consistent snapshot so card navigation can publish the canonical DB
    // projection before opening detail even when Search's collector won the startup race against
    // ContentBridge. This snapshot contains no query or match metadata.
    val snapshot = contentStore.searchSnapshot()
    val revisionIsCurrent = snapshot.revision == captured.corpus.sourceRevision
    val ownerIsCurrent = synchronized(gate) {
      owner === captured.owner && corpus === captured.corpus &&
        mutableStatus.value == captured.status && captured.owner.publication.isOpen
    }
    if (!revisionIsCurrent || !ownerIsCurrent || !sessionCoordinator.isCurrent(captured.owner.context)) {
      return@withContext null
    }
    val destinationExists = when (handle.destination.kind) {
      SearchDestinationKind.CARD -> snapshot.cards.any { it.id == handle.destination.cardId }
      SearchDestinationKind.HUB -> snapshot.hubs.any { it.id == handle.destination.hubId }
      SearchDestinationKind.SECTION -> snapshot.sections.any {
        it.id == handle.destination.sectionId && it.hubId == handle.destination.hubId
      }
      SearchDestinationKind.BLOCK -> snapshot.blocks.any { block ->
        block.id == handle.destination.blockId && block.sectionId == handle.destination.sectionId
      }
    }
    if (!destinationExists) return@withContext null
    ResolvedSearchResult(
      context = captured.owner.context,
      destination = handle.destination,
      cards = if (handle.destination.kind == SearchDestinationKind.CARD) snapshot.cards else emptyList(),
    )
  }

  private fun publishCorpus(expected: FamilyOwner, built: SearchCorpus) {
    expected.publication.publish {
      if (!sessionCoordinator.isCurrent(expected.context)) return@publish
      if (contentStore.searchRevision() != built.sourceRevision) return@publish
      synchronized(gate) {
        if (owner !== expected) return@synchronized
        corpus = built
        mutableStatus.value = SearchStatus(
          bindingGeneration = mutableStatus.value.bindingGeneration,
          corpusGeneration = corpusCounter.incrementAndGet(),
          readiness = built.readiness,
        )
      }
    }
  }

  private fun unavailable(requestGeneration: Long): SearchResponse {
    val current = mutableStatus.value
    return SearchResponse(
      requestGeneration = requestGeneration,
      bindingGeneration = current.bindingGeneration,
      corpusGeneration = current.corpusGeneration,
      readiness = current.readiness,
      failure = SearchFailure.UNAVAILABLE,
    )
  }

  private data class SearchCapture(
    val owner: FamilyOwner,
    val corpus: SearchCorpus,
    val status: SearchStatus,
  )
}

/** Internal admission result: opaque tenant capability plus query-free canonical content. */
internal data class ResolvedSearchResult(
  val context: FamilySessionContext,
  val destination: SearchDestination,
  val cards: List<Card>,
)
