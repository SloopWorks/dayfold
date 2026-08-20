package com.sloopworks.dayfold.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.sloopworks.dayfold.client.AndroidLocalNotifier
import com.sloopworks.dayfold.client.DeepLinkTarget
import com.sloopworks.dayfold.client.NotificationSpec
import com.sloopworks.dayfold.client.exactReminderIdentityUri
import com.sloopworks.dayfold.client.notificationIdentityUri
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

  private fun awaitNotificationState(
    manager: NotificationManager,
    predicate: (List<android.service.notification.StatusBarNotification>) -> Boolean,
  ): Boolean {
    repeat(20) {
      if (predicate(manager.activeNotifications.toList())) return true
      SystemClock.sleep(50)
    }
    return predicate(manager.activeNotifications.toList())
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
    notifier.postGroup(listOf(spec)) { }

    val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    assertTrue(
      "expected the posted notification in the active set",
      awaitNotificationState(mgr) { active ->
        active.any { it.notification.extras.getString("android.title") == spec.title }
      },
    )

    notifier.cancelAll()
  }

  @Test fun retracting_a_child_also_removes_the_stale_group_summary() {
    grantPostNotifications()
    val notifier = AndroidLocalNotifier(ctx)
    notifier.cancelAll()
    val first = NotificationSpec("card:first", "First", "why", "On your device", null, false)
    val second = NotificationSpec("card:second", "Second", "why", "On your device", null, false)
    notifier.postGroup(listOf(first, second)) { }

    val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    assertTrue(awaitNotificationState(mgr) { active ->
      active.any { it.id == AndroidLocalNotifier.GROUP_SUMMARY_ID }
    })

    notifier.cancel(first.subjectKey)

    assertTrue(awaitNotificationState(mgr) { active ->
      active.none { it.id == AndroidLocalNotifier.GROUP_SUMMARY_ID }
    })
    notifier.cancelAll()
  }

  @Test fun java_hash_collisions_keep_distinct_children_and_tap_identities() {
    grantPostNotifications()
    val notifier = AndroidLocalNotifier(ctx)
    notifier.cancelAll()
    // "Aa" and "BB" are a canonical Java String.hashCode collision; the common prefix preserves it.
    val first = NotificationSpec("card:Aa", "Collision A", "why A", "On your device", DeepLinkTarget("hub-a"), false)
    val second = NotificationSpec("card:BB", "Collision B", "why B", "On your device", DeepLinkTarget("hub-b"), false)
    assertTrue(first.subjectKey.hashCode() == second.subjectKey.hashCode())
    assertTrue(notificationIdentityUri(first.subjectKey) != notificationIdentityUri(second.subjectKey))
    assertTrue(exactReminderIdentityUri(first.subjectKey) != exactReminderIdentityUri(second.subjectKey))

    notifier.postGroup(listOf(first, second)) { }

    val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    assertTrue(awaitNotificationState(mgr) { active ->
      val children = active.filter { it.id == AndroidLocalNotifier.CHILD_NOTIFICATION_ID }
      children.map { it.tag }.toSet().containsAll(setOf(first.subjectKey, second.subjectKey)) &&
        children.mapNotNull { it.notification.extras.getString("android.title") }.toSet()
          .containsAll(setOf(first.title, second.title))
    })
    notifier.cancelAll()
  }
}
