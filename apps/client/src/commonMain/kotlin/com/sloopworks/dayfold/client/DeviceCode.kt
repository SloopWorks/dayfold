package com.sloopworks.dayfold.client

// Device user_code formatting helpers. Pure logic (no Compose) → stay in :client.
// public: consumed cross-module by :ui (DeviceApprovalScreens) AND by staying
// DeepLink.kt in :client. Extracted from DeviceApprovalScreens.kt in P2.2a because
// :client cannot depend on :ui, yet DeepLink (staying) needs them.
const val CODE_LEN = 8   // user_code is 8 chars rendered XXXX-XXXX

// Normalize free input → 8 uppercased alphanumerics (drop the dash + noise).
fun normalizeDeviceCode(raw: String): String =
  raw.uppercase().filter { it.isLetterOrDigit() }.take(CODE_LEN)

// 8 alnum chars → the user_code the server expects (WDJF-7K2P).
fun formatUserCode(code: String): String =
  if (code.length == CODE_LEN) "${code.take(4)}-${code.drop(4)}" else code
