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
    assertEquals("avatar:fox-01", s.profile.avatarRef)
    assertEquals("teal", s.profile.avatarColor)
    assertEquals("Leo", s.profile.displayName)
  }

  @Test fun `ProfileLoaded also clears a stale avatarOpId + avatarError (background reload)`() {
    val s = rootReducer(
      AppState(profile = ProfileState(avatarOpId = "pending", avatarError = "Couldn't save your avatar. Try again.")),
      ProfileLoaded(MeProfile("U1", "Leo", "teal", "avatar:fox-01")),
    )
    assertNull(s.profile.avatarOpId)
    assertNull(s.profile.avatarError)
  }

  // (a) op-start sets avatarOpId non-null — mirrors MemberOpRequested/DeviceOpRequested,
  // but also applies the picked value optimistically (single-row, no id to key off).
  @Test fun `AvatarOpRequested applies the picked value optimistically and marks avatarOpId busy`() {
    val s = rootReducer(
      AppState(profile = ProfileState(avatarColor = "teal", avatarRef = "avatar:fox-01", avatarOpId = null)),
      AvatarOpRequested("coral", "avatar:sun-01"),
    )
    assertEquals("coral", s.profile.avatarColor)
    assertEquals("avatar:sun-01", s.profile.avatarRef)
    assertEquals("pending", s.profile.avatarOpId)
    assertNull(s.profile.avatarError)
  }

  // (b) success applies the SERVER-returned value (not necessarily the picked one)
  // and clears avatarOpId.
  @Test fun `AvatarUpdated (success) applies the server-returned value and clears avatarOpId`() {
    val s = rootReducer(
      AppState(profile = ProfileState(avatarColor = "coral", avatarRef = "avatar:sun-01", avatarOpId = "pending")),
      AvatarUpdated("coral", "avatar:sun-01"),
    )
    assertEquals("coral", s.profile.avatarColor)
    assertEquals("avatar:sun-01", s.profile.avatarRef)
    assertNull(s.profile.avatarOpId)
    assertNull(s.profile.avatarError)
  }

  // (c) failure reverts to the previous value, clears avatarOpId, and sets avatarError.
  @Test fun `AvatarUpdateFailed reverts to the previous value, clears avatarOpId, and sets avatarError`() {
    // state as it sits mid-flight: the optimistic value is showing, op busy.
    val midFlight = AppState(profile = ProfileState(avatarColor = "coral", avatarRef = "avatar:sun-01", avatarOpId = "pending"))
    val s = rootReducer(midFlight, AvatarUpdateFailed("teal", "avatar:fox-01", "Couldn't save your avatar. Try again."))
    assertEquals("teal", s.profile.avatarColor)
    assertEquals("avatar:fox-01", s.profile.avatarRef)
    assertNull(s.profile.avatarOpId)
    assertEquals("Couldn't save your avatar. Try again.", s.profile.avatarError)
  }

  @Test fun `AvatarUpdateFailed reverting to null previous values clears the avatar`() {
    val midFlight = AppState(profile = ProfileState(avatarColor = "coral", avatarRef = "avatar:sun-01", avatarOpId = "pending"))
    val s = rootReducer(midFlight, AvatarUpdateFailed(null, null, "Couldn't save your avatar. Try again."))
    assertNull(s.profile.avatarColor)
    assertNull(s.profile.avatarRef)
    assertNull(s.profile.avatarOpId)
    assertEquals("Couldn't save your avatar. Try again.", s.profile.avatarError)
  }

  // ── display-name edit (mirrors the Avatar* trio) ──────────────────────────
  @Test fun `NameOpRequested applies the new name optimistically and marks nameOpId busy`() {
    val s = rootReducer(AppState(profile = ProfileState(displayName = "Leo", nameOpId = null)), NameOpRequested("Zoe"))
    assertEquals("Zoe", s.profile.displayName)
    assertEquals("pending", s.profile.nameOpId)
    assertNull(s.profile.nameError)
  }

  @Test fun `NameUpdated (success) applies the server value and clears nameOpId`() {
    val s = rootReducer(AppState(profile = ProfileState(displayName = "Zoe", nameOpId = "pending")), NameUpdated("Zoe"))
    assertEquals("Zoe", s.profile.displayName)
    assertNull(s.profile.nameOpId)
    assertNull(s.profile.nameError)
  }

  @Test fun `NameUpdateFailed reverts to the previous name, clears nameOpId, and sets nameError`() {
    val midFlight = AppState(profile = ProfileState(displayName = "Zoe", nameOpId = "pending"))
    val s = rootReducer(midFlight, NameUpdateFailed("Leo", "Couldn't save your name. Try again."))
    assertEquals("Leo", s.profile.displayName)
    assertNull(s.profile.nameOpId)
    assertEquals("Couldn't save your name. Try again.", s.profile.nameError)
  }

  @Test fun `ProfileLoaded also clears a stale nameOpId + nameError`() {
    val s = rootReducer(
      AppState(profile = ProfileState(nameOpId = "pending", nameError = "Couldn't save your name. Try again.")),
      ProfileLoaded(MeProfile("U1", "Leo", null, null)),
    )
    assertNull(s.profile.nameOpId)
    assertNull(s.profile.nameError)
  }
}
