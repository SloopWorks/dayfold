package com.sloopworks.dayfold.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.sloopworks.dayfold.client.AndroidLocalNotifier
import com.sloopworks.dayfold.client.DeepLinkTarget
import com.sloopworks.dayfold.client.NotificationSpec
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0044 Phase B · S3/S4 — on-emulator proof that the Android LocalNotifier actually posts a LOCAL
 * notification (NotificationCompat; no FCM/APNs). Grants POST_NOTIFICATIONS, posts a spec, and asserts
 * it lands in the system's active set with the honest on-device subtext + deep-link target.
 */
class AndroidLocalNotifierTest {
  private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private fun grantPostNotifications() {
    if (Build.VERSION.SDK_INT >= 33) {
      InstrumentationRegistry.getInstrumentation().uiAutomation
        .grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  @Test fun posts_a_local_notification_with_deep_link_and_on_device_subtext() {
    grantPostNotifications()
    val notifier = AndroidLocalNotifier(ctx)
    notifier.cancelAll()

    val spec = NotificationSpec(
      subjectKey = "hub:demo", title = "Near Lincoln Market — party list?",
      body = "7 items still open. Grab them while you're here.",
      subtext = "Matched on your device",
      target = DeepLinkTarget("hub-demo", blockId = "blk-demo"), urgent = true,
    )
    notifier.ensureChannel()
    notifier.postGroup(listOf(spec))

    val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val active = mgr.activeNotifications
    assertTrue(
      "expected the posted notification in the active set; got ${active.map { it.notification.extras.getString("android.title") }}",
      active.any { it.notification.extras.getString("android.title") == spec.title },
    )

    notifier.cancelAll()
  }
}
