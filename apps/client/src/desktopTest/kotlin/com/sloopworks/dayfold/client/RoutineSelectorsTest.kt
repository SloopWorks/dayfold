package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.fake.FakeScenarios
import com.sloopworks.dayfold.client.fake.FakeBackend
import com.sloopworks.dayfold.client.fake.SMART_BRIEFINGS_PREVIEW_SCENARIO_ID
import com.sloopworks.dayfold.client.fake.initialStateForFakeScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineSelectorsTest {
  @Test fun `wire recovery decoding is total and never retains unknown values`() {
    val known = routineRecoveryFromWire(
      1,
      "source_probe",
      "source_reauth",
      "user_action",
      "manage_source",
    )
    assertEquals(RoutineRecoveryPhase.SOURCE_PROBE, known.phase)
    assertEquals(RoutineRecoveryReason.SOURCE_REAUTH, known.reason)
    assertEquals(RoutineRecommendedAction.MANAGE_SOURCE, known.recommendedAction)

    listOf(
      routineRecoveryFromWire(2, "source_probe", "source_reauth", "user_action", "manage_source"),
      routineRecoveryFromWire(1, "future_phase_value", "source_reauth", "user_action", "manage_source"),
      routineRecoveryFromWire(1, "source_probe", "provider said something private", "user_action", "manage_source"),
    ).forEach { unknown ->
      assertEquals(ROUTINE_RECOVERY_SCHEMA_VERSION, unknown.schemaVersion)
      assertEquals(RoutineRecoveryPhase.UNKNOWN, unknown.phase)
      assertEquals(RoutineRecoveryReason.UNKNOWN, unknown.reason)
      assertEquals(RoutineRecommendedAction.CONTACT_SUPPORT, unknown.recommendedAction)
      assertFalse(unknown.supportCodeAvailable)
    }
  }

  @Test fun `synthetic hub fixtures exclude restricted and disappeared targets`() {
    val available = AppState(routines = RoutineState.preview().copy(
      selectedHub = RoutineSyntheticHub.FAMILY_BRIEFING,
    ))
    assertEquals(RoutineSyntheticHub.FAMILY_BRIEFING, routineSelectedHubOption(available)?.hub)
    assertTrue(routineHubOptions(available).single { it.hub == RoutineSyntheticHub.RESTRICTED_EXAMPLE }.restricted)
    assertFalse(routineHubOptions(available).single { it.hub == RoutineSyntheticHub.RESTRICTED_EXAMPLE }.eligible)

    assertNull(routineSelectedHubOption(available.copy(
      routines = available.routines.copy(hubFixture = RoutineHubFixture.SELECTED_REMOVED),
    )))
    assertTrue(routineHubOptions(available.copy(
      routines = available.routines.copy(hubFixture = RoutineHubFixture.EMPTY),
    )).isEmpty())
  }

  @Test fun `only the exact dedicated fake scenario injects preview capability`() {
    val matches = FakeScenarios.all.filter { it.id == SMART_BRIEFINGS_PREVIEW_SCENARIO_ID }
    assertEquals(1, matches.size)
    assertNotNull(FakeScenarios.byId(SMART_BRIEFINGS_PREVIEW_SCENARIO_ID))
    assertEquals(RoutineCapability.PREVIEW, initialStateForFakeScenario(SMART_BRIEFINGS_PREVIEW_SCENARIO_ID).routines.capability)

    (FakeScenarios.all.map { it.id }.filterNot { it == SMART_BRIEFINGS_PREVIEW_SCENARIO_ID } + listOf("unknown", "", null)).forEach {
      assertEquals(RoutineCapability.HIDDEN, initialStateForFakeScenario(it).routines.capability, "scenario=$it")
    }
  }

  @Test fun `preview fake serves existing read fixtures and exposes no routine API`() {
    val scenario = FakeScenarios.byId(SMART_BRIEFINGS_PREVIEW_SCENARIO_ID)!!
    val backend = FakeBackend(scenario.data)
    assertEquals(200, backend.handle("GET", "/auth/whoami", null).status)
    assertEquals(200, backend.handle("GET", "/families/synthetic/sync", null).status)
    assertEquals(404, backend.handle("GET", "/families/synthetic/routines", null).status)
    assertEquals(404, backend.handle("POST", "/families/synthetic/routines", null).status)
  }
}
