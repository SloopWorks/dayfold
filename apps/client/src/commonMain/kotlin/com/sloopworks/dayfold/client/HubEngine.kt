package com.sloopworks.dayfold.client

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.reduxkotlin.Store

// Orchestrates the Hubs surface (ADR 0006). Mirrors AuthEngine: mutex-guarded
// suspend I/O, exceptions → actions, and the 401 refresh-and-retry loop (the access
// token is short-lived). The reducer stays pure. The family id + access come from
// the store per call (the providers pattern) so they're never stale.
class HubEngine(
  private val store: Store<AppState>,
  private val hubClient: HubClient,
  private val authClient: AuthClient,
  private val tokenStore: TokenStore,
) {
  private val mutex = Mutex()

  private fun fid(): String? = store.state.activeFamilyId
  private fun session(): Session? = store.state.session

  suspend fun loadHubs() = mutex.withLock {
    val fid = fid(); val s = session()
    if (fid == null || s == null) return@withLock                 // idle until onboarded
    store.dispatch(HubsBusyStarted)
    try {
      val hubs = callWithRefresh(s) { hubClient.familyHubs(it.access, fid) }
      store.dispatch(HubsLoaded(hubs))
    } catch (e: Exception) {
      store.dispatch(HubsFailed(e.message ?: "Couldn't load hubs"))
    }
  }

  suspend fun openHub(hubId: String) = mutex.withLock {
    val fid = fid(); val s = session()
    store.dispatch(OpenHub(hubId))                                 // list → detail (busy)
    if (fid == null || s == null) return@withLock
    try {
      when (val res = callWithRefresh(s) { hubClient.hubTree(it.access, fid, hubId) }) {
        is HubTreeResult.Loaded -> store.dispatch(HubTreeLoaded(res.tree))
        HubTreeResult.NotFound -> store.dispatch(HubNotFound)      // restricted/absent (omit-don't-403)
      }
    } catch (e: Exception) {
      store.dispatch(HubsFailed(e.message ?: "Couldn't load hub"))
    }
  }

  // Refresh-and-retry on 401 (mirrors AuthEngine.callWithRefresh): the access token
  // is 5m, so a single rotate + persist + dispatch + retry recovers transparently.
  private suspend fun <T> callWithRefresh(session: Session, block: suspend (Session) -> T): T =
    try {
      block(session)
    } catch (e: AuthHttpException) {
      if (e.status != 401) throw e
      val rotated = authClient.refresh(session.refresh)
      tokenStore.save(rotated)
      store.dispatch(SessionRotated(rotated))
      block(rotated)
    }
}
