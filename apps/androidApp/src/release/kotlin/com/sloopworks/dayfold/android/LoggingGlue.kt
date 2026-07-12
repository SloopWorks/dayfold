package com.sloopworks.dayfold.android
import com.sloopworks.dayfold.client.Log
// Release: no SWIP logging runtime — Log stays on its println fallback, floored at WARN+
// (on-device only; unscrubbed at the front-door, so call sites must not log raw PII).
fun installLogging(debug: Boolean) { Log.minLevel = Log.LogLevel.WARN }
