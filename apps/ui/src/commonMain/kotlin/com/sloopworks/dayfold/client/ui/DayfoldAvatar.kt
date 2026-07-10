package com.sloopworks.dayfold.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

data class AvatarSwatch(val key: String, val bg: Color, val fg: Color)
data class AvatarStyle(val initials: String, val bg: Color, val fg: Color)

fun avatarStyle(seed: String, avatarColorKey: String?, swatches: List<AvatarSwatch>): AvatarStyle {
  val initials = seed.trim().split(" ").filter { it.isNotBlank() }
    .mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")
    .ifEmpty { "?" }
  val sw = swatches.firstOrNull { it.key == avatarColorKey }
    ?: swatches[((seed.hashCode().toLong() and 0x7fffffffL) % swatches.size).toInt()]
  return AvatarStyle(initials, sw.bg, sw.fg)
}

@Composable
fun DayfoldAvatar(
  name: String,
  size: Dp,
  avatarColorKey: String? = null,
  avatarRef: String? = null,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
) {
  val swatches = com.sloopworks.dayfold.client.theme.LocalDayfoldColors.current.avatarSwatches
  val sem = Modifier.semantics {
    if (contentDescription != null) this.contentDescription = contentDescription
  }
  val fun_ = FunAvatars.resolve(avatarRef)   // Task 2; null-safe: returns null until Task 2 lands
  Box(modifier.size(size).clip(CircleShape).then(sem), contentAlignment = Alignment.Center) {
    if (fun_ != null) {
      FunAvatarImage(fun_, size)             // Task 2
    } else {
      val s = avatarStyle(name, avatarColorKey, swatches)
      Box(Modifier.size(size).clip(CircleShape).background(s.bg))
      Text(s.initials, color = s.fg, fontSize = (size.value * 0.4f).sp,
        modifier = Modifier.clearAndSetSemantics {})
    }
  }
}

// --- Temporary Task-2 scaffolding: keep until fun-avatar drawable resolution lands. ---
internal object FunAvatars { fun resolve(ref: String?): FunAvatar? = null }
internal class FunAvatar
@Composable internal fun FunAvatarImage(a: FunAvatar, size: Dp) {}
