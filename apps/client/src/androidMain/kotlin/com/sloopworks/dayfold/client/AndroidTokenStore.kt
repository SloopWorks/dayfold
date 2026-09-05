package com.sloopworks.dayfold.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/**
 * Stores the complete backend session encrypted by a non-exportable Android Keystore key.
 *
 * The preferences file contains only versioned AES-GCM ciphertext. A one-time migration accepts
 * the old private-preferences JSON, immediately encrypts it, and overwrites the plaintext value.
 */
class AndroidTokenStore(
  context: Context,
  private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStore {
  private val prefs = context.getSharedPreferences("dayfold_session", Context.MODE_PRIVATE)
  private val preferenceKey = "session"
  private val keyAlias = "com.sloopworks.dayfold.session.v1"

  override fun load(): Session? {
    val stored = prefs.getString(preferenceKey, null) ?: return null
    val encoded = if (stored.startsWith(CIPHERTEXT_PREFIX)) {
      runCatching { decrypt(stored.removePrefix(CIPHERTEXT_PREFIX)) }.getOrNull() ?: return null
    } else {
      // Pre-release migration: the prior implementation stored the JSON directly.
      stored
    }
    val session = runCatching { json.decodeFromString(Session.serializer(), encoded) }.getOrNull() ?: return null
    if (!stored.startsWith(CIPHERTEXT_PREFIX)) save(session)
    return session
  }

  override fun save(session: Session) {
    val plaintext = json.encodeToString(Session.serializer(), session)
    prefs.edit().putString(preferenceKey, CIPHERTEXT_PREFIX + encrypt(plaintext)).commit()
  }

  override fun clear() {
    prefs.edit().remove(preferenceKey).commit()
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
  }

  private fun secretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
      init(
        KeyGenParameterSpec.Builder(
          keyAlias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setRandomizedEncryptionRequired(true)
          .build(),
      )
      generateKey()
    }
  }

  private fun encrypt(plaintext: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val encrypted = cipher.doFinal(plaintext.encodeToByteArray())
    val envelope = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
    return Base64.encodeToString(envelope, Base64.NO_WRAP)
  }

  private fun decrypt(encoded: String): String {
    val envelope = Base64.decode(encoded, Base64.NO_WRAP)
    require(envelope.isNotEmpty())
    val ivSize = envelope[0].toInt() and 0xff
    require(ivSize in 12..16 && envelope.size > 1 + ivSize)
    val iv = envelope.copyOfRange(1, 1 + ivSize)
    val ciphertext = envelope.copyOfRange(1 + ivSize, envelope.size)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertext).decodeToString()
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val CIPHERTEXT_PREFIX = "v2:"
  }
}
