package com.sloopworks.dayfold.android

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.sloopworks.dayfold.client.AndroidGeofenceOperationQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidGeofenceOperationQueueTest {
  private fun completed(): Task<Void> = Tasks.forResult(null)

  @Test fun replacements_remove_then_add_without_interleaving() {
    val queue = AndroidGeofenceOperationQueue()
    val firstRemoval = TaskCompletionSource<Void>()
    val order = mutableListOf<String>()

    queue.enqueueReplace(
      remove = { order += "remove-a"; firstRemoval.task },
      add = { order += "add-a"; completed() },
    )
    val second = queue.enqueueReplace(
      remove = { order += "remove-b"; completed() },
      add = { order += "add-b"; completed() },
    )
    assertEquals(listOf("remove-a"), order)

    firstRemoval.setResult(null)
    Tasks.await(second, 5, TimeUnit.SECONDS)
    assertEquals(listOf("remove-a", "add-a", "remove-b", "add-b"), order)
  }

  @Test fun family_cleanup_cannot_race_a_late_registration_add() {
    val queue = AndroidGeofenceOperationQueue()
    val firstRemoval = TaskCompletionSource<Void>()
    val order = mutableListOf<String>()

    queue.enqueueReplace(
      remove = { order += "replace-remove"; firstRemoval.task },
      add = { order += "replace-add"; completed() },
    )
    val cleanup = queue.enqueueRemove { order += "family-remove"; completed() }

    firstRemoval.setResult(null)
    Tasks.await(cleanup, 5, TimeUnit.SECONDS)
    assertEquals(listOf("replace-remove", "family-remove"), order)
  }
}
