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

  actual fun openUri(uri: String) { vettedOpenUri(uri)?.let(::open) }

  private fun open(uri: String) {
    // iOS has no geo: handler — geo:0,0?q=<enc> → https://maps.apple.com/?q=<enc>
    // (benefits both the Navigate button and inline geo: links). Falls back to the
    // raw uri when there's no q= so we never produce an empty maps query.
    val target = if (uri.startsWith("geo:")) {
      uri.substringAfter("q=", "").takeIf { it.isNotEmpty() }?.let { "https://maps.apple.com/?q=$it" } ?: uri
    } else {
      uri
    }
    val url = NSURL.URLWithString(target) ?: return
    UIApplication.sharedApplication.openURL(url)
  }
}
