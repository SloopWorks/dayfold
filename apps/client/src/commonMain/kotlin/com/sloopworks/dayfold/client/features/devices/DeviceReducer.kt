package com.sloopworks.dayfold.client

/** Connected-device list and the owner device-authorization flow. */
fun reduceDevices(state: AppState, action: Any): AppState = when (action) {
  is OpenDevices -> state
  is DevicesLoaded -> state.copy(devices = state.devices.copy(devices = action.devices, listBusy = false, listError = null, operationId = null))
  is DeviceRevoked -> state.copy(devices = state.devices.copy(devices = state.devices.devices.filterNot { it.id == action.id }, operationId = null))
  is DeviceOpRequested -> state.copy(devices = state.devices.copy(operationId = action.id))
  is DevicesRequested -> state.copy(devices = state.devices.copy(listBusy = true, listError = null))
  is DevicesFailed -> state.copy(devices = state.devices.copy(listBusy = false, listError = action.message, operationId = null))
  is OpenEnterCode -> state.copy(devices = state.devices.copy(pendingDevice = null, busy = false,
    error = null, outcome = null))
  is OpenScan -> state.copy(devices = state.devices.copy(error = null))
  is ScanPermissionGranted -> state
  is ScanPermissionDenied -> state
  is DeviceLookupRequested -> state.copy(devices = state.devices.copy(busy = true, error = null))
  is DevicePendingLoaded -> state.copy(devices = state.devices.copy(busy = false, pendingDevice = action.device,
    outcome = null, resuming = false))
  is DeviceLookupNotFound -> state.copy(devices = state.devices.copy(busy = false, pendingDevice = null,
    outcome = "expired", resuming = false))
  is DeviceLookupFailed -> state.copy(devices = state.devices.copy(busy = false, error = action.message, resuming = false))
  is ApproveDeviceRequested, is DenyDeviceRequested -> state.copy(devices = state.devices.copy(busy = true, error = null))
  is DeviceApproved -> state.copy(devices = state.devices.copy(busy = false, outcome = "approved"))
  is DeviceDenied -> state.copy(devices = state.devices.copy(busy = false, outcome = "denied"))
  is DeviceApproveExpired -> state.copy(devices = state.devices.copy(busy = false, outcome = "expired"))
  is DeviceOpFailed -> state.copy(devices = state.devices.copy(busy = false, error = action.message))
  is CloseDeviceFlow -> state.copy(devices = state.devices.copy(pendingDevice = null,
    busy = false, error = null, outcome = null, resuming = false))
  is DeviceLinkStashed -> state.copy(devices = state.devices.copy(pendingLink = action.code))
  is DeviceLinkConsumed -> state.copy(devices = state.devices.copy(pendingLink = null, resuming = true))
  else -> state
}
