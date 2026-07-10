package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// AUTH-S5 T4 — own-profile load + optimistic avatar-update reducer cases. Pure
// state transitions only (mirrors AuthReducerTest's rootReducer harness); all I/O
// (GET/PATCH /auth/me) lives in AuthEngine.
class AvatarReducerTest {
  @Test fun `ProfileLoaded populates myDisplayName + myAvatar fields`() {
    val s = rootReducer(AppState(), ProfileLoaded(MeProfile("U1", "Leo", "teal", "avatar:fox-01")))
    assertEquals("avatar:fox-01", s.myAvatarRef)
    assertEquals("teal", s.myAvatarColor)
    assertEquals("Leo", s.myDisplayName)
  }

  @Test fun `AvatarUpdated applies optimistically and clears avatarOpId`() {
    val s = rootReducer(AppState(myAvatarRef = null, avatarOpId = "pending"), AvatarUpdated("coral", "avatar:sun-01"))
    assertEquals("avatar:sun-01", s.myAvatarRef)
    assertEquals("coral", s.myAvatarColor)
    assertNull(s.avatarOpId)
  }

  @Test fun `AvatarUpdateFailed clears avatarOpId and leaves the optimistic value in place`() {
    val optimistic = AppState(myAvatarColor = "coral", myAvatarRef = "avatar:sun-01", avatarOpId = "pending")
    val s = rootReducer(optimistic, AvatarUpdateFailed)
    assertNull(s.avatarOpId)
    // do not block on network / do not revert — the optimistic value stays
    assertEquals("coral", s.myAvatarColor)
    assertEquals("avatar:sun-01", s.myAvatarRef)
  }
}
