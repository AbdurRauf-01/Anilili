package com.miruronative.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun `urls retain only origin`() {
        val result = DiagnosticRedactor.redactText(
            "load https://user:pass@cdn.example.com:8443/private/master.m3u8?token=secret#part",
        )

        assertTrue(result.contains("https://cdn.example.com:8443/<redacted>"))
        assertFalse(result.contains("private"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("user:pass"))
    }

    @Test
    fun `assignments credentials and personal text are removed`() {
        val result = DiagnosticRedactor.redactText(
            "query=One Piece title='Private title' token=abc Bearer xyz.123 email=test@example.com " +
                "json={\"access_token\":\"json-secret\",\"body\":\"private-body\"}",
        )

        assertFalse(result.contains("One Piece"))
        assertFalse(result.contains("Private title"))
        assertFalse(result.contains("abc"))
        assertFalse(result.contains("xyz.123"))
        assertFalse(result.contains("test@example.com"))
        assertFalse(result.contains("json-secret"))
        assertFalse(result.contains("private-body"))
        assertTrue(result.contains("query=<redacted>"))
    }

    @Test
    fun `structured attributes redact by key but keep useful host and timing`() {
        val result = DiagnosticRedactor.redactAttributes(
            mapOf(
                "token" to "secret",
                "url" to "https://cdn.example/private",
                "host" to "cdn.example",
                "durationMs" to 42,
            ),
        )

        assertEquals("<redacted>", result["token"])
        assertEquals("<redacted>", result["url"])
        assertEquals("cdn.example", result["host"])
        assertEquals("42", result["durationMs"])
    }

    @Test
    fun `complete structured events are redacted before persistence`() {
        val event = DiagnosticEvent(
            timestampUtc = "2026-07-30T12:00:00.000Z",
            elapsedRealtimeMs = 1,
            processUptimeMs = 1,
            sessionId = "session",
            sequence = 1,
            process = "process",
            pid = 1,
            thread = "thread",
            threadId = 1,
            level = "ERROR",
            category = "test",
            name = "redaction",
            message = "url=https://example.com/private?token=secret",
            attributes = mapOf("query" to "private search", "host" to "example.com"),
            exception = DiagnosticException(
                type = "Example",
                message = "Bearer private-token",
                stackTrace = "at https://example.com/private/path",
            ),
        )

        val redacted = DiagnosticRedactor.redact(event)

        assertFalse(redacted.message.orEmpty().contains("private"))
        assertEquals("<redacted>", redacted.attributes["query"])
        assertEquals("example.com", redacted.attributes["host"])
        assertFalse(redacted.exception?.message.orEmpty().contains("private-token"))
        assertFalse(redacted.exception?.stackTrace.orEmpty().contains("private/path"))
    }
}
