package com.sloopworks.debugdrawer

import android.content.Intent
import com.sloopworks.debugdrawer.persistence.debugAppContext

internal actual fun attemptRestart(): Boolean {
  val ctx = debugAppContext() ?: return false
  val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return false
  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
  ctx.startActivity(intent)
  Runtime.getRuntime().exit(0)
  return true
}
