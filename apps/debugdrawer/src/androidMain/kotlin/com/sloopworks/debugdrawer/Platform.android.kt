package com.sloopworks.debugdrawer

import android.content.Context
import com.sloopworks.debugdrawer.persistence.initAndroidDebugStore

internal actual fun initPlatform(context: Any?) {
  (context as? Context)?.let { initAndroidDebugStore(it) }
}
