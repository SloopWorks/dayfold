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

  @Test fun `ProfileLoaded also clears a stale avatarOpId + avatarError (background reload)`() {
    val s = rootReducer(
      AppState(avatarOpId = "pending", avatarError = "Couldn't save your avatar. Try again."),
      ProfileLoaded(MeProfile("U1", "Leo", "teal", "avatar:fox-01")),
    )
    assertNull(s.avatarOpId)
    assertNull(s.avatarError)
  }

  // (a) op-start sets avatarOpId non-null — mirrors MemberOpRequested/DeviceOpRequested,
  // but also applies the picked value optimistically (single-row, no id to key off).
  @Test fun `AvatarOpRequested applies the picked value optimistically and marks avatarOpId busy`() {
    val s = rootReducer(
      AppState(myAvatarColor = "teal", myAvatarRef = "avatar:fox-01", avatarOpId = null),
      AvatarOpRequested("coral", "avatar:sun-01"),
    )
    assertEquals("coral", s.myAvatarColor)
    assertEquals("avatar:sun-01", s.myAvatarRef)
    assertEquals("pending", s.avatarOpId)
    assertNull(s.avatarError)
  }

  // (b) success applies the SERVER-returned value (not necessarily the picked one)
  // and clears avatarOpId.
  @Test fun `AvatarUpdated (success) applies the server-returned value and clears avatarOpId`() {
    val s = rootReducer(
      AppState(myAvatarColor = "coral", myAvatarRef = "avatar:sun-01", avatarOpId = "pending"),
      AvatarUpdated("coral", "avatar:sun-01"),
    )
    assertEquals("coral", s.myAvatarColor)
    assertEquals("avatar:sun-01", s.myAvatarRef)
    assertNull(s.avatarOpId)
    assertNull(s.avatarError)
  }

  // (c) failure reverts to the previous value, clears avatarOpId, and sets avatarError.
  @Test fun `AvatarUpdateFailed reverts to the previous value, clears avatarOpId, and sets avatarError`() {
    // state as it sits mid-flight: the optimistic value is showing, op busy.
    val midFlight = AppState(myAvatarColor = "coral", myAvatarRef = "avatar:sun-01", avatarOpId = "pending")
    val s = rootReducer(midFlight, AvatarUpdateFailed("teal", "avatar:fox-01", "Couldn't save your avatar. Try again."))
    assertEquals("teal", s.myAvatarColor)
    assertEquals("avatar:fox-01", s.myAvatarRef)
    assertNull(s.avatarOpId)
    assertEquals("Couldn't save your avatar. Try again.", s.avatarError)
  }

  @Test fun `AvatarUpdateFailed reverting to null previous values clears the avatar`() {
    val midFlight = AppState(myAvatarColor = "coral", myAvatarRef = "avatar:sun-01", avatarOpId = "pending")
    val s = rootReducer(midFlight, AvatarUpdateFailed(null, null, "Couldn't save your avatar. Try again."))
    assertNull(s.myAvatarColor)
    assertNull(s.myAvatarRef)
    assertNull(s.avatarOpId)
    assertEquals("Couldn't save your avatar. Try again.", s.avatarError)
  }

  // ── display-name edit (mirrors the Avatar* trio) ──────────────────────────
  @Test fun `NameOpRequested applies the new name optimistically and marks nameOpId busy`() {
    val s = rootReducer(AppState(myDisplayName = "Leo", nameOpId = null), NameOpRequested("Zoe"))
    assertEquals("Zoe", s.myDisplayName)
    assertEquals("pending", s.nameOpId)
    assertNull(s.nameError)
  }

  @Test fun `NameUpdated (success) applies the server value and clears nameOpId`() {
    val s = rootReducer(AppState(myDisplayName = "Zoe", nameOpId = "pending"), NameUpdated("Zoe"))
    assertEquals("Zoe", s.myDisplayName)
    assertNull(s.nameOpId)
    assertNull(s.nameError)
  }

  @Test fun `NameUpdateFailed reverts to the previous name, clears nameOpId, and sets nameError`() {
    val midFlight = AppState(myDisplayName = "Zoe", nameOpId = "pending")
    val s = rootReducer(midFlight, NameUpdateFailed("Leo", "Couldn't save your name. Try again."))
    assertEquals("Leo", s.myDisplayName)
    assertNull(s.nameOpId)
    assertEquals("Couldn't save your name. Try again.", s.nameError)
  }

  @Test fun `ProfileLoaded also clears a stale nameOpId + nameError`() {
    val s = rootReducer(
      AppState(nameOpId = "pending", nameError = "Couldn't save your name. Try again."),
      ProfileLoaded(MeProfile("U1", "Leo", null, null)),
    )
    assertNull(s.nameOpId)
    assertNull(s.nameError)
  }
}
