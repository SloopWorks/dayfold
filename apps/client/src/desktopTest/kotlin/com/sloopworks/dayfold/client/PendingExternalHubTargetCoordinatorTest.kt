package com.sloopworks.dayfold.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingExternalHubTargetCoordinatorTest {
  @Test fun `latest cold target is delivered once after family binding`() = runBlocking {
    val fixture = fixture()
    try {
      val admitted = mutableListOf<String>()
      fixture.coordinator.submit(DeepLinkTarget("hub-old")) { admitted += "hub-old" }
      fixture.coordinator.submit(DeepLinkTarget("hub-latest", blockId = "block-latest")) {
        admitted += "hub-latest"
      }

      fixture.coordinator.familyBound(fixture.familyA)
      fixture.coordinator.awaitIdle()

      assertEquals(listOf("hub-latest:block-latest"), fixture.delivered)
      assertEquals(listOf("hub-latest"), admitted)
    } finally {
      fixture.scope.cancel()
    }
  }

  @Test fun `warm targets are admitted in submission order`() = runBlocking {
    val fixture = fixture()
    try {
      fixture.coordinator.familyBound(fixture.familyA)
      fixture.coordinator.submit(DeepLinkTarget("hub-a"))
      fixture.coordinator.submit(DeepLinkTarget("hub-b"))
      fixture.coordinator.awaitIdle()

      assertEquals(listOf("hub-a:null", "hub-b:null"), fixture.delivered)
    } finally {
      fixture.scope.cancel()
    }
  }

  @Test fun `target associated with a stale family cannot cross into its replacement`() = runBlocking {
    val fixture = fixture()
    try {
      fixture.coordinator.familyBound(fixture.familyA)
      val familyB = fixture.sessions.selectFamily(fixture.auth, "family-b")!!

      fixture.coordinator.submit(DeepLinkTarget("hub-b"))
      fixture.coordinator.awaitIdle()
      assertTrue(fixture.delivered.isEmpty())

      fixture.coordinator.familyBound(familyB)
      fixture.coordinator.awaitIdle()

      assertTrue(fixture.delivered.isEmpty())
      assertTrue(fixture.deliveredFamilies.isEmpty())
    } finally {
      fixture.scope.cancel()
    }
  }

  @Test fun `terminal clear prevents old target crossing into a new identity`() = runBlocking {
    val fixture = fixture()
    try {
      var discarded = 0
      fixture.coordinator.submit(
        target = DeepLinkTarget("old-family-hub"),
        onDiscarded = { discarded += 1 },
      )
      fixture.coordinator.clear()

      val newAuth = fixture.sessions.install(Session("new-access", "new-refresh"))
      val newFamily = fixture.sessions.selectFamily(newAuth, "new-family")!!
      fixture.coordinator.familyBound(newFamily)
      fixture.coordinator.awaitIdle()

      assertTrue(fixture.delivered.isEmpty())
      assertEquals(1, discarded)
    } finally {
      fixture.scope.cancel()
    }
  }

  private fun fixture(): Fixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sessions = SessionCoordinator(
      refreshScope = scope,
      refreshSession = { error("refresh is not used") },
      commitRotation = {},
    )
    val auth = sessions.install(Session("access", "refresh"))
    val familyA = sessions.selectFamily(auth, "family-a")!!
    val delivered = mutableListOf<String>()
    val deliveredFamilies = mutableListOf<String>()
    val coordinator = PendingExternalHubTargetCoordinator(
      scope = scope,
      isCurrent = sessions::isCurrent,
      deliver = { family, target, onAdmitted ->
        deliveredFamilies += family.familyId
        delivered += "${target.hubId}:${target.blockId}"
        onAdmitted()
        true
      },
    )
    return Fixture(scope, sessions, auth, familyA, coordinator, delivered, deliveredFamilies)
  }

  private class Fixture(
    val scope: CoroutineScope,
    val sessions: SessionCoordinator,
    val auth: AuthSessionContext,
    val familyA: FamilySessionContext,
    val coordinator: PendingExternalHubTargetCoordinator,
    val delivered: MutableList<String>,
    val deliveredFamilies: MutableList<String>,
  )
}
