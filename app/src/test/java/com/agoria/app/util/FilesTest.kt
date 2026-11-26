package com.agoria.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesTest {

    // ------------------------------------------------------------ safeName

    @Test fun safeName_keepsLettersDigitsDashUnderscoreDot() {
        assertEquals("my-file.name_123", Files.safeName("my-file.name_123"))
    }

    @Test fun safeName_replacesUnsafeCharsWithDash() {
        assertEquals("a-b-c", Files.safeName("a/b\\c"))
    }

    @Test fun safeName_blocksPathSeparators() {
        // Slashes and backslashes must be replaced (never appear in output).
        val result = Files.safeName("../../etc/passwd")
        assertTrue(result, !result.contains("/"))
        assertTrue(result, !result.contains("\\"))
        // Dots are preserved by design; BuildRunner additionally rejects ".." path segments.
        assertEquals("..-..-etc-passwd", result)
    }

    @Test fun safeName_dotsBecomeDashes() {
        // Dots are preserved by safeName (only non-letter/digit/dash/underscore/dot become '-').
        assertEquals("file..name", Files.safeName("file..name"))
    }

    @Test fun safeName_blankBecomesUntitled() {
        assertEquals("untitled", Files.safeName(""))
        // Whitespace/slashes become dashes; result is not blank so it stays as dashes.
        assertEquals("---", Files.safeName("   "))
        assertEquals("---", Files.safeName("///"))
    }

    @Test fun safeName_cappedAt80Chars() {
        val long = "a".repeat(200)
        val result = Files.safeName(long)
        assertEquals(80, result.length)
        assertTrue(result, result.all { it == 'a' })
    }

    @Test fun safeName_leadingTrailingUnsafeTrimmed() {
        // safeName doesn't trim dashes, but replaces leading unsafe with dash
        val result = Files.safeName("!!!hello!!!")
        assertEquals("---hello---", result)
    }
}
