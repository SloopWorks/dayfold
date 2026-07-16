package com.sloopworks.dayfold.client

fun reduceProfile(state: AppState, action: Any): AppState = when (action) {
  is ProfileLoaded -> state.copy(
    myDisplayName = action.profile.displayName, myAvatarColor = action.profile.avatarColor,
    myAvatarRef = action.profile.avatarRef, avatarOpId = null, avatarError = null,
    nameOpId = null, nameError = null,
  )
  is AvatarOpRequested -> state.copy(myAvatarColor = action.avatarColor, myAvatarRef = action.avatarRef, avatarOpId = "pending", avatarError = null)
  is AvatarUpdated -> state.copy(myAvatarColor = action.avatarColor, myAvatarRef = action.avatarRef, avatarOpId = null, avatarError = null)
  is AvatarUpdateFailed -> state.copy(myAvatarColor = action.prevAvatarColor, myAvatarRef = action.prevAvatarRef, avatarOpId = null, avatarError = action.message)
  is NameOpRequested -> state.copy(myDisplayName = action.displayName, nameOpId = "pending", nameError = null)
  is NameUpdated -> state.copy(myDisplayName = action.displayName, nameOpId = null, nameError = null)
  is NameUpdateFailed -> state.copy(myDisplayName = action.prevDisplayName, nameOpId = null, nameError = action.message)
  else -> state
}
