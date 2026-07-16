package com.sloopworks.dayfold.client

fun reduceNow(state: AppState, action: Any): AppState = when (action) {
  is NowContentLoaded -> state.copy(now = state.now.copy(content = action.content))
  is SurfacingLoaded -> state.copy(now = state.now.copy(surfacing = action.records))
  else -> state
}
