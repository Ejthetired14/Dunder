package com.agoria.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SecretVaultTest {

    @Test
    fun encryptAndDecryptRoundTrip() {
        val plaintext = "my-super-secret-api-key"
        val passphrase = "correct-horse-battery-staple"

        val encrypted = SecretVault.encrypt(plaintext, passphrase)
        assertNotNull(encrypted)
        assertNotEquals(plaintext, encrypted!!)

        val decrypted = SecretVault.decrypt(encrypted!!, passphrase)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun wrongPassphraseReturnsNull() {
        val plaintext = "my-super-secret-api-key"
        val passphrase = "correct-horse-battery-staple"
        val wrongPassphrase = "wrong-passphrase"

        val encrypted = SecretVault.encrypt(plaintext, passphrase)
        assertNotNull(encrypted)

        val decrypted = SecretVault.decrypt(encrypted!!, wrongPassphrase)
        assertNull(decrypted)
    }

    @Test
    fun tamperedCiphertextReturnsNull() {
        val plaintext = "my-super-secret-api-key"
        val passphrase = "correct-horse-battery-staple"

        val encrypted = SecretVault.encrypt(plaintext, passphrase)
        assertNotNull(encrypted)

        // Flip a byte in the ciphertext portion (after salt + iv)
        val all = java.util.Base64.getDecoder().decode(encrypted!!)
        val tampered = all.copyOf(all.size)
        // Salt is 16 bytes, IV is 12 bytes, so ciphertext starts at index 28
        tampered[30] = (tampered[30].toInt() xor 0xFF).toByte()
        val tamperedBase64 = java.util.Base64.getEncoder().encodeToString(tampered)

        val decrypted = SecretVault.decrypt(tamperedBase64, passphrase)
        assertNull(decrypted)
    }

    @Test
    fun invalidBase64ReturnsNull() {
        val decrypted = SecretVault.decrypt("not-valid-base64!!!", "passphrase")
        assertNull(decrypted)
    }

    @Test
    fun emptyPassphraseCanStillEncryptDecrypt() {
        val plaintext = "test-data"
        val passphrase = ""

        val encrypted = SecretVault.encrypt(plaintext, passphrase)
        assertNotNull(encrypted)

        val decrypted = SecretVault.decrypt(encrypted!!, passphrase)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun emptyPlaintextReturnsNull() {
        val encrypted = SecretVault.encrypt("", "passphrase")
        assertNull(encrypted)
    }

    @Test
    fun encryptedOutputIsBase64() {
        val plaintext = "test"
        val passphrase = "pass"

        val encrypted = SecretVault.encrypt(plaintext, passphrase)
        assertNotNull(encrypted)

        // Should decode without error
        java.util.Base64.getDecoder().decode(encrypted!!)
    }

    @Test
    fun twoEncryptsProduceDifferentOutput() {
        val plaintext = "same-plaintext"
        val passphrase = "same-passphrase"

        val encrypted1 = SecretVault.encrypt(plaintext, passphrase)
        val encrypted2 = SecretVault.encrypt(plaintext, passphrase)

        // Random salt means different ciphertext each time
        assertNotEquals(encrypted1, encrypted2)

        // But both decrypt to the same plaintext
        assertEquals(plaintext, SecretVault.decrypt(encrypted1!!, passphrase))
        assertEquals(plaintext, SecretVault.decrypt(encrypted2!!, passphrase))
    }

    @Test
    fun secretsSectionRoundTripInSnapshot() {
        // Verify that a secrets JSON section can be encrypted and decrypted as a unit
        val secrets = JSONObject()
        secrets.put("cloudflare_workers", "enc-api-token-123")
        secrets.put("slack", "enc-webhook-url-456")

        val secretsText = secrets.toString()
        val passphrase = "vault-passphrase"

        val encrypted = SecretVault.encrypt(secretsText, passphrase)
        assertNotNull(encrypted)

        val decryptedText = SecretVault.decrypt(encrypted!!, passphrase)
        assertEquals(secretsText, decryptedText)

        val roundTrip = JSONObject(decryptedText)
        assertEquals("enc-api-token-123", roundTrip.getString("cloudflare_workers"))
        assertEquals("enc-webhook-url-456", roundTrip.getString("slack"))
    }
}
