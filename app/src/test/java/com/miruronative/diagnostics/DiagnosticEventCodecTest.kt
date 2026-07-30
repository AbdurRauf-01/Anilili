package com.miruronative.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticEventCodecTest {
    @Test
    fun `structured event round trips with schema and correlation fields`() {
        val event = DiagnosticEvent(
            timestampUtc = "2026-07-29T12:00:00.000Z",
            elapsedRealtimeMs = 200,
            processUptimeMs = 100,
            sessionId = "session",
            sequence = 7,
            process = "com.miruronative",
            pid = 123,
            thread = "main",
            threadId = 1,
            level = "INFO",
            category = "playback",
            name = "session.summary",
            attributes = mapOf("rebufferCount" to "2"),
        )

        val decoded = DiagnosticEventCodec.decode(DiagnosticEventCodec.encode(event))

        assertEquals(DIAGNOSTIC_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals("session", decoded.sessionId)
        assertEquals(7, decoded.sequence)
        assertEquals("2", decoded.attributes["rebufferCount"])
    }
}
