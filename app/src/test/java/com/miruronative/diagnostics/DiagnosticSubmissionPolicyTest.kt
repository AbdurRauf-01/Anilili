package com.miruronative.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticSubmissionPolicyTest {
    @Test
    fun `description is bounded and passes through the central redactor`() {
        val description = "Playback failed. password=hunter2 user@example.com " +
            "x".repeat(DiagnosticSubmissionPolicy.MAX_DESCRIPTION_CHARS)

        val normalized = DiagnosticSubmissionPolicy.normalizeDescription(description)

        assertEquals(DiagnosticSubmissionPolicy.MAX_DESCRIPTION_CHARS, normalized.length)
        assertEquals(false, "hunter2" in normalized)
        assertEquals(false, "user@example.com" in normalized)
    }

    @Test
    fun `screenshot format is detected from bytes rather than filename`() {
        assertEquals(
            "image/png",
            DiagnosticSubmissionPolicy.screenshotContentType(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            ),
        )
        assertEquals(
            "image/jpeg",
            DiagnosticSubmissionPolicy.screenshotContentType(
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
            ),
        )
        assertEquals(
            "image/webp",
            DiagnosticSubmissionPolicy.screenshotContentType("RIFF0000WEBP".toByteArray()),
        )
        assertNull(DiagnosticSubmissionPolicy.screenshotContentType("not-an-image".toByteArray()))
    }
}
