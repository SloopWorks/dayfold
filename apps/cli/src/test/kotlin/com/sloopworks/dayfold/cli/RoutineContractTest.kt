package com.sloopworks.dayfold.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineContractTest {
  private val fixtureRoot = File("../../specs/domain-model/examples/routines")

  @Test fun `Kotlin producer policy passes the shared valid corpus`() {
    val files = File(fixtureRoot, "valid").listFiles { file -> file.extension == "json" }!!.sortedBy { it.name }
    assertTrue(files.size >= 5)
    files.forEach { file ->
      val result = validateRoutineScenario(file.readText())
      assertTrue(result.isValid, "${file.name}: ${result.issues}")
    }
  }

  @Test fun `Kotlin producer policy rejects the shared invalid corpus`() {
    val files = File(fixtureRoot, "invalid").listFiles { file -> file.extension == "json" }!!.sortedBy { it.name }
    assertTrue(files.size >= 9)
    files.forEach { file ->
      val result = validateRoutineScenario(file.readText())
      assertFalse(result.isValid, "${file.name} unexpectedly passed")
      assertTrue(result.issues.isNotEmpty(), "${file.name} had no stable diagnostic")
    }
  }

  @Test fun `reader maps unknown reason or version to Unknown without retaining wire text`() {
    assertEquals(RecoveryReason.UNKNOWN, decodeRecoveryReason(1, "future_provider_reason"))
    assertEquals(RecoveryReason.UNKNOWN, decodeRecoveryReason(2, "offline"))
    assertEquals(RecoveryReason.OFFLINE, decodeRecoveryReason(1, "offline"))
  }

  @Test fun `forbidden audience update delete and wrong hub remain rejected`() {
    val expectedCodes = mapOf(
      "audience-forgery.json" to "schema.additionalProperties",
      "update-without-version.json" to "schema.const",
      "delete-operation.json" to "schema.const",
      "wrong-hub.json" to "policy.target_hub",
    )
    expectedCodes.forEach { (name, code) ->
      val result = validateRoutineScenario(File(fixtureRoot, "invalid/$name").readText())
      assertTrue(result.issues.any { it.code == code }, "$name missing $code: ${result.issues}")
    }
  }

  @Test fun `finish diagnostics never contain rejected content`() {
    val raw = File(fixtureRoot, "invalid/finish-content-leak.json").readText()
    val diagnostics = validateRoutineScenario(raw).issues.joinToString("\n")
    assertTrue(diagnostics.contains("schema.additionalProperties"))
    assertFalse(diagnostics.contains("SYNTHETIC_PRIVATE_CONTENT_MARKER"))
  }

  @Test fun `finish policy rejects tenant source count result and recovery contradictions`() {
    val expectedCodes = mapOf(
      "finish-family-mismatch.json" to "policy.family_mismatch",
      "finish-missing-source.json" to "policy.missing_source",
      "finish-observed-zero.json" to "policy.source_outcome_count",
      "finish-unavailable-records.json" to "policy.source_outcome_count",
      "finish-success-conflict.json" to "policy.result_coherence",
      "finish-partial-without-recovery.json" to "schema.required",
      "finish-partial-without-degradation.json" to "policy.result_coherence",
      "finish-rejected-without-rejections.json" to "policy.result_coherence",
      "finish-failed-with-proposal.json" to "policy.result_coherence",
    )
    expectedCodes.forEach { (name, code) ->
      val result = validateRoutineScenario(File(fixtureRoot, "invalid/$name").readText())
      assertTrue(result.issues.any { it.code == code }, "$name missing $code: ${result.issues}")
    }
  }

  @Test fun `all fixture identifiers are synthetic and scenarios carry no URL or token field`() {
    fixtureRoot.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
      val raw = file.readText()
      assertFalse(raw.contains("https://"), file.name)
      assertFalse(raw.contains("accessToken"), file.name)
      assertFalse(raw.contains("refreshToken"), file.name)
    }
  }
}
