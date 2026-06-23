package com.sloopworks.debugdrawer.persistence

import platform.Foundation.NSUserDefaults

/** iOS store backed by [NSUserDefaults]. Writes are async-batched by the system (R5). */
internal class UserDefaultsDebugStore : DebugStore {
  private val defaults = NSUserDefaults.standardUserDefaults
  override fun get(key: String): String? = defaults.stringForKey(key)
  override fun put(key: String, value: String) { defaults.setObject(value, forKey = key) }
  override fun remove(key: String) { defaults.removeObjectForKey(key) }
}

internal actual fun createDebugStore(): DebugStore = UserDefaultsDebugStore()
