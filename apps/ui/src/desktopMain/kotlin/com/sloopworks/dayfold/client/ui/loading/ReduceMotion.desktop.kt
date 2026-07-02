package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.runtime.Composable

// Desktop has no OS reduced-motion signal we read at M0 → animate.
@Composable actual fun rememberReduceMotion(): Boolean = false
