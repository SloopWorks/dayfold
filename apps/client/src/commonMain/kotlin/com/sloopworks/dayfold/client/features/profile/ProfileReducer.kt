package com.sloopworks.dayfold.client

fun reduceProfile(state: AppState, action: Any): AppState = when (action) {
  is ProfileLoaded -> state.copy(profile = state.profile.copy(
    displayName = action.profile.displayName, avatarColor = action.profile.avatarColor,
    avatarRef = action.profile.avatarRef, avatarOpId = null, avatarError = null,
    nameOpId = null, nameError = null,
  ))
  is AvatarOpRequested -> state.copy(profile = state.profile.copy(avatarColor = action.avatarColor, avatarRef = action.avatarRef, avatarOpId = "pending", avatarError = null))
  is AvatarUpdated -> state.copy(profile = state.profile.copy(avatarColor = action.avatarColor, avatarRef = action.avatarRef, avatarOpId = null, avatarError = null))
  is AvatarUpdateFailed -> state.copy(profile = state.profile.copy(avatarColor = action.prevAvatarColor, avatarRef = action.prevAvatarRef, avatarOpId = null, avatarError = action.message))
  is NameOpRequested -> state.copy(profile = state.profile.copy(displayName = action.displayName, nameOpId = "pending", nameError = null))
  is NameUpdated -> state.copy(profile = state.profile.copy(displayName = action.displayName, nameOpId = null, nameError = null))
  is NameUpdateFailed -> state.copy(profile = state.profile.copy(displayName = action.prevDisplayName, nameOpId = null, nameError = action.message))
  else -> state
}
