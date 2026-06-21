package com.sloopworks.dayfold.client

// AUTH-S5 T3 — persistence for the backend session (access + rotating refresh).
// An interface (not an `expect class`) so each platform's impl can take its own
// construction deps (Android needs a Context, iOS the Keychain) and be injected
// at the platform entrypoint (T6) — no expect/actual constructor-shape clash.
//
// Security posture: the refresh token is the long-lived secret. Desktop writes
// 0600; Android/iOS get EncryptedSharedPreferences / Keychain at T6 (a plain
// fallback there is a tracked follow, never the shipped default).
interface TokenStore {
  fun load(): Session?
  fun save(session: Session)
  fun clear()
}
