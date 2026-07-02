package com.sloopworks.dayfold.client.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Dayfold shape scale (designs/Brand.dc.html): cards = large 26dp (the morphing
// detail-surface start radius), pills/chips/buttons use the component's own full
// shape (999). 8/12/16/26/32 ramp.
val DayfoldShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(26.dp),
  extraLarge = RoundedCornerShape(32.dp),
)
