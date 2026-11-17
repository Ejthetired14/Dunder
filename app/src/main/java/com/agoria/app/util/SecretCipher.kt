package com.agoria.app.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (provider API keys, connector tokens) with a key that never leaves
 * the Android Keystore. The stored value is base64(iv || ciphertext || tag).
 */
object SecretCipher {

    private const val ALIAS = "agoria_secret_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String?): String? {
        if (plain.isNullOrBlank()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(encrypted, 0, out, iv.size, encrypted.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        return try {
            val all = Base64.decode(stored, Base64.NO_WRAP)
            if (all.size <= IV_BYTES) return null
            val iv = all.copyOfRange(0, IV_BYTES)
            val encrypted = all.copyOfRange(IV_BYTES, all.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}