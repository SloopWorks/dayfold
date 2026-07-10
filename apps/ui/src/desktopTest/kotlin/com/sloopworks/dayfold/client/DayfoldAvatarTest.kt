package com.sloopworks.dayfold.client
import androidx.compose.ui.graphics.Color
import com.sloopworks.dayfold.client.ui.AvatarSwatch
import com.sloopworks.dayfold.client.ui.avatarStyle
import kotlin.test.Test
import kotlin.test.assertEquals

private val SW = listOf(
  AvatarSwatch("coral", Color(0xFFFFDAD4), Color(0xFF7A2615)),
  AvatarSwatch("teal",  Color(0xFFCDE9E4), Color(0xFF12433C)),
  AvatarSwatch("violet",Color(0xFFE9DDFB), Color(0xFF3A2260)),
)

class DayfoldAvatarTest {
  @Test fun initialsAreTwoLettersFromNameParts() {
    assertEquals("LG", avatarStyle("Leo Garcia", null, SW).initials)
  }
  @Test fun initialsSingleWordSingleLetter() {
    assertEquals("Y", avatarStyle("you", null, SW).initials)
  }
  @Test fun explicitColorKeyWins() {
    assertEquals(SW[1].bg, avatarStyle("Leo Garcia", "teal", SW).bg)
  }
  @Test fun sameSeedIsDeterministic() {
    assertEquals(avatarStyle("Leo", null, SW).bg, avatarStyle("Leo", null, SW).bg)
  }
}
