package com.sloopworks.dayfold.client.cards

import androidx.compose.ui.platform.UriHandler

// Routes inline [label](url) link taps through the shell's vetted PlatformActions.
// A LinkAnnotation.Url with no linkInteractionListener opens via LocalUriHandler
// (Compose foundation: TextLinkScope → uriHandler.openUri); installing this as
// LocalUriHandler around the app content makes inline links share the action-
// button handoff path (Android runCatching → no crash, iOS geo→maps, desktop
// best-effort) instead of Compose's default system handler. The render-time
// allowlist already gates which links exist; PlatformActions.openUri re-vets
// (defense-in-depth).
class PlatformUriHandler(private val onOpenUri: (String) -> Unit) : UriHandler {
  override fun openUri(uri: String) = onOpenUri(uri)
}
