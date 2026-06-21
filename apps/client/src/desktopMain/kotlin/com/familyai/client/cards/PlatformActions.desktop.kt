package com.familyai.client.cards

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

// Desktop actual. Desktop.browse handles http/mailto on most OSes; tel/sms/geo
// are best-effort (guarded). Copy → AWT clipboard; Share → clipboard fallback.
actual class PlatformActions {
  actual fun perform(action: CardAction) {
    when (action) {
      is CardAction.Copy -> copy(action.text)
      is CardAction.Share -> copy(action.text)        // no native share on desktop → clipboard
      is CardAction.OpenDetail -> {}                   // in-app nav (CL-6)
      else -> cardActionUri(action)?.let(::open)
    }
  }

  private fun open(uri: String) {
    runCatching {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
        Desktop.getDesktop().browse(URI(uri))
    }
  }

  private fun copy(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
  }
}
