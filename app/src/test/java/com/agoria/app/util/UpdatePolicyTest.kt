package com.agoria.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdatePolicyTest {
    private val owner = "Ejthetired14"
    private val repo = "Dunder"
    private val good = "https://github.com/Ejthetired14/Dunder/releases/download/v1.0.218/app-release-signed.apk"

    @Test fun trustedReleaseUrlAccepted() {
        assertTrue(UpdatePolicy.isTrustedReleaseUrl(good, owner, repo))
        assertTrue(UpdatePolicy.isTrustedReleaseUrl(good.replace("Ejthetired14", "ejthetired14"), owner, repo))
    }

    @Test fun untrustedReleaseUrlsRejected() {
        val bad = listOf(
            good.replace("https://", "http://"),
            "https://evil.example.com/Ejthetired14/Dunder/releases/download/v1/a.apk",
            "https://github.com@evil.example.com/Ejthetired14/Dunder/releases/download/v1/a.apk",
            "https://github.com/Other/Dunder/releases/download/v1/a.apk",
            "https://github.com/Ejthetired14/Other/releases/download/v1/a.apk",
            "https://github.com/Ejthetired14/Dunder/releases/download/../a.apk",
            "https://github.com/Ejthetired14/Dunder/releases/download/v1/a.apk?x=1",
            "https://github.com/Ejthetired14/Dunder/releases/download/v1/a.apk#f",
            "https://github.com/Ejthetired14/Dunder/releases/download/v1/%2e%2e.apk",
            "https://github.com/Ejthetired14/Dunder/releases/download/v1/a.zip",
            "https://github.com/Ejthetired14/Dunder/releases/latest/download/a.apk",
            "https://github.com/Ejthetired14/Dunder/releases/download/v1/x/a.apk",
            "https://github.com:8443/Ejthetired14/Dunder/releases/download/v1/a.apk",
            "https://github.com/Ejthetired14/Dunder/releases/download/v1/a b.apk",
            "",
            "not a url",
        )
        bad.forEach { assertFalse("should reject: $it", UpdatePolicy.isTrustedReleaseUrl(it, owner, repo)) }
    }

    @Test fun downloadHostsAreAllowlisted() {
        assertTrue(UpdatePolicy.isAllowedDownloadUrl("https://github.com/a/b"))
        assertTrue(UpdatePolicy.isAllowedDownloadUrl("https://objects.githubusercontent.com/x?sig=1"))
        assertTrue(UpdatePolicy.isAllowedDownloadUrl("https://release-assets.githubusercontent.com/x?sig=1"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("http://github.com/a/b"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://evil.example.com/x"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://github.com.evil.example.com/x"))
        assertFalse(UpdatePolicy.isAllowedDownloadUrl("https://192.168.1.5/x"))
    }

    @Test fun sha256Normalizing() {
        val h = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        assertEquals(h.lowercase(), UpdatePolicy.normalizeSha256("  $h "))
        assertNull(UpdatePolicy.normalizeSha256(null))
        assertNull(UpdatePolicy.normalizeSha256(""))
        assertNull(UpdatePolicy.normalizeSha256("abc"))
        assertNull(UpdatePolicy.normalizeSha256("z".repeat(64)))
    }

    @Test fun fileHashMatchAndMismatch() {
        val f = File.createTempFile("upd", ".apk")
        try {
            f.writeText("abc")
            val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            assertTrue(UpdatePolicy.fileMatchesSha256(f, abc))
            assertTrue(UpdatePolicy.fileMatchesSha256(f, abc.uppercase()))
            assertFalse(UpdatePolicy.fileMatchesSha256(f, "0".repeat(64)))
            assertFalse(UpdatePolicy.fileMatchesSha256(f, "not-a-hash"))
        } finally {
            f.delete()
        }
    }

    @Test fun safeFileComponent() {
        assertEquals("v1.0.218", UpdatePolicy.safeFileComponent("v1.0.218"))
        assertEquals("evil", UpdatePolicy.safeFileComponent("../../evil"))
        assertEquals("release", UpdatePolicy.safeFileComponent(".."))
        assertEquals("release", UpdatePolicy.safeFileComponent(""))
        assertFalse(UpdatePolicy.safeFileComponent("a/b\\c").contains('/'))
        assertTrue(UpdatePolicy.safeFileComponent("x".repeat(500)).length <= 64)
    }

    @Test fun versionOrdering() {
        assertTrue(UpdatePolicy.isNewer("1.0.218", 218, "1.0.217", 217))
        assertFalse(UpdatePolicy.isNewer("1.0.217", 217, "1.0.217", 217))
        assertTrue(UpdatePolicy.isNewer("1.0.217", 218, "1.0.217", 217))
        assertFalse(UpdatePolicy.isNewer("1.0.216", 999, "1.0.217", 217))
        assertTrue(UpdatePolicy.isNewer("1.1", 1, "1.0.9", 9))
    }
}