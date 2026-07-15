package com.sloopworks.dayfold.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sloopworks.dayfold.client.AndroidLocalNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationIntentConsumptionTest {
  @Test fun notification_target_is_consumed_once_across_activity_recreation() {
    val intent = Intent()
      .putExtra(AndroidLocalNotifier.EXTRA_HUB_ID, "hub-1")
      .putExtra(AndroidLocalNotifier.EXTRA_BLOCK_ID, "block-1")

    val target = intent.consumeNotificationTarget()

    assertEquals("hub-1", target?.hubId)
    assertEquals("block-1", target?.blockId)
    assertNull(intent.consumeNotificationTarget())
    assertNull(intent.getStringExtra(AndroidLocalNotifier.EXTRA_HUB_ID))
    assertNull(intent.getStringExtra(AndroidLocalNotifier.EXTRA_BLOCK_ID))
  }
}
