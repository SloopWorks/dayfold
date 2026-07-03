package com.sloopworks.dayfold.client

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloopworks.dayfold.client.theme.DayfoldDarkColors
import com.sloopworks.dayfold.client.theme.DayfoldLightColors
import com.sloopworks.dayfold.client.theme.DayfoldLightExtended
import com.sloopworks.dayfold.client.theme.DayfoldShapes
import com.sloopworks.dayfold.client.theme.DayfoldTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// Verifies the Dayfold tokens carry the brand values (not M3 defaults) and that
// light/dark are distinct — catches an accidental default-palette or a wrong hex.
class DayfoldThemeTest {
  @Test fun lightPrimaryIsBrandCoral() =
    assertEquals(Color(0xFFC0381E), DayfoldLightColors.primary)

  @Test fun darkPrimaryIsBrandCoral() =
    assertEquals(Color(0xFFFFB4A3), DayfoldDarkColors.primary)

  @Test fun lightAndDarkSurfacesDiffer() =
    assertNotEquals(DayfoldLightColors.surface, DayfoldDarkColors.surface)

  @Test fun secondaryAndTertiaryAreBrandTealAndViolet() {
    assertEquals(Color(0xFF00796F), DayfoldLightColors.secondary)
    assertEquals(Color(0xFF6438AE), DayfoldLightColors.tertiary)
  }

  @Test fun cardShapeIs26dp() =
    assertEquals(RoundedCornerShape(26.dp), DayfoldShapes.large)

  @Test fun titleAndHeadlineUseDisplayWeightAndSize() {
    assertEquals(FontWeight.SemiBold, DayfoldTypography.titleMedium.fontWeight)
    assertEquals(17.sp, DayfoldTypography.titleMedium.fontSize)
    assertEquals(24.sp, DayfoldTypography.headlineSmall.fontSize)
    assertEquals(FontWeight.SemiBold, DayfoldTypography.labelSmall.fontWeight)
  }

  @Test fun extendedPrivacyChipIsOnDeviceTeal() =
    assertEquals(Color(0xFF0B5048), DayfoldLightExtended.onPrivacyContainer)
}
