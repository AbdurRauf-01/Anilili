package com.miruronative.diagnostics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.miruronative.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class DiagnosticTrigger(val wireValue: String) {
    MANUAL("manual"),
    CRASH("crash"),
    SLOW_START("slow_start"),
    SHORTCUT("shortcut"),
}

sealed interface DiagnosticSendResult {
    val reportId: String?

    data class Sent(
        override val reportId: String,
        val bytes: Long,
    ) : DiagnosticSendResult

    data class Queued(
        override val reportId: String,
        val reason: String,
    ) : DiagnosticSendResult

    data class Failed(
        val reason: String,
    ) : DiagnosticSendResult {
        override val reportId: String? = null
    }

    fun userMessage(): String = when (this) {
        is Sent -> "Diagnostics sent · reference $reportId"
        is Queued -> "Saved for automatic retry · reference $reportId"
        is Failed -> reason
    }
}

/** Creates a redacted snapshot, stages it durably, uploads it, and queues transient failures. */
object DiagnosticsUploadManager {
    private const val DIRECTORY = "diagnostic_uploads"
    private const val MAX_PENDING_REPORTS = 8
    private val sending = AtomicBoolean(false)
    private val stagedName = Regex("^(ANL-[A-Z0-9-]+)--([a-z_]+)\\.zip$")

    suspend fun send(context: Context, trigger: DiagnosticTrigger): DiagnosticSendResult {
        if (BuildConfig.DIAGNOSTICS_UPLOAD_URL.isBlank()) {
            return DiagnosticSendResult.Failed("Diagnostic sending is temporarily unavailable in this build")
        }
        if (!sending.compareAndSet(false, true)) {
            return DiagnosticSendResult.Failed("A diagnostic report is already being prepared")
        }
        val app = context.applicationContext
        try {
            val staged = withContext(Dispatchers.IO) { stage(app, trigger) }
                .getOrElse { error ->
                    DiagnosticsLog.throwable("diagnostics report preparation failed", error)
                    return DiagnosticSendResult.Failed(
                        error.message ?: "Couldn't prepare diagnostics",
                    )
                }
            val reportId = staged.reportId
            DiagnosticsLog.event(
                "upload",
                "report.upload_started",
                mapOf("reportId" to reportId, "trigger" to trigger.wireValue, "bytes" to staged.file.length()),
            )
            return try {
                val receipt = DiagnosticUploadClient().upload(staged.file, reportId, trigger)
                withContext(Dispatchers.IO) { staged.file.delete() }
                DiagnosticsLog.event(
                    "upload",
                    "report.upload_succeeded",
                    mapOf("reportId" to reportId, "bytes" to receipt.receivedBytes),
                )
                DiagnosticSendResult.Sent(reportId, receipt.receivedBytes)
            } catch (error: DiagnosticUploadUnavailableException) {
                withContext(Dispatchers.IO) { staged.file.delete() }
                DiagnosticsLog.event(
                    "upload",
                    "report.upload_unavailable",
                    mapOf("reportId" to reportId),
                )
                DiagnosticSendResult.Failed(error.message.orEmpty())
            } catch (error: DiagnosticUploadHttpException) {
                DiagnosticsLog.event(
                    "upload",
                    "report.upload_rejected",
                    mapOf(
                        "reportId" to reportId,
                        "status" to error.statusCode,
                        "retryable" to error.retryable,
                    ),
                )
                if (error.retryable) {
                    enqueue(app, staged)
                    DiagnosticSendResult.Queued(reportId, error.message.orEmpty())
                } else {
                    withContext(Dispatchers.IO) { staged.file.delete() }
                    DiagnosticSendResult.Failed(error.message ?: "Diagnostic server rejected the report")
                }
            } catch (error: Throwable) {
                DiagnosticsLog.throwable("diagnostics upload deferred", error)
                enqueue(app, staged)
                DiagnosticSendResult.Queued(reportId, error.message ?: "Network unavailable")
            }
        } finally {
            sending.set(false)
        }
    }

    /** Called in the normal app process to recover reports staged by the crash-safe process. */
    fun schedulePending(context: Context) {
        val app = context.applicationContext
        runCatching {
            val directory = pendingDirectory(app)
            cleanup(directory)
            directory.listFiles()
                .orEmpty()
                .filter(File::isFile)
                .mapNotNull(::parseStaged)
                .forEach { enqueue(app, it) }
        }.onFailure { DiagnosticsLog.throwable("pending diagnostics scheduling failed", it) }
    }

