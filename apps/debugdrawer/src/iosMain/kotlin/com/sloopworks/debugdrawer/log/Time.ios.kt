package com.sloopworks.debugdrawer.log

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
