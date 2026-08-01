package com.anilili.diagnostics

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
import com.anilili.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
        is Queued -> "Diagnostics queued for secure upload · reference $reportId"
        is Failed -> reason
    }
}

/** Creates a redacted snapshot, stages it durably, uploads it, and queues transient failures. */
object DiagnosticsUploadManager {
    private const val DIRECTORY = "diagnostic_uploads"
    private const val MAX_PENDING_REPORTS = 8
    private val sending = AtomicBoolean(false)
    private val stagedName = Regex("^(ANL-[A-Z0-9-]+)--([a-z_]+)\\.zip$")

    suspend fun send(
        context: Context,
        trigger: DiagnosticTrigger,
        submission: DiagnosticSubmissionInput = DiagnosticSubmissionInput(),
    ): DiagnosticSendResult {
        if (BuildConfig.DIAGNOSTICS_UPLOAD_URL.isBlank()) {
            return DiagnosticSendResult.Failed("Diagnostic sending is temporarily unavailable in this build")
        }
        if (!sending.compareAndSet(false, true)) {
            return DiagnosticSendResult.Failed("A diagnostic report is already being prepared")
        }
        val app = context.applicationContext
        try {
            // Consent has already been given. Finish the small local staging operation even if the
            // Compose screen leaves; the potentially slow network upload belongs to WorkManager.
            val staged = withContext(NonCancellable + Dispatchers.IO) {
                stage(app, trigger, submission)
            }
                .getOrElse { error ->
                    DiagnosticsLog.throwable("diagnostics report preparation failed", error)
                    return DiagnosticSendResult.Failed(
                        error.message ?: "Couldn't prepare diagnostics",
                    )
                }
            val reportId = staged.reportId
            val scheduled = enqueue(app, staged)
            return DiagnosticSendResult.Queued(
                reportId,
                if (scheduled) "Upload scheduled" else "Upload staged for the next app start",
            )
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

    private fun stage(
        context: Context,
        trigger: DiagnosticTrigger,
        submission: DiagnosticSubmissionInput,
    ): Result<StagedReport> {
        var source: File? = null
        var destination: File? = null
        return runCatching {
            val reportId = newReportId()
            source = DiagnosticsLog.createShareSnapshot(context)
            require(source!!.length() in 1..DiagnosticUploadClient.MAX_COMPRESSED_BYTES) {
                "Diagnostic report exceeds the upload limit"
            }
            val directory = pendingDirectory(context)
            cleanup(directory)
            destination = directory.resolve("$reportId--${trigger.wireValue}.zip")
            val finalDestination = destination!!
            val temporary = directory.resolve("${finalDestination.name}.tmp")
            val description = DiagnosticSubmissionPolicy.normalizeDescription(submission.description)
            val descriptionFile = finalDestination.descriptionFile()
            val screenshotFile = finalDestination.screenshotFile()
            runCatching { descriptionFile.delete() }
            runCatching { screenshotFile.delete() }
            if (description.isNotBlank()) {
                descriptionFile.writeText(description, Charsets.UTF_8)
            }
            val screenshot = submission.screenshotUri?.let { uri ->
                copyScreenshot(context, uri, screenshotFile)
            }
            source!!.inputStream().buffered().use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (!temporary.renameTo(finalDestination)) {
                temporary.copyTo(finalDestination, overwrite = true)
                check(temporary.delete()) { "Couldn't finalize staged diagnostic report" }
            }
            trimToLimit(directory)
            DiagnosticsLog.event(
                "upload",
                "report.staged",
                mapOf("reportId" to reportId, "trigger" to trigger.wireValue, "bytes" to finalDestination.length()),
            )
            StagedReport(finalDestination, reportId, trigger, description, screenshot)
        }.also {
            runCatching { source?.delete() }
        }.onFailure {
            // Any final ZIP is a complete bundle marker. Sidecars without it are ignored and cleaned.
            destination?.let(::deleteBundle)
            val directory = pendingDirectory(context)
            directory.listFiles().orEmpty()
                .filter { file -> file.name.endsWith(".tmp") }
                .forEach { file -> runCatching { file.delete() } }
        }
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
            "report.upload_scheduled",
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
        val description = file.descriptionFile()
            .takeIf(File::isFile)
            ?.readText(Charsets.UTF_8)
            ?.let(DiagnosticSubmissionPolicy::normalizeDescription)
            .orEmpty()
        val screenshot = file.screenshotFile()
            .takeIf(File::isFile)
            ?.let(::readScreenshot)
        return StagedReport(file, match.groupValues[1], trigger, description, screenshot)
    }

    private fun pendingDirectory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    private fun cleanup(directory: File) {
        val cutoff = System.currentTimeMillis() - PENDING_REPORT_MAX_AGE_MS
        directory.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || file.name.endsWith(".tmp")) {
                runCatching { file.delete() }
            }
        }
        directory.listFiles().orEmpty()
            .filter { stagedName.matches(it.name) && it.lastModified() < cutoff }
            .forEach(::deleteBundle)
        val bundlePrefixes = directory.listFiles().orEmpty()
            .filter { stagedName.matches(it.name) }
            .map { it.name.removeSuffix(".zip") }
            .toSet()
        directory.listFiles().orEmpty()
            .filter { file ->
                (file.name.endsWith(DESCRIPTION_SUFFIX) || file.name.endsWith(SCREENSHOT_SUFFIX)) &&
                    bundlePrefixes.none { prefix -> file.name == "$prefix$DESCRIPTION_SUFFIX" ||
                        file.name == "$prefix$SCREENSHOT_SUFFIX" }
            }
            .forEach { runCatching { it.delete() } }
        trimToLimit(directory)
    }

