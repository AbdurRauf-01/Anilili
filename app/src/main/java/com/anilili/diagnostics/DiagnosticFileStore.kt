package com.anilili.diagnostics

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, ordered JSONL writer. Each Android process owns a distinct active file while a shared
 * OS file lock makes exports and rotations safe across the app and the lightweight share process.
 */
internal class DiagnosticFileStore(
    private val directory: File,
    private val fileStem: String,
    private val maxBytes: Long = 1_000_000L,
    queueCapacity: Int = 1_024,
) {
    private sealed interface Command {
        data class WriteLine(val line: String) : Command
        data class WriteEvent(val event: DiagnosticEvent) : Command
        data class Flush(val latch: CountDownLatch) : Command
    }

    private val queue = ArrayBlockingQueue<Command>(queueCapacity)
    private val dropped = AtomicLong(0)
    private val activeFile get() = directory.resolve("$fileStem.jsonl")
    private val previousFile get() = directory.resolve("$fileStem.previous.jsonl")

    init {
        Thread(::writerLoop, "anilili-diagnostics-log").apply {
            isDaemon = true
            start()
        }
    }

    fun append(line: String) {
        val normalized = line.trimEnd('\r', '\n') + "\n"
        if (!queue.offer(Command.WriteLine(normalized))) dropped.incrementAndGet()
    }

    fun append(event: DiagnosticEvent) {
        if (!queue.offer(Command.WriteEvent(event))) dropped.incrementAndGet()
    }

    fun appendBlocking(line: String) {
        flush(250)
        val normalized = line.trimEnd('\r', '\n') + "\n"
        withFileLock {
            directory.mkdirs()
            rotateIfNeeded(normalized.toByteArray(StandardCharsets.UTF_8).size)
            activeFile.appendText(normalized, StandardCharsets.UTF_8)
        }
    }

    fun appendBlocking(event: DiagnosticEvent) {
        appendBlocking(encodeEvent(event))
    }

    fun flush(timeoutMs: Long = 2_000): Boolean {
        val latch = CountDownLatch(1)
        if (!queue.offer(Command.Flush(latch), timeoutMs, TimeUnit.MILLISECONDS)) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun droppedCount(): Long = dropped.get()

    private fun writerLoop() {
        while (true) {
            when (val command = queue.take()) {
                is Command.Flush -> command.latch.countDown()
                is Command.WriteEvent,
                is Command.WriteLine,
                -> {
                    val writes = mutableListOf(command)
                    while (writes.size < 64) {
                        val next = queue.peek()
                        if (next !is Command.WriteEvent && next !is Command.WriteLine) break
                        queue.poll()
                        writes += next
                    }
                    runCatching { writeBatch(writes) }
                        .onFailure { Log.e("DiagnosticsLog", "Could not persist diagnostic events", it) }
                }
            }
        }
    }

    private fun writeBatch(commands: List<Command>) {
        val text = buildString {
            commands.forEach { command ->
                when (command) {
                    is Command.WriteEvent -> append(encodeEvent(command.event)).append('\n')
                    is Command.WriteLine -> append(command.line)
                    is Command.Flush -> Unit
                }
            }
        }
        val incomingBytes = text.toByteArray(StandardCharsets.UTF_8).size
        withFileLock {
            directory.mkdirs()
            rotateIfNeeded(incomingBytes)
            activeFile.appendText(text, StandardCharsets.UTF_8)
        }
    }

    private fun rotateIfNeeded(incomingBytes: Int) {
        val target = activeFile
        if (!target.exists() || target.length() + incomingBytes <= maxBytes) return
        previousFile.delete()
        if (!target.renameTo(previousFile)) {
            target.copyTo(previousFile, overwrite = true)
            target.delete()
        }
    }

    private fun encodeEvent(event: DiagnosticEvent): String =
        DiagnosticEventCodec.encode(DiagnosticRedactor.redact(event))

    private inline fun <T> withFileLock(block: () -> T): T = synchronized(JVM_FILE_LOCK) {
        directory.mkdirs()
        RandomAccessFile(directory.resolve(LOCK_FILE), "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    companion object {
        private const val LOCK_FILE = "events.lock"
        private val JVM_FILE_LOCK = Any()

        fun readAllLines(directory: File): List<String> = synchronized(JVM_FILE_LOCK) {
            directory.mkdirs()
            RandomAccessFile(directory.resolve(LOCK_FILE), "rw").channel.use { channel ->
                channel.lock().use {
                    directory.listFiles()
                        .orEmpty()
                        .filter { it.isFile && it.name.startsWith("events-") && it.extension == "jsonl" }
                        .flatMap { file ->
                            runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrDefault(emptyList())
                        }
                        .filter(String::isNotBlank)
                }
            }
        }

        /** Copies a consistent, bounded event-file snapshot without retaining event contents. */
        fun snapshotEventFiles(directory: File, snapshotDirectory: File): List<File> =
            synchronized(JVM_FILE_LOCK) {
                directory.mkdirs()
                snapshotDirectory.mkdirs()
                RandomAccessFile(directory.resolve(LOCK_FILE), "rw").channel.use { channel ->
                    channel.lock().use {
                        directory.listFiles()
                            .orEmpty()
                            .filter(::isEventFile)
                            .sortedBy(File::getName)
                            .map { source ->
                                source.copyTo(snapshotDirectory.resolve(source.name), overwrite = true)
                            }
                    }
                }
            }

        /** K-way merge of process log files, retaining only one line per file in memory. */
        fun forEachOrderedLine(files: List<File>, consumer: (String) -> Unit) {
            val readers = files.sortedBy(File::getName).map { it.bufferedReader(StandardCharsets.UTF_8) }
            val queue = PriorityQueue(
                maxOf(1, files.size),
                compareBy<LineCursor> { it.timestamp }.thenBy { it.sourceIndex },
            )
            try {
                readers.forEachIndexed { index, reader ->
                    nextCursor(reader, index)?.let(queue::add)
                }
                while (queue.isNotEmpty()) {
                    val cursor = queue.remove()
                    consumer(cursor.line)
                    nextCursor(readers[cursor.sourceIndex], cursor.sourceIndex)?.let(queue::add)
                }
            } finally {
                readers.forEach { runCatching { it.close() } }
            }
        }

        private data class LineCursor(
            val timestamp: String,
            val sourceIndex: Int,
            val line: String,
        )

        private fun nextCursor(reader: java.io.BufferedReader, sourceIndex: Int): LineCursor? {
            while (true) {
                val line = reader.readLine() ?: return null
                if (line.isBlank()) continue
                val timestamp = DiagnosticEventCodec.timestampOf(line) ?: continue
                return LineCursor(timestamp, sourceIndex, line)
            }
        }

        private fun isEventFile(file: File): Boolean =
            file.isFile && file.name.startsWith("events-") && file.extension == "jsonl"
    }
}
