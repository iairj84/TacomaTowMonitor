package com.tacomatow.monitor.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `parses SHA-256 from release notes`() {
        val sha = "4d0a677d47324d653b08120e122d4bff1a4200167f31df2988530bdc98af0b94"
        val notes = "v0.9.17 — tow service\n\nSHA-256: $sha"
        assertEquals(sha, UpdateChecker.parseSha256(notes))
    }

    @Test
    fun `missing hash returns null instead of silently passing`() {
        assertNull(UpdateChecker.parseSha256("v0.9.17 — tow service\n\nNo hash here."))
    }

    @Test
    fun `sha256Hex matches known digest`() {
        // SHA-256("abc") from FIPS 180-4.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateChecker.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `hash comparison is case-insensitive`() {
        val sha = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
        assertEquals(sha.lowercase(), UpdateChecker.parseSha256("SHA-256: $sha"))
    }
}