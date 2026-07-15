package com.sloopworks.dayfold.android

import android.app.Application

/**
 * Hosts SWIP init in the EARLIEST app code (ADR 0060): the crash handler must be installed
 * before anything can crash during startup. `swipInit` resolves to the debug glue (real) or
 * the release glue (inert `= Unit`), so this class stays SWIP-free and release keeps zero bytes.
 */
class DayfoldApp : Application() {
  override fun onCreate() {
    super.onCreate()
    swipInit(this)
  }
}
