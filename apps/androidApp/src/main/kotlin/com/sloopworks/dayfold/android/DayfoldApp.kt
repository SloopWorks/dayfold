package com.sloopworks.dayfold.android

import android.app.Application
import com.sloopworks.dayfold.client.AndroidApiConfigHolder
import com.sloopworks.dayfold.client.ensurePeriodicRefresh

/**
 * Hosts SWIP init in the EARLIEST app code (ADR 0060): the crash handler must be installed
 * before anything can crash during startup. `swipInit` resolves to the debug glue (real) or
 * the release glue (inert `= Unit`), so this class stays SWIP-free and release keeps zero bytes.
 */
class DayfoldApp : Application() {
  override fun onCreate() {
    super.onCreate()
    swipInit(this)
    // ADR 0020 R3 — set BEFORE anything else in the process can run, so a cold WorkManager/receiver
    // dispatch that never touches MainActivity (see AndroidApiConfigHolder's doc) always finds a
    // non-null api base.
    AndroidApiConfigHolder.apiBase = BuildConfig.DAYFOLD_API
    ensurePeriodicRefresh(this)
  }
}
