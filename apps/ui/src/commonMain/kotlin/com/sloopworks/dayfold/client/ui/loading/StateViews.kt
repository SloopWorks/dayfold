package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Inline failure with a retry. `retrying` shows a spinner inside the button. */
@Composable
fun ErrorRetry(message: String?, onRetry: () -> Unit, retrying: Boolean = false, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(
    modifier.fillMaxWidth().padding(40.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Something went wrong", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
    Text(
      message ?: "Please try again.",
      style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
    )
    Button(onClick = onRetry, enabled = !retrying) {
      if (retrying) CircularProgressIndicator(strokeWidth = 2.dp, color = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
      else Text("Try again")
    }
  }
}

/** Calm empty-list state. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  Column(
    modifier.fillMaxWidth().padding(40.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, textAlign = TextAlign.Center)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
  }
}
