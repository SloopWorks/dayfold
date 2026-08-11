package com.sloopworks.dayfold.android

import androidx.compose.runtime.Composable

/** Release mirror: no inspector, no registration, pure passthrough. */
@Composable
fun ComponentsRecordedContent(content: @Composable () -> Unit) {
  content()
}
