package com.sloopworks.dayfold.client

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosDeepLinkReplayTest {
  @Test fun latest_target_replaces_an_unclaimed_cold_target() = runBlocking {
    val replay = IosDeepLinkReplay()
    val owner = Any()
    replay.emit(DeepLinkTarget("hub-old"))
    replay.emit(DeepLinkTarget("hub-latest", blockId = "block-latest"))

    val tap = replay.taps.first()
    val target = replay.claim(tap, owner)

    assertEquals("hub-latest", target?.hubId)
    assertEquals("block-latest", target?.blockId)
  }

  @Test fun claim_is_exclusive_and_acknowledgement_consumes_replay() = runBlocking {
    val replay = IosDeepLinkReplay()
    val owner = Any()
    val competitor = Any()
    replay.emit(DeepLinkTarget("hub"))
    val tap = replay.taps.first()

    assertEquals("hub", replay.claim(tap, owner)?.hubId)
    assertNull(replay.claim(tap, competitor))

    replay.acknowledge(tap, owner)
    assertNull(replay.claim(tap, owner))
    assertNull(replay.claim(tap, competitor))
  }

  @Test fun release_allows_replacement_controller_to_claim_uncommitted_tap() = runBlocking {
    val replay = IosDeepLinkReplay()
    val disposedOwner = Any()
    val replacementOwner = Any()
    replay.emit(DeepLinkTarget("hub"))
    val tap = replay.taps.first()

    assertEquals("hub", replay.claim(tap, disposedOwner)?.hubId)
    replay.release(disposedOwner)

    assertEquals("hub", replay.claim(tap, replacementOwner)?.hubId)
    replay.acknowledge(tap, replacementOwner)
    assertNull(replay.claim(tap, disposedOwner))
  }
}
