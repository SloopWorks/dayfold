package com.sloopworks.dayfold.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.sloopworks.dayfold.client.generated.Res
import com.sloopworks.dayfold.client.generated.avatar_flower_01
import com.sloopworks.dayfold.client.generated.avatar_fox_01
import com.sloopworks.dayfold.client.generated.avatar_leaf_01
import com.sloopworks.dayfold.client.generated.avatar_moon_01
import com.sloopworks.dayfold.client.generated.avatar_sun_01
import com.sloopworks.dayfold.client.generated.avatar_wave_01
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// Bundled fun-avatar registry — ids are stable ADR-0036 bundled-asset refs
// (avatar:<slug>), never object-storage keys or URLs. Final illustrated art
// replaces these drawable/avatar_*.xml files in place (same ids), no code
// change required.
data class FunAvatar(val id: String, val name: String, val drawable: DrawableResource)

object FunAvatars {
  val all: List<FunAvatar> = listOf(
    FunAvatar("avatar:flower-01", "Flower avatar", Res.drawable.avatar_flower_01),
    FunAvatar("avatar:fox-01", "Fox avatar", Res.drawable.avatar_fox_01),
    FunAvatar("avatar:leaf-01", "Leaf avatar", Res.drawable.avatar_leaf_01),
    FunAvatar("avatar:moon-01", "Moon avatar", Res.drawable.avatar_moon_01),
    FunAvatar("avatar:wave-01", "Wave avatar", Res.drawable.avatar_wave_01),
    FunAvatar("avatar:sun-01", "Sun avatar", Res.drawable.avatar_sun_01),
  )
  private val byId = all.associateBy { it.id }
  fun resolve(ref: String?): FunAvatar? = ref?.let { byId[it] }
}

@Composable
fun FunAvatarImage(a: FunAvatar, size: Dp) =
  Image(painterResource(a.drawable), contentDescription = null, modifier = Modifier.size(size))
