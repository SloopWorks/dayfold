package com.sloopworks.dayfold.client

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sloopworks.dayfold.client.cards.CardAction
import org.reduxkotlin.Store
import org.reduxkotlin.compose.rememberStableStore

/** Test-only adapter that keeps behavior fakes outside FeedApp's production boundary. */
@Composable
internal fun TestFeedApp(
  store: Store<AppState>,
  onSignIn: (String) -> Unit = {},
  onCreateFamily: (String) -> Unit = {},
  onSignOut: () -> Unit = {},
  onRedeemInvite: (String) -> Unit = {},
  onLoadApprovals: () -> Unit = {},
  onApproveMember: (String) -> Unit = {},
  onLoadMembers: () -> Unit = {},
  onRemoveMember: (String) -> Unit = {},
  onMintInvite: (String) -> Unit = {},
  onLoadDevices: () -> Unit = {},
  onRevokeDevice: (String) -> Unit = {},
  onPlatformAction: (CardAction) -> Unit = {},
) {
  val stableStore = rememberStableStore(store)
  val commands = remember(store) {
    val base = StableDayfoldCommands(DayfoldCommands.navigationOnly(store))
    object : StableDayfoldCommands by base {
      override fun createFamily(name: String) = onCreateFamily(name)
      override fun signOut() = onSignOut()
      override fun redeemInvite(token: String) = onRedeemInvite(token)
      override fun loadApprovals(familyId: String) = onLoadApprovals()
      override fun approveMember(familyId: String, userId: String) = onApproveMember(userId)
      override fun loadMembers(familyId: String) = onLoadMembers()
      override fun removeMember(familyId: String, userId: String) = onRemoveMember(userId)
      override fun mintInvite(familyId: String, mode: String) = onMintInvite(mode)
      override fun loadDevices() = onLoadDevices()
      override fun revokeDevice(deviceId: String) = onRevokeDevice(deviceId)
    }
  }
  val platformActions = remember(store) {
    StablePlatformActions.noOp(
      onSignIn = onSignIn,
      onPerform = onPlatformAction,
    )
  }
  FeedApp(
    store = stableStore,
    commands = commands,
    platformActions = platformActions,
  )
}
