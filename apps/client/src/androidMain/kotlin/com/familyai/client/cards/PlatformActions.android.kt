package com.familyai.client.cards

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

// Android actual — takes a Context ctor (mirrors DriverFactory). ACTION_VIEW for
// handoffs, ClipboardManager for Copy, ACTION_SEND for Share. Every startActivity
// is guarded (no installed handler → no crash).
actual class PlatformActions(private val context: Context) {
  actual fun perform(action: CardAction) {
    when (action) {
      is CardAction.Copy -> copy(action.text)
      is CardAction.Share -> share(action.text)
      is CardAction.OpenDetail -> {}                   // in-app nav (CL-6)
      else -> cardActionUri(action)?.let(::open)
    }
  }

  private fun open(uri: String) {
    runCatching {
      context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    }
  }

  private fun copy(text: String) {
    runCatching {
      (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("dayfold", text))
    }
  }

  private fun share(text: String) {
    runCatching {
      val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
      }
      context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }
}
