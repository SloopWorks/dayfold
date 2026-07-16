package com.sloopworks.dayfold.client

import androidx.compose.runtime.Stable

/** Compose-stable UI command contract backed by the application-owned [DayfoldCommands]. */
@Stable
interface StableDayfoldCommands {
  fun retryAuth()
  fun createFamily(name: String)
  fun signOut()
  fun redeemInvite(token: String)
  fun loadApprovals(familyId: String)
  fun approveMember(familyId: String, userId: String)
  fun declineMember(familyId: String, userId: String)
  fun loadMembers(familyId: String)
  fun removeMember(familyId: String, userId: String)
  fun mintInvite(familyId: String, mode: String)
  fun revokeInvite(familyId: String, inviteId: String)
  fun updateAvatar(color: String?, avatarRef: String?)
  fun updateDisplayName(name: String)
  fun loadDevices()
  fun revokeDevice(deviceId: String)
  fun lookupDevice(userCode: String)
  fun approveDevice(familyId: String, userCode: String, hubIds: List<String>?)
  fun denyDevice(familyId: String, userCode: String)
  fun refresh()
  fun loadHubs()
  fun nowShown(subjectKeys: Set<String>)
  fun openHubs(returnDestination: HubReturnDestination = HubReturnDestination.HUB_LIST)
  fun openHub(
    familyId: String,
    hubId: String,
    focusBlockId: String? = null,
    returnDestination: HubReturnDestination = HubReturnDestination.HUB_LIST,
  )
  fun closeHub(expectedHubId: String, destination: HubReturnDestination)
  fun loadAudience(familyId: String, hubId: String)
  fun setHubRole(familyId: String, hubId: String, userId: String, role: String)
  fun removeHubParticipant(familyId: String, hubId: String, userId: String)
  fun setHubVisibility(familyId: String, hubId: String, visibility: String)
  fun toggleItem(familyId: String, blockId: String, itemId: String, done: Boolean)
  fun retryBlock(familyId: String, blockId: String)
  fun deleteBlock(familyId: String, blockId: String)
  fun hideBlock(familyId: String, blockId: String)
  fun unhideBlock(familyId: String, blockId: String)
  fun setNotificationConfig(config: NotifConfig)
}

/** Wraps [commands] once in a Compose-stable UI command boundary. */
fun StableDayfoldCommands(commands: DayfoldCommands): StableDayfoldCommands =
  RuntimeStableDayfoldCommands(commands)

private class RuntimeStableDayfoldCommands(
  private val commands: DayfoldCommands,
) : StableDayfoldCommands {
  override fun retryAuth() = commands.retryAuth()
  override fun createFamily(name: String) = commands.createFamily(name)
  override fun signOut() = commands.signOut()
  override fun redeemInvite(token: String) = commands.redeemInvite(token)
  override fun loadApprovals(familyId: String) = commands.loadApprovals(familyId)
  override fun approveMember(familyId: String, userId: String) = commands.approveMember(familyId, userId)
  override fun declineMember(familyId: String, userId: String) = commands.declineMember(familyId, userId)
  override fun loadMembers(familyId: String) = commands.loadMembers(familyId)
  override fun removeMember(familyId: String, userId: String) = commands.removeMember(familyId, userId)
  override fun mintInvite(familyId: String, mode: String) = commands.mintInvite(familyId, mode)
  override fun revokeInvite(familyId: String, inviteId: String) = commands.revokeInvite(familyId, inviteId)
  override fun updateAvatar(color: String?, avatarRef: String?) = commands.updateAvatar(color, avatarRef)
  override fun updateDisplayName(name: String) = commands.updateDisplayName(name)
  override fun loadDevices() = commands.loadDevices()
  override fun revokeDevice(deviceId: String) = commands.revokeDevice(deviceId)
  override fun lookupDevice(userCode: String) = commands.lookupDevice(userCode)
  override fun approveDevice(familyId: String, userCode: String, hubIds: List<String>?) =
    commands.approveDevice(familyId, userCode, hubIds)
  override fun denyDevice(familyId: String, userCode: String) = commands.denyDevice(familyId, userCode)
  override fun refresh() = commands.refresh()
  override fun loadHubs() = commands.loadHubs()
  override fun nowShown(subjectKeys: Set<String>) = commands.nowShown(subjectKeys)
  override fun openHubs(returnDestination: HubReturnDestination) = commands.openHubs(returnDestination)
  override fun openHub(
    familyId: String,
    hubId: String,
    focusBlockId: String?,
    returnDestination: HubReturnDestination,
  ) = commands.openHub(familyId, hubId, focusBlockId, returnDestination)
  override fun closeHub(expectedHubId: String, destination: HubReturnDestination) =
    commands.closeHub(expectedHubId, destination)
  override fun loadAudience(familyId: String, hubId: String) = commands.loadAudience(familyId, hubId)
  override fun setHubRole(familyId: String, hubId: String, userId: String, role: String) =
    commands.setHubRole(familyId, hubId, userId, role)
  override fun removeHubParticipant(familyId: String, hubId: String, userId: String) =
    commands.removeHubParticipant(familyId, hubId, userId)
  override fun setHubVisibility(familyId: String, hubId: String, visibility: String) =
    commands.setHubVisibility(familyId, hubId, visibility)
  override fun toggleItem(familyId: String, blockId: String, itemId: String, done: Boolean) =
    commands.toggleItem(familyId, blockId, itemId, done)
  override fun retryBlock(familyId: String, blockId: String) = commands.retryBlock(familyId, blockId)
  override fun deleteBlock(familyId: String, blockId: String) = commands.deleteBlock(familyId, blockId)
  override fun hideBlock(familyId: String, blockId: String) = commands.hideBlock(familyId, blockId)
  override fun unhideBlock(familyId: String, blockId: String) = commands.unhideBlock(familyId, blockId)
  override fun setNotificationConfig(config: NotifConfig) = commands.setNotificationConfig(config)
}
