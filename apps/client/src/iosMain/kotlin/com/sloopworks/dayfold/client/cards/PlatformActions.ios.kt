package com.sloopworks.dayfold.client.cards

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard

// iOS actual — UIApplication.openURL for handoffs, UIPasteboard for Copy. Share
// falls back to the pasteboard (a UIActivityViewController needs a presenting VC,
// out of scope until the iOS shell grows one).
actual class PlatformActions {
  actual fun perform(action: CardAction) {
    when (action) {
      is CardAction.Copy -> UIPasteboard.generalPasteboard().string = action.text
      is CardAction.Share -> UIPasteboard.generalPasteboard().string = action.text
      is CardAction.OpenDetail -> {}                   // in-app nav (CL-6)
      else -> cardActionUri(action)?.let(::open)
    }
  }

  private fun open(uri: String) {
    val url = NSURL.URLWithString(uri) ?: return
    UIApplication.sharedApplication.openURL(url)
  }
}
