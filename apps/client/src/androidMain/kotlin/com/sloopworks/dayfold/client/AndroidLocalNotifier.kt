package com.sloopworks.dayfold.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

// ADR 0044 Phase B — the Android LOCAL notifier (LocalNotifier impl). NotificationCompat only — NO
// FCM/APNs (dumb-server invariant): the headless pass posts these on-device. Fidelity per
// designs/triggers/Notifications: BigText body, group + group-summary digest, a deep-link "Open" action,
// and the honest on-device subtext ("Matched on your device" / "Added by Claude") that rides into the
// notification. Tap → the launcher activity with the deep-link extras (cold-start route hydrates OpenHub).
class AndroidLocalNotifier(private val context: Context) : LocalNotifier {
  private val manager = NotificationManagerCompat.from(context)

  override fun ensureChannel() {
    // IMPORTANCE_DEFAULT — present but calm (no full-screen, no sound escalation). Idempotent.
    val channel = NotificationChannel(CHANNEL_ID, "Dayfold reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
      description = "Few, timely place and time reminders — matched on your device."
      setShowBadge(false)   // no badge/count (calm posture, ADR 0044 §1)
    }
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
  }

  override fun postGroup(specs: List<NotificationSpec>) {
    if (specs.isEmpty()) return
    ensureChannel()
    specs.forEach { spec -> safeNotify(notifId(spec.subjectKey), build(spec)) }
    // a grouped digest summary when more than one fires (designs §2 grouping).
    if (specs.size > 1) safeNotify(GROUP_SUMMARY_ID, buildSummary(specs))
  }

  override fun cancel(subjectKey: String) = manager.cancel(notifId(subjectKey))
  override fun cancelAll() = manager.cancelAll()

  private fun build(spec: NotificationSpec): Notification =
    NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)   // TODO branded monochrome status icon
      .setContentTitle(spec.title)
      .setContentText(spec.body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
      .setSubText(spec.subtext)                          // the on-device honesty line
      .setGroup(spec.group)
      .setAutoCancel(true)
      .setPriority(if (spec.urgent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
      .setContentIntent(deepLinkIntent(spec))
      .addAction(0, "Open", deepLinkIntent(spec))
      .build()

  // The grouped summary card (Android collapses the children beneath it).
  private fun buildSummary(specs: List<NotificationSpec>): Notification {
    val inbox = NotificationCompat.InboxStyle().setSummaryText("Matched on your device")
    specs.forEach { inbox.addLine(it.title) }
    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Dayfold")
      .setContentText("${specs.size} reminders")
      .setStyle(inbox)
      .setSubText("Matched on your device")
      .setGroup(specs.first().group)
      .setGroupSummary(true)
      .setAutoCancel(true)
      .build()
  }

  // Tap → relaunch the app's launcher activity with the deep-link extras. No compile dependency on
  // MainActivity (resolved by package); the cold-start route reads these extras → CardAction.OpenHub.
  private fun deepLinkIntent(spec: NotificationSpec): PendingIntent {
    val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?: Intent(Intent.ACTION_MAIN)).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      spec.target?.let {
        putExtra(EXTRA_HUB_ID, it.hubId)
        putExtra(EXTRA_BLOCK_ID, it.blockId)
      }
      putExtra(EXTRA_SUBJECT_KEY, spec.subjectKey)
    }
    return PendingIntent.getActivity(
      context, notifId(spec.subjectKey), intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  private fun safeNotify(id: Int, notification: Notification) {
    // POST_NOTIFICATIONS (API 33+) is a runtime grant; if it's not held, notify() is a silent no-op
    // rather than a crash (the permission ladder requests it; we never override a denial).
    if (manager.areNotificationsEnabled()) runCatching { manager.notify(id, notification) }
  }

  private fun notifId(subjectKey: String): Int = subjectKey.hashCode()

  companion object {
    const val CHANNEL_ID = "dayfold.now.reminders"
    const val GROUP_SUMMARY_ID = -1
    const val EXTRA_HUB_ID = "dayfold.hubId"
    const val EXTRA_BLOCK_ID = "dayfold.blockId"
    const val EXTRA_SUBJECT_KEY = "dayfold.subjectKey"
  }
}
