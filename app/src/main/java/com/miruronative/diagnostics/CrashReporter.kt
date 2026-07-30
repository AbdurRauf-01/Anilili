package com.miruronative.diagnostics

import android.content.Context
import android.os.Build
import com.miruronative.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Zero-dependency crash capture for sideload builds, where there is no Play Console to collect
 * traces. Fatal crashes (and important non-fatal failures) are written to a file in [Context.getFilesDir];
 * the next launch shows them in a copyable dialog so remote users can paste the trace into a report.
 */
object CrashReporter {
    private const val MAX_LOG_BYTES = 200_000L
    @Volatile private var logFile: File? = null
    private val installed = AtomicBoolean(false)
    private val clearGeneration = AtomicLong(0)
    private val fileLock = Any()
    private val nonFatalWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "anilili-nonfatal-crash-log").apply { isDaemon = true }
    }

    fun init(context: Context) {
        val file = File(context.filesDir, "last_crash.txt")
        logFile = file
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                appendAndSync(file, entry("FATAL on thread ${thread.name}", throwable), fatal = true)
                DiagnosticsLog.fatal("FATAL on thread ${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Records a survivable failure that should still surface in the crash dialog next launch. */
    fun logNonFatal(what: String, throwable: Throwable) {
        val file = logFile ?: return
        DiagnosticsLog.throwable("NON-FATAL: $what", throwable)
        val generation = clearGeneration.get()
        nonFatalWriter.execute {
            runCatching {
                val report = entry("NON-FATAL: $what", throwable)
                appendAndSync(file, report, fatal = false, expectedGeneration = generation)
            }
        }
    }

    /** The pending report from an earlier run, or null when the last run was clean. */
    fun pendingReport(): String? = synchronized(fileLock) {
        logFile
            ?.takeIf { it.exists() && it.length() > 0 }
            ?.runCatching { readText() }
            ?.getOrNull()
    }

    fun clear() {
        synchronized(fileLock) {
            clearGeneration.incrementAndGet()
            runCatching { logFile?.delete() }
        }
    }

    private fun entry(headline: String, throwable: Throwable): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("== $headline ==")
            appendLine("time: $time")
            appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
            appendLine("build: ${BuildConfig.GIT_SHA}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine(trace)
            appendLine()
        }
    }

    private fun appendAndSync(
        file: File,
        text: String,
        fatal: Boolean,
        expectedGeneration: Long? = null,
    ) = synchronized(fileLock) {
        if (expectedGeneration != null && expectedGeneration != clearGeneration.get()) return@synchronized
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (!fatal && file.length() + bytes.size > MAX_LOG_BYTES) return@synchronized
        if (fatal && file.length() + bytes.size > MAX_LOG_BYTES) {
            val previous = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
            val keep = previous.takeLast((MAX_LOG_BYTES / 2).toInt()).toByteArray()
            FileOutputStream(file, false).use { output ->
                output.write(keep)
                output.write(bytes)
                output.fd.sync()
            }
            return@synchronized
        }
        FileOutputStream(file, true).use { output ->
            output.write(bytes)
            if (fatal) output.fd.sync()
        }
    }
}
