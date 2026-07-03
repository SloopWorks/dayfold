package com.sloopworks.dayfold.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

// #223 loading states added per-row op tracking (memberOpId / deviceOpId). The correctness
// guard: while ONE member/device op is in flight, every OTHER row's action disables
// (clickable(enabled = !anyBusy)) so a second tap can't start a concurrent, racing op (a
// double-remove / double-revoke). The *-row-busy snapshots prove the in-flight row's spinner;
// these pin the GUARD on the sibling rows behaviorally — the part a snapshot can't assert.
@OptIn(ExperimentalTestApi::class)
class LoadingConcurrencyGuardTest {
  @Test fun memberOpInFlightDisablesOtherRowActions() = runComposeUiTest {
    // u1's approve/decline is in flight (memberOpId) → u1 shows a spinner; u2's actions,
    // though rendered, must be disabled so they can't fire while u1 resolves.
    val state = AppState(
      pendingApprovals = listOf(PendingMember("u1", "Alex Kim"), PendingMember("u2", "Sam Rivera")),
      memberOpId = "u1",
    )
    setContent { MaterialTheme { MembersScreen(state) } }
    onNodeWithTag("approve-u2").assertIsNotEnabled()
    onNodeWithTag("decline-u2").assertIsNotEnabled()
  }

  @Test fun deviceOpInFlightDisablesOtherRevokes() = runComposeUiTest {
    // c1's revoke is in flight (deviceOpId) → c1 shows a spinner; c2's revoke, though
    // rendered, must be disabled so a second revoke can't race the first.
    val state = AppState(
      devices = listOf(
        DeviceCredential("c1", kind = "cli", label = "claude-code · CI"),
        DeviceCredential("c2", kind = "cli", label = "laptop · dev"),
      ),
      deviceOpId = "c1",
    )
    setContent { MaterialTheme { DevicesScreen(state) } }
    onNodeWithTag("revoke-c2").assertIsNotEnabled()
  }
}
