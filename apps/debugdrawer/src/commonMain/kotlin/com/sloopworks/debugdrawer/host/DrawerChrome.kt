package com.sloopworks.debugdrawer.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloopworks.debugdrawer.theme.DrawerColors
import com.sloopworks.debugdrawer.theme.SailMark

// Shared chrome primitives (C4/C5 + chips/dots), styled to the theme tokens.
// Hairline borders over shadow; 4dp scale; rows ≥48dp (a11y).

/** C4 — a tappable panel row in the host list. */
@Composable
fun PanelListRow(title: String, trailing: String? = null, colors: DrawerColors, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 52.dp)
      .clickable(onClickLabel = title) { onClick() }
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (trailing != null) Text(trailing, color = colors.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
      Text("  ›", color = colors.faint, fontSize = 16.sp)
    }
  }
}

/** C5 — a read-only key/value row; the value is tap-to-copy. */
@Composable
fun KeyValueRow(label: String, value: String, colors: DrawerColors, onCopy: (String) -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .clickable(onClickLabel = "Copy $label") { onCopy(value) }
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Text(label, color = colors.faint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    Text(value, color = colors.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
  }
}

/** A small monospace chip (e.g. the DEBUG build-type tag). */
@Composable
fun MonoChip(text: String, colors: DrawerColors, modifier: Modifier = Modifier) {
  Box(
    modifier
      .clip(RoundedCornerShape(5.dp))
      .border(1.dp, colors.border, RoundedCornerShape(5.dp))
      .padding(horizontal = 7.dp, vertical = 2.dp)
  ) {
    Text(text, color = colors.faint, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium)
  }
}

/** A status dot — color carries meaning but is always paired with text by callers. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
  Box(modifier.size(7.dp).clip(CircleShape).background(color))
}

/** Host header (C3): brand mark + name + build chip + close; or back + title in detail. */
@Composable
fun HostHeader(
  colors: DrawerColors,
  brandName: String,
  buildType: String,
  inDetail: Boolean,
  detailTitle: String?,
  onBack: () -> Unit,
  onClose: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (inDetail) {
        Text(
          "‹  ${detailTitle.orEmpty()}",
          color = colors.text,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier
            .clickable(onClickLabel = "Back") { onBack() }
            .semantics { contentDescription = "Back" }
            .padding(end = 8.dp),
        )
      } else {
        SailMark(colors.accent, Modifier.size(18.dp).padding(end = 0.dp))
        Text("  $brandName", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        MonoChip(buildType.uppercase(), colors, Modifier.padding(start = 10.dp))
      }
    }
    Text(
      "✕",
      color = colors.muted,
      fontSize = 16.sp,
      modifier = Modifier
        .clickable(onClickLabel = "Close debug drawer") { onClose() }
        .semantics { contentDescription = "Close debug drawer" }
        .padding(4.dp),
    )
  }
}