    private fun stage(context: Context, trigger: DiagnosticTrigger): Result<StagedReport> = runCatching {
        val reportId = newReportId()
        val source = DiagnosticsLog.createShareSnapshot(context)
        require(source.length() in 1..DiagnosticUploadClient.MAX_COMPRESSED_BYTES) {
            "Diagnostic report exceeds the upload limit"
        }
        val directory = pendingDirectory(context)
        cleanup(directory)
        val destination = directory.resolve("$reportId--${trigger.wireValue}.zip")
        val temporary = directory.resolve("${destination.name}.tmp")
        source.inputStream().buffered().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            check(temporary.delete()) { "Couldn't finalize staged diagnostic report" }
        }
        runCatching { source.delete() }
        trimToLimit(directory)
        DiagnosticsLog.event(
            "upload",
            "report.staged",
            mapOf("reportId" to reportId, "trigger" to trigger.wireValue, "bytes" to destination.length()),
        )
        StagedReport(destination, reportId, trigger)
    }

    private fun enqueue(context: Context, staged: StagedReport): Boolean = runCatching {
        val input = Data.Builder()
            .putString(DiagnosticsUploadWorker.KEY_FILE_NAME, staged.file.name)
            .putString(DiagnosticsUploadWorker.KEY_REPORT_ID, staged.reportId)
            .putString(DiagnosticsUploadWorker.KEY_TRIGGER, staged.trigger.wireValue)
            .build()
        val request = OneTimeWorkRequestBuilder<DiagnosticsUploadWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DiagnosticsUploadWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "diagnostics-upload-${staged.reportId}",
            ExistingWorkPolicy.KEEP,
            request,
        )
        DiagnosticsLog.event(
            "upload",
            "report.retry_scheduled",
            mapOf("reportId" to staged.reportId),
        )
        true
    }.onFailure {
        // The isolated :diagnostics process intentionally does not initialize WorkManager.
        // The staged file is discovered by schedulePending() on the next normal app start.
        DiagnosticsLog.event(
            "upload",
            "report.retry_staged_for_next_start",
            mapOf("reportId" to staged.reportId, "failureType" to it.javaClass.simpleName),
        )
    }.getOrDefault(false)

    private fun parseStaged(file: File): StagedReport? {
        val match = stagedName.matchEntire(file.name) ?: return null
        val trigger = DiagnosticTrigger.entries.firstOrNull { it.wireValue == match.groupValues[2] } ?: return null
        return StagedReport(file, match.groupValues[1], trigger)
    }

    private fun pendingDirectory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    private fun cleanup(directory: File) {
        val cutoff = System.currentTimeMillis() - PENDING_REPORT_MAX_AGE_MS
        directory.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || file.lastModified() < cutoff || file.name.endsWith(".tmp")) {
                runCatching { file.delete() }
            }
        }
        trimToLimit(directory)
    }

    private fun trimToLimit(directory: File) {
        directory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
            .drop(MAX_PENDING_REPORTS)
            .forEach { runCatching { it.delete() } }
    }

    private fun newReportId(): String {
        val format = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10).uppercase(Locale.US)
        return "ANL-${format.format(Date())}-$suffix"
    }

    private data class StagedReport(
        val file: File,
        val reportId: String,
        val trigger: DiagnosticTrigger,
    )
}

class DiagnosticsUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
        val reportId = inputData.getString(KEY_REPORT_ID) ?: return Result.failure()
        val triggerValue = inputData.getString(KEY_TRIGGER) ?: return Result.failure()
        val trigger = DiagnosticTrigger.entries.firstOrNull { it.wireValue == triggerValue }
            ?: return Result.failure()
        val directory = File(applicationContext.filesDir, "diagnostic_uploads")
        val file = directory.resolve(fileName)
        if (file.parentFile?.canonicalFile != directory.canonicalFile) return Result.failure()
        if (!file.isFile) return Result.success()
        if (file.lastModified() < System.currentTimeMillis() - PENDING_REPORT_MAX_AGE_MS) {
            withContext(Dispatchers.IO) { file.delete() }
            return Result.success()
        }

        return try {
            val receipt = DiagnosticUploadClient().upload(file, reportId, trigger)
            withContext(Dispatchers.IO) { file.delete() }
            DiagnosticsLog.event(
                "upload",
                "report.retry_succeeded",
                mapOf("reportId" to reportId, "attempt" to runAttemptCount, "bytes" to receipt.receivedBytes),
            )
            Result.success()
        } catch (error: DiagnosticUploadHttpException) {
            DiagnosticsLog.event(
                "upload",
                "report.retry_http_failure",
                mapOf(
                    "reportId" to reportId,
                    "attempt" to runAttemptCount,
                    "status" to error.statusCode,
                    "retryable" to error.retryable,
                ),
            )
            if (error.retryable) Result.retry() else {
                withContext(Dispatchers.IO) { file.delete() }
                Result.failure()
            }
        } catch (error: Throwable) {
            DiagnosticsLog.throwable("diagnostics upload retry failed", error)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "diagnostics-upload"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_REPORT_ID = "report_id"
        const val KEY_TRIGGER = "trigger"
    }
}

private const val PENDING_REPORT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
