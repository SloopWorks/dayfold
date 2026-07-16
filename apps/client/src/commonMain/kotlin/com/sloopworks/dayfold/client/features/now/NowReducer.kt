package com.sloopworks.dayfold.client

fun reduceNow(state: AppState, action: Any): AppState = when (action) {
  is NowContentLoaded -> state.copy(nowContent = action.content)
  is SurfacingLoaded -> state.copy(surfacing = action.records)
  else -> state
}
