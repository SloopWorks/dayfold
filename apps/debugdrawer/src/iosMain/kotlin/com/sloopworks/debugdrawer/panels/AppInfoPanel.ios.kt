package com.sloopworks.debugdrawer.panels

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.UIKit.UIDevice

internal actual fun platformInfo(): PlatformInfo {
  val d = UIDevice.currentDevice
  return PlatformInfo(
    os = d.systemName,
    osVersion = d.systemVersion,
    device = d.model,
    locale = NSLocale.currentLocale.localeIdentifier,
  )
}
