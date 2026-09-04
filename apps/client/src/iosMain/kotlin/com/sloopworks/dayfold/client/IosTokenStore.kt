package com.sloopworks.dayfold.client

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSCopyingProtocol
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Stores Dayfold's rotating backend session in the iOS Keychain, never in a plist. */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class IosTokenStore(
  private val json: Json = Json { ignoreUnknownKeys = true },
  private val key: String = "dayfold_session",
) : TokenStore {
  override fun load(): Session? {
    val encoded = read() ?: return null
    return runCatching { json.decodeFromString(Session.serializer(), encoded) }.getOrNull()
  }

  override fun save(session: Session) {
    val encoded = json.encodeToString(Session.serializer(), session)
    delete()
    val data = NSString.create(string = encoded).dataUsingEncoding(NSUTF8StringEncoding)
      ?: error("Unable to encode Dayfold session")
    val query = baseQuery().apply {
      set(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
      set(kSecValueData, data)
    }
    val status = query.withSecurityDictionary { SecItemAdd(it, null) }
    check(status == errSecSuccess || status == errSecDuplicateItem) {
      "Unable to store Dayfold session in Keychain (OSStatus $status)"
    }
  }

  override fun clear() {
    delete()
  }

  private fun read(): String? = memScoped {
    val query = baseQuery().apply {
      set(kSecReturnData, true)
      set(kSecMatchLimit, kSecMatchLimitOne)
    }
    val result = alloc<CFTypeRefVar>()
    val status = query.withSecurityDictionary { SecItemCopyMatching(it, result.ptr) }
    if (status == errSecItemNotFound) return@memScoped null
    check(status == errSecSuccess) { "Unable to read Dayfold session from Keychain (OSStatus $status)" }
    val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
    NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
  }

  private fun delete() {
    val status = baseQuery().withSecurityDictionary { SecItemDelete(it) }
    check(status == errSecSuccess || status == errSecItemNotFound) {
      "Unable to clear Dayfold session from Keychain (OSStatus $status)"
    }
  }

  private fun baseQuery() = NSMutableDictionary().apply {
    set(kSecClass, kSecClassGenericPassword)
    set(kSecAttrService, SERVICE)
    set(kSecAttrAccount, ACCOUNT)
  }

  private fun NSMutableDictionary.set(key: Any?, value: Any?) {
    setObject(value ?: error("Keychain value is unavailable"), forKey = key as NSCopyingProtocol)
  }

  private inline fun <T> NSMutableDictionary.withSecurityDictionary(block: (CFDictionaryRef) -> T): T {
    val retained = CFBridgingRetain(this) ?: error("Unable to bridge Keychain query")
    return try {
      block(retained.reinterpret())
    } finally {
      CFRelease(retained)
    }
  }

  private companion object {
    const val SERVICE = "com.sloopworks.dayfold.session"
    const val ACCOUNT = "backend-session"
  }
}
