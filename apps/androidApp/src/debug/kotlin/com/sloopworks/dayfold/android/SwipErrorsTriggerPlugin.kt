package com.sloopworks.dayfold.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sloopworks.debugdrawer.DebugPlugin
import com.sloopworks.debugdrawer.DebugScope

/**
 * Debug-only (ADR 0060 §8): fires the SWIP error pillar on demand so the handled + crash paths
 * can be proven on a real device. Never shown in any user-facing surface.
 */
class SwipErrorsTriggerPlugin : DebugPlugin {
  override val id = "swip-errors-trigger"
  override val title = "SWIP Errors (smoke)"

  @Composable
  override fun Content(scope: DebugScope) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { swipDebugFireWtf() }) { Text("Fire wtf() → PostHog + Sentry") }
      Button(onClick = { swipDebugFireCrash() }) { Text("Fire crash → Sentry (mirrors next launch)") }
    }
  }
}
