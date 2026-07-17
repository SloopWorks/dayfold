package com.sloopworks.dayfold.client

/**
 * UI-facing commands that may cross a Compose host boundary.
 *
 * The port exposes user intents, not the Redux store, engines, coroutine scopes, or mutable runtime
 * state. Implementations must have stable identity and capture every argument at the call edge before
 * starting asynchronous work. Platform-only authentication and deep-link admission stay on
 * [DayfoldCommands], outside this UI port.
 */
interface DayfoldCommandPort {
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