    private fun trimToLimit(directory: File) {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && stagedName.matches(it.name) }
            .sortedByDescending(File::lastModified)
            .drop(MAX_PENDING_REPORTS)
            .forEach(::deleteBundle)
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
        val description: String,
        val screenshot: DiagnosticScreenshot?,
    )

    private fun copyScreenshot(context: Context, uri: android.net.Uri, destination: File): DiagnosticScreenshot {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        var total = 0L
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            temporary.outputStream().buffered().use { output ->
                val chunk = ByteArray(16 * 1_024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    require(total <= DiagnosticSubmissionPolicy.MAX_SCREENSHOT_BYTES) {
                        "Screenshot is larger than 5 MB"
                    }
                    output.write(chunk, 0, read)
                }
            }
        } ?: error("Couldn't open the selected screenshot")
        require(total > 0L) { "The selected screenshot is empty" }
        val contentType = temporary.inputStream().buffered().use { input ->
            val header = ByteArray(12)
            val read = input.read(header)
            DiagnosticSubmissionPolicy.screenshotContentType(header.copyOf(maxOf(0, read)))
        } ?: error("Choose a JPEG, PNG or WebP screenshot")
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            check(temporary.delete()) { "Couldn't finalize the selected screenshot" }
        }
        return DiagnosticScreenshot(destination, contentType)
    }

    private fun readScreenshot(file: File): DiagnosticScreenshot? = runCatching {
        require(file.length() in 1..DiagnosticSubmissionPolicy.MAX_SCREENSHOT_BYTES)
        val contentType = file.inputStream().buffered().use { input ->
            val header = ByteArray(12)
            val read = input.read(header)
            DiagnosticSubmissionPolicy.screenshotContentType(header.copyOf(maxOf(0, read)))
        } ?: return@runCatching null
        DiagnosticScreenshot(file, contentType)
    }.getOrNull()

    private fun File.descriptionFile(): File =
        File(parentFile, "${name.removeSuffix(".zip")}$DESCRIPTION_SUFFIX")

    private fun File.screenshotFile(): File =
        File(parentFile, "${name.removeSuffix(".zip")}$SCREENSHOT_SUFFIX")

    private fun deleteBundle(zip: File) {
        listOf(zip, zip.descriptionFile(), zip.screenshotFile()).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private const val DESCRIPTION_SUFFIX = ".description.txt"
    private const val SCREENSHOT_SUFFIX = ".screenshot"
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
            withContext(Dispatchers.IO) { deleteStagedBundle(file) }
            return Result.success()
        }
        val description = stagedDescription(file)
        val screenshot = stagedScreenshot(file)

        return try {
            DiagnosticsLog.event(
                "upload",
                "report.upload_started",
                mapOf("reportId" to reportId, "trigger" to trigger.wireValue, "bytes" to file.length()),
            )
            val receipt = DiagnosticUploadClient().upload(
                file,
                reportId,
                trigger,
                description,
                screenshot,
            )
            withContext(Dispatchers.IO) { deleteStagedBundle(file) }
            DiagnosticsLog.event(
                "upload",
                "report.upload_succeeded",
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
                withContext(Dispatchers.IO) { deleteStagedBundle(file) }
                Result.failure()
            }
        } catch (error: CancellationException) {
            throw error
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
private const val STAGED_DESCRIPTION_SUFFIX = ".description.txt"
private const val STAGED_SCREENSHOT_SUFFIX = ".screenshot"

private fun stagedSidecar(zip: File, suffix: String): File =
    File(zip.parentFile, "${zip.name.removeSuffix(".zip")}$suffix")

private fun stagedDescription(zip: File): String =
    stagedSidecar(zip, STAGED_DESCRIPTION_SUFFIX)
        .takeIf(File::isFile)
        ?.readText(Charsets.UTF_8)
        ?.let(DiagnosticSubmissionPolicy::normalizeDescription)
        .orEmpty()

private fun stagedScreenshot(zip: File): DiagnosticScreenshot? {
    val file = stagedSidecar(zip, STAGED_SCREENSHOT_SUFFIX).takeIf(File::isFile) ?: return null
    if (file.length() !in 1..DiagnosticSubmissionPolicy.MAX_SCREENSHOT_BYTES) return null
    val contentType = runCatching {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(12)
            val read = input.read(header)
            DiagnosticSubmissionPolicy.screenshotContentType(header.copyOf(maxOf(0, read)))
        }
    }.getOrNull() ?: return null
    return DiagnosticScreenshot(file, contentType)
}

private fun deleteStagedBundle(zip: File) {
    listOf(
        zip,
        stagedSidecar(zip, STAGED_DESCRIPTION_SUFFIX),
        stagedSidecar(zip, STAGED_SCREENSHOT_SUFFIX),
    ).forEach { file -> runCatching { file.delete() } }
}
