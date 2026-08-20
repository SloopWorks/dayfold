package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosExactReplacementTest {
  @Test fun `failed replacement preserves prior prefixed and legacy schedules`() {
    val subject = "card:weather"
    val prefixed = iosExactNotificationIdentifier(subject)
    val previous = IosExactRequestInventory(
      pending = listOf(
        IosExactRequestRecord(prefixed, subject),
        IosExactRequestRecord(subject, subject),
      ),
      delivered = emptyList(),
    )

    val removals = iosExactRemovalPlan(
      previous = previous,
      desiredIdentifiers = setOf(prefixed),
      acceptedIdentifiers = emptySet(),
      activeSubjects = setOf(subject),
    )

    assertEquals(emptySet(), removals.pending)
  }

  @Test fun `successful replacement removes legacy and stale requests plus delivered history`() {
    val desiredSubject = "card:weather"
    val staleSubject = "card:old"
    val desired = iosExactNotificationIdentifier(desiredSubject)
    val previous = IosExactRequestInventory(
      pending = listOf(
        IosExactRequestRecord(desiredSubject, desiredSubject),
        IosExactRequestRecord(iosExactNotificationIdentifier(staleSubject), staleSubject),
      ),
      delivered = listOf(IosExactRequestRecord(desiredSubject, desiredSubject)),
    )

    val removals = iosExactRemovalPlan(
      previous = previous,
      desiredIdentifiers = setOf(desired),
      acceptedIdentifiers = setOf(desired),
      activeSubjects = setOf(desiredSubject),
    )

    assertEquals(
      setOf(desiredSubject, iosExactNotificationIdentifier(staleSubject)),
      removals.pending,
    )
    assertEquals(setOf(desiredSubject), removals.delivered)
  }

  @Test fun `ordinary reconcile preserves relevant delivered notification`() {
    val subject = "card:weather"
    val identifier = iosExactNotificationIdentifier(subject)

    val removals = iosExactRemovalPlan(
      previous = IosExactRequestInventory(
        pending = emptyList(),
        delivered = listOf(IosExactRequestRecord(identifier, subject)),
      ),
      desiredIdentifiers = setOf(identifier),
      acceptedIdentifiers = setOf(identifier),
      activeSubjects = setOf(subject),
    )

    assertEquals(emptySet(), removals.delivered)
  }

  @Test fun `fired but unread exact notification survives an empty future plan`() {
    val subject = "card:weather"
    val identifier = iosExactNotificationIdentifier(subject)

    val removals = iosExactRemovalPlan(
      previous = IosExactRequestInventory(
        pending = emptyList(),
        delivered = listOf(IosExactRequestRecord(identifier, subject)),
      ),
      desiredIdentifiers = emptySet(),
      acceptedIdentifiers = emptySet(),
      activeSubjects = setOf(subject),
    )

    assertEquals(emptySet(), removals.delivered)
  }

  @Test fun `delivered exact notification is removed after its source disappears`() {
    val subject = "card:weather"
    val identifier = iosExactNotificationIdentifier(subject)

    val removals = iosExactRemovalPlan(
      previous = IosExactRequestInventory(
        pending = emptyList(),
        delivered = listOf(IosExactRequestRecord(identifier, subject)),
      ),
      desiredIdentifiers = emptySet(),
      acceptedIdentifiers = emptySet(),
      activeSubjects = emptySet(),
    )

    assertEquals(setOf(identifier), removals.delivered)
  }

  @Test fun `later invalidation revokes expiration preservation`() {
    assertTrue(shouldPreserveAcceptedExact(expirationGeneration = 7, currentOpenGeneration = 7))
    assertFalse(shouldPreserveAcceptedExact(expirationGeneration = 7, currentOpenGeneration = 8))
    assertFalse(shouldPreserveAcceptedExact(expirationGeneration = 7, currentOpenGeneration = null))
  }
}
