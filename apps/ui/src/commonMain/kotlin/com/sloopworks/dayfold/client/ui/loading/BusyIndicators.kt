package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** In-row busy spinner sized to a 48dp touch target (no layout jump vs an icon button). */
@Composable
fun RowBusy(modifier: Modifier = Modifier) {
  Box(
    modifier.size(48.dp).semantics { contentDescription = "Working" },
    contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
  }
}

/** Full-screen blocking spinner over the app surface. */
@Composable
fun FullScreenLoading(content: @Composable () -> Unit) {
  val cs = MaterialTheme.colorScheme
  Box(Modifier.fillMaxSize().background(cs.surface), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
      content()
      CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
    }
  }
}
