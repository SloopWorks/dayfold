package com.sloopworks.dayfold.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.DayfoldCommands
import com.sloopworks.dayfold.client.FeedApp
import com.sloopworks.dayfold.client.StableDayfoldCommands
import com.sloopworks.dayfold.client.StablePlatformActions
import org.reduxkotlin.Store
import org.reduxkotlin.compose.rememberSelectorStore

/** Android-test adapter that keeps deterministic behavior fakes outside the production boundary. */
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
  onLoadDevices: () -> Unit = {},
  onRevokeDevice: (String) -> Unit = {},
) {
  val selectorStore = rememberSelectorStore(store)
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
      override fun loadDevices() = onLoadDevices()
      override fun revokeDevice(deviceId: String) = onRevokeDevice(deviceId)
    }
  }
  val platformActions = remember(store) { StablePlatformActions.noOp(onSignIn = onSignIn) }
  FeedApp(
    store = selectorStore,
    commands = commands,
    platformActions = platformActions,
  )
}
