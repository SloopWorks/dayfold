package com.sloopworks.dayfold.client

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ktor.client.HttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers

// ADR 0020 R3 — Android glue. All decision logic is in commonMain (backgroundRefreshPass);
// this is a thin OS bridge, mirroring AndroidBackgroundNotify.

const val REFRESH_WORK_NAME = "dayfold.refresh"

// 30 minutes, not the 15-minute floor: the OS runs periodic work inside a flex window subject
// to Doze and standby buckets, so a shorter request buys jitter, not freshness. NEVER treat
// this as a guaranteed cadence (spec A.6).
private const val REFRESH_INTERVAL_MINUTES = 30L
private const val REFRESH_FLEX_MINUTES = 10L

class RefreshWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val outcome = runBackgroundRefresh(applicationContext)
    Log.i("refresh") { "background pass: $outcome" }
    // A budget overrun is not a failure — the cursor resumes next wake. Returning retry()
    // here would burn standby-bucket quota on a problem waiting solves.
    return Result.success()
  }
}

/**
 * Enqueue the periodic refresh. KEEP so repeated calls (app start, BOOT_COMPLETED) are free
 * rather than duplicative — WorkManager already persists its own work across reboots, so the
 * boot re-enqueue is belt-and-braces. Switch to UPDATE only if the interval/constraints change.
 */
fun ensurePeriodicRefresh(context: Context) {
  val request = PeriodicWorkRequestBuilder<RefreshWorker>(
    REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES,
    REFRESH_FLEX_MINUTES, TimeUnit.MINUTES,
  )
    // CONNECTED + BatteryNotLow ONLY. RequiresCharging/DeviceIdle would starve it: an unmet
    // constraint can skip a run entirely, not merely delay it.
    .setConstraints(
      Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build(),
    )
    .build()

  WorkManager.getInstance(context.applicationContext)
    .enqueueUniquePeriodicWork(REFRESH_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
}

/**
 * Wire the commonMain pass to Android's store + notify glue. Budget is generous relative to
 * iOS because WorkManager allows ~10 minutes; the cap exists to bound a hung network call.
 *
 * [AndroidRuntimeHandleHolder.get] supplies `delegateToRuntime`: when a live runtime is retained
 * in this process, backgroundRefreshPass hands the pass to it and never builds its own
 * SyncClient/AuthClient below — see that holder's doc for why a second refresher is unsafe.
 *
 * Builds and closes its own [HttpClient] for the lifetime of this one wake. The worker has no
 * WorkerFactory-injected graph to borrow a client from (no Configuration.Provider — see the
 * dependency comment in androidApp/build.gradle.kts), and never closing it would leak an OkHttp
 * engine every ~30 minutes for as long as the process stays alive.
 */
internal suspend fun runBackgroundRefresh(context: Context): RefreshOutcome {
  val cs = AndroidContentStoreHolder.get(context)
  val http = HttpClient()
  return try {
    backgroundRefreshPass(
      deps = RefreshDeps(
        memberships = { cs.cachedMemberships() },
        session = { AndroidTokenStore(context).load() },
        delegateToRuntime = AndroidRuntimeHandleHolder.get(),
        syncOnce = { familyId, session -> androidHeadlessSync(context, cs, http, familyId, session) },
        reconcile = { reRegisterGeofences(context) },
      ),
      budget = 60.seconds,
    )
  } finally {
    http.close()
  }
}

/**
 * Supplies the platform pieces to the shared headless drain ([headlessSync], which reuses
 * [SyncDrainer] — no second paging loop). [databaseDispatcher] matches DayfoldRuntimeFactory's own
 * default (Dispatchers.Default is never overridden by the foreground graph either).
 */
private suspend fun androidHeadlessSync(
  context: Context,
  contentStore: ContentStore,
  http: HttpClient,
  familyId: String,
  session: Session,
) {
  val api = requireNotNull(AndroidApiConfigHolder.apiBase) {
    "AndroidApiConfigHolder.apiBase must be set (DayfoldApp.onCreate) before RefreshWorker runs"
  }
  headlessSync(
    contentStore = contentStore,
    syncClient = SyncClient(api, http),
    databaseDispatcher = Dispatchers.Default,
    familyId = familyId,
    session = session,
    refreshAccess = { refresh -> androidRefreshAccess(context, api, http, refresh) },
    nowIso = { kotlin.time.Clock.System.now().toString() },
  )
}

/**
 * The one refresh attempt [headlessSync] is allowed on a 401. MUST persist the rotated session
 * before returning: `POST /auth/refresh` has already rotated the lineage server-side by the time
 * this call returns (AuthClient.kt:134), so if the new refresh token is not written back to
 * [AndroidTokenStore], the NEXT wake (or the next foreground sign-in) would present the
 * now-superseded token and trip reuse detection — the exact sign-out this delegation scheme
 * exists to avoid. A thrown [AuthHttpException] here (e.g. the lineage really was revoked) is
 * swallowed to null on purpose: headlessSync then re-throws the ORIGINAL 401 rather than this
 * refresh's own error, and does not retry.
 */
private suspend fun androidRefreshAccess(context: Context, api: String, http: HttpClient, refresh: String): Session? =
  runCatching { AuthClient(api, http).refresh(refresh) }
    .onSuccess { AndroidTokenStore(context).save(it) }
    .getOrNull()
