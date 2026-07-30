package com.miruronative.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DiagnosticFileStoreTest {
    @Test
    fun `flush makes queued events visible to export`() {
        val directory = Files.createTempDirectory("diagnostic-store").toFile()
        val store = DiagnosticFileStore(directory, "events-test")

        store.append("{\"event\":1}")
        store.append("{\"event\":2}")

        assertTrue(store.flush())
        assertEquals(
            listOf("{\"event\":1}", "{\"event\":2}"),
            DiagnosticFileStore.readAllLines(directory),
        )
    }

    @Test
    fun `rotation preserves complete json lines`() {
        val directory = Files.createTempDirectory("diagnostic-rotation").toFile()
        val store = DiagnosticFileStore(directory, "events-test", maxBytes = 45)
        val first = "{\"event\":\"${"a".repeat(30)}\"}"
        val second = "{\"event\":\"${"b".repeat(30)}\"}"

        store.append(first)
        assertTrue(store.flush())
        store.append(second)
        assertTrue(store.flush())

        val lines = DiagnosticFileStore.readAllLines(directory)
        assertEquals(setOf(first, second), lines.toSet())
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") })
    }

    @Test
    fun `structured events are encoded and redacted on the writer thread`() {
        val directory = Files.createTempDirectory("diagnostic-event-store").toFile()
        val store = DiagnosticFileStore(directory, "events-main")

        store.append(event("2026-07-30T12:00:00.000Z", "secret", message = "token=private-value"))

        assertTrue(store.flush())
        val decoded = DiagnosticEventCodec.decode(DiagnosticFileStore.readAllLines(directory).single())
        assertFalse(decoded.message.orEmpty().contains("private-value"))
        assertTrue(decoded.message.orEmpty().contains("<redacted>"))
    }

    @Test
    fun `snapshotted process files stream in timestamp order`() {
        val directory = Files.createTempDirectory("diagnostic-ordered-store").toFile()
        val snapshot = Files.createTempDirectory("diagnostic-ordered-snapshot").toFile()
        val main = DiagnosticFileStore(directory, "events-main")
        val service = DiagnosticFileStore(directory, "events-service")
        main.append(event("2026-07-30T12:00:00.000Z", "first"))
        main.append(event("2026-07-30T12:00:02.000Z", "third"))
        service.append(event("2026-07-30T12:00:01.000Z", "second"))
        assertTrue(main.flush())
        assertTrue(service.flush())

        val names = mutableListOf<String>()
        val files = DiagnosticFileStore.snapshotEventFiles(directory, snapshot)
        DiagnosticFileStore.forEachOrderedLine(files) { line ->
            names += DiagnosticEventCodec.decode(line).name
        }

        assertEquals(listOf("first", "second", "third"), names)
    }

    private fun event(timestamp: String, name: String, message: String? = null) = DiagnosticEvent(
        timestampUtc = timestamp,
        elapsedRealtimeMs = 1,
        processUptimeMs = 1,
        sessionId = "session",
        sequence = 1,
        process = "process",
        pid = 1,
        thread = "thread",
        threadId = 1,
        level = "INFO",
        category = "test",
        name = name,
        message = message,
    )
}
