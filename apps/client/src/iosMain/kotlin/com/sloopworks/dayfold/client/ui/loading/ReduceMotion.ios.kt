package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

// Honor iOS Settings → Accessibility → Motion → Reduce Motion.
@Composable actual fun rememberReduceMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
