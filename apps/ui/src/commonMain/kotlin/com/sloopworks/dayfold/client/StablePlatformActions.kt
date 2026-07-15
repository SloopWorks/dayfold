package com.sloopworks.dayfold.client

import androidx.compose.runtime.Stable
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.PlatformActions

/** Compose-stable boundary for host-scoped native UI and operating-system handoffs. */
@Stable
interface StablePlatformActions {
  val supportsDevSignIn: Boolean

  fun perform(action: CardAction)
  fun openUri(uri: String)
  fun signIn(provider: String)
  fun devSignIn()
  fun openAppSettings()
  fun requestProximityPermissions()

  companion object {
    /** A deterministic platform seam for previews and tests. */
    fun noOp(
      onSignIn: (String) -> Unit = {},
      onDevSignIn: (() -> Unit)? = null,
      onOpenAppSettings: () -> Unit = {},
      onRequestProximityPermissions: () -> Unit = {},
      onPerform: (CardAction) -> Unit = {},
      onOpenUri: (String) -> Unit = {},
    ): StablePlatformActions = RuntimeStablePlatformActions(
      onPerform = onPerform,
      onOpenUri = onOpenUri,
      onSignIn = onSignIn,
      onDevSignIn = onDevSignIn,
      onOpenAppSettings = onOpenAppSettings,
      onRequestProximityPermissions = onRequestProximityPermissions,
    )
  }
}

/** Wraps native [platformActions] and the remaining host-scoped UI seams once. */
fun StablePlatformActions(
  platformActions: PlatformActions,
  onSignIn: (String) -> Unit = {},
  onDevSignIn: (() -> Unit)? = null,
  onOpenAppSettings: () -> Unit = {},
  onRequestProximityPermissions: () -> Unit = {},
): StablePlatformActions = RuntimeStablePlatformActions(
  onPerform = platformActions::perform,
  onOpenUri = platformActions::openUri,
  onSignIn = onSignIn,
  onDevSignIn = onDevSignIn,
  onOpenAppSettings = onOpenAppSettings,
  onRequestProximityPermissions = onRequestProximityPermissions,
)

private class RuntimeStablePlatformActions(
  private val onPerform: (CardAction) -> Unit,
  private val onOpenUri: (String) -> Unit,
  private val onSignIn: (String) -> Unit,
  private val onDevSignIn: (() -> Unit)?,
  private val onOpenAppSettings: () -> Unit,
  private val onRequestProximityPermissions: () -> Unit,
) : StablePlatformActions {
  override val supportsDevSignIn: Boolean = onDevSignIn != null

  override fun perform(action: CardAction) = onPerform(action)
  override fun openUri(uri: String) = onOpenUri(uri)
  override fun signIn(provider: String) = onSignIn(provider)
  override fun devSignIn() { onDevSignIn?.invoke() }
  override fun openAppSettings() = onOpenAppSettings()
  override fun requestProximityPermissions() = onRequestProximityPermissions()
}
