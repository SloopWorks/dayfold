package com.sloopworks.dayfold.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.sloopworks.dayfold.client.AndroidContentStoreHolder
import com.sloopworks.dayfold.client.Card
import com.sloopworks.dayfold.client.NotifConfig
import com.sloopworks.dayfold.client.Provenance
import com.sloopworks.dayfold.client.runBackgroundNotificationPass
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0044 Phase B · S4 — the headless background PASS end-to-end on a real Android runtime. Seeds the
 * process-shared store, enables the (device-local) config, then runs runBackgroundNotificationPass — the
 * exact code path the geofence / exact-alarm receivers invoke — and asserts a LOCAL notification fires.
 * Proves the whole chain on-device: shared store → notifSnapshot → nowFeed + selectNotifications →
 * AndroidLocalNotifier (no engine fork, no FCM/APNs).
 */
class AndroidBackgroundPassTest {
  private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private fun grantPostNotifications() {
    if (Build.VERSION.SDK_INT >= 33) {
      InstrumentationRegistry.getInstrumentation().uiAutomation
        .grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  @Test fun background_pass_posts_a_local_notification_from_the_shared_store() {
    grantPostNotifications()
    val cs = AndroidContentStoreHolder.get(ctx)
    cs.wipe()   // clean slate (the holder DB persists across runs)

    val now = kotlin.time.Clock.System.now().toString()
    cs.applyDelta(
      changedCards = listOf(Card(id = "bgpass", title = "Soccer at 4pm — pack jackets", bodyMd = "Rain at kickoff", notBefore = now, provenance = Provenance("claude"))),
      changedHubs = emptyList(), tombstones = emptyList(), nextCursor = null, nowIso = now,
    )
    // quietStart == quietEnd → no quiet window (time-of-day independent); cap fresh.
    cs.setNotifConfig(NotifConfig(enabled = true, quietStartMinuteOfDay = 0, quietEndMinuteOfDay = 0, dailyCap = 3))

    val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.cancelAll()

    val plan = runBackgroundNotificationPass(ctx, null)
    assertTrue("expected the card planned to post; got ${plan.toPost.map { it.subjectKey }}", plan.toPost.isNotEmpty())

    val active = mgr.activeNotifications
    assertTrue(
      "expected a posted notification on-device; got ${active.map { it.notification.extras.getString("android.title") }}",
      active.any { it.notification.extras.getString("android.title") == "Soccer at 4pm — pack jackets" },
    )

    // the post was logged → a second pass dedups it (no re-nag).
    val plan2 = runBackgroundNotificationPass(ctx, null)
    assertTrue("expected dedup on the second pass", plan2.toPost.isEmpty())

    mgr.cancelAll()
    cs.wipe()
  }
}
