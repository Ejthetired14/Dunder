package com.agoria.app.util

import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SEC-16: optional passphrase-protected encryption for the secrets section of an export.
 *
 * Uses PBKDF2WithHmacSHA256 (100,000 iterations, random 16-byte salt) to derive a key from the
 * user-supplied passphrase, then AES-256-GCM to encrypt. The passphrase is never stored, never
 * logged, and never written to disk. A wrong passphrase or tampered ciphertext fails closed
 * (decryption returns null).
 *
 * Format: base64(salt || iv || ciphertext || tag)
 */
object SecretVault {

    private const val ITERATIONS = 100_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    /**
     * Encrypts `plain` with a key derived from `passphrase`. Returns null on any failure.
     */
    fun encrypt(plain: String, passphrase: String): String? {
        return try {
            val salt = ByteArray(SALT_BYTES)
            random.nextBytes(salt)
            val key = deriveKey(passphrase.toCharArray(), salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(salt.size + iv.size + encrypted.size)
            System.arraycopy(salt, 0, out, 0, salt.size)
            System.arraycopy(iv, 0, out, salt.size, iv.size)
            System.arraycopy(encrypted, 0, out, salt.size + iv.size, encrypted.size)
            Base64.getEncoder().encodeToString(out)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decrypts `stored` with a key derived from `passphrase`. Returns null if the passphrase is
     * wrong, the ciphertext is tampered, or the format is invalid.
     */
    fun decrypt(stored: String, passphrase: String): String? {
        return try {
            val all = Base64.getDecoder().decode(stored)
            val minLen = SALT_BYTES + IV_BYTES + TAG_BITS / 8
            if (all.size < minLen) return null
            val salt = all.copyOfRange(0, SALT_BYTES)
            val iv = all.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
            val encrypted = all.copyOfRange(SALT_BYTES + IV_BYTES, all.size)
            val key = deriveKey(passphrase.toCharArray(), salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
}
