package com.sloopworks.debugdrawer.persistence

import android.content.Context

/**
 * Android store backed by SharedPreferences. The application Context is supplied
 * once via [initAndroidDebugStore] (called from `DebugDrawer.install(config, context)`,
 * R3) — NOT via LocalContext, so the store works outside composition (e.g. when the
 * app constructs its HTTP clients at startup).
 */
@Volatile
private var appContext: Context? = null

fun initAndroidDebugStore(context: Context) {
  appContext = context.applicationContext
}

/** App context for in-module platform actions (e.g. restart). Null until installed. */
internal fun debugAppContext(): Context? = appContext

internal class SharedPrefsDebugStore(context: Context) : DebugStore {
  private val sp = context.getSharedPreferences("com.sloopworks.debugdrawer", Context.MODE_PRIVATE)
  override fun get(key: String): String? = sp.getString(key, null)
  // apply() = async write (R5), never commit() (sync disk I/O on the main thread).
  override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
  override fun remove(key: String) { sp.edit().remove(key).apply() }
}

internal actual fun createDebugStore(): DebugStore =
  SharedPrefsDebugStore(
    appContext ?: error("initAndroidDebugStore(context) not called — call DebugDrawer.install(config, context)")
  )
