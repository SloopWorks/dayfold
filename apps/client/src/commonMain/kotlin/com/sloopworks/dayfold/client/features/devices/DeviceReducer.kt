package com.sloopworks.dayfold.client

/** Connected-device list and the owner device-authorization flow. */
fun reduceDevices(state: AppState, action: Any): AppState = when (action) {
  is OpenDevices -> state
  is DevicesLoaded -> state.copy(devices = action.devices, deviceListBusy = false, deviceListError = null, deviceOpId = null)
  is DeviceRevoked -> state.copy(devices = state.devices.filterNot { it.id == action.id }, deviceOpId = null)
  is DeviceOpRequested -> state.copy(deviceOpId = action.id)
  is DevicesRequested -> state.copy(deviceListBusy = true, deviceListError = null)
  is DevicesFailed -> state.copy(deviceListBusy = false, deviceListError = action.message, deviceOpId = null)
  is OpenEnterCode -> state.copy(pendingDevice = null, deviceBusy = false,
    deviceError = null, deviceOutcome = null)
  is OpenScan -> state.copy(deviceError = null)
  is ScanPermissionGranted -> state
  is ScanPermissionDenied -> state
  is DeviceLookupRequested -> state.copy(deviceBusy = true, deviceError = null)
  is DevicePendingLoaded -> state.copy(deviceBusy = false, pendingDevice = action.device,
    deviceOutcome = null, deviceResuming = false)
  is DeviceLookupNotFound -> state.copy(deviceBusy = false, pendingDevice = null,
    deviceOutcome = "expired", deviceResuming = false)
  is DeviceLookupFailed -> state.copy(deviceBusy = false, deviceError = action.message, deviceResuming = false)
  is ApproveDeviceRequested, is DenyDeviceRequested -> state.copy(deviceBusy = true, deviceError = null)
  is DeviceApproved -> state.copy(deviceBusy = false, deviceOutcome = "approved")
  is DeviceDenied -> state.copy(deviceBusy = false, deviceOutcome = "denied")
  is DeviceApproveExpired -> state.copy(deviceBusy = false, deviceOutcome = "expired")
  is DeviceOpFailed -> state.copy(deviceBusy = false, deviceError = action.message)
  is CloseDeviceFlow -> state.copy(pendingDevice = null,
    deviceBusy = false, deviceError = null, deviceOutcome = null, deviceResuming = false)
  is DeviceLinkStashed -> state.copy(pendingDeviceLink = action.code)
  is DeviceLinkConsumed -> state.copy(pendingDeviceLink = null, deviceResuming = true)
  is InviteLinkStashed -> state.copy(pendingInviteLink = action.token)
  is InviteLinkConsumed -> state.copy(pendingInviteLink = null)
  else -> state
}
