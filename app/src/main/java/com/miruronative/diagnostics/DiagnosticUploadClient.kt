package com.miruronative.diagnostics

import com.miruronative.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DiagnosticUploadReceipt(
    val reportId: String,
    val receivedBytes: Long,
)

class DiagnosticUploadHttpException(
    val statusCode: Int,
    val retryable: Boolean,
    message: String,
) : IOException(message)

class DiagnosticUploadUnavailableException : IOException(
    "Diagnostic sending is temporarily unavailable in this build",
)

/** Minimal, independent HTTP client so the crash-safe diagnostics process does not boot AppGraph. */
class DiagnosticUploadClient(
    private val baseUrl: String = BuildConfig.DIAGNOSTICS_UPLOAD_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    internal suspend fun upload(
        report: File,
        reportId: String,
        trigger: DiagnosticTrigger,
        description: String = "",
        screenshot: DiagnosticScreenshot? = null,
    ): DiagnosticUploadReceipt = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw DiagnosticUploadUnavailableException()
        require(report.isFile) { "Diagnostic report is missing" }
        require(report.length() in 1..MAX_COMPRESSED_BYTES) {
            "Diagnostic report exceeds the ${MAX_COMPRESSED_BYTES / 1_000_000} MB upload limit"
        }
        require(description.length <= DiagnosticSubmissionPolicy.MAX_DESCRIPTION_CHARS) {
            "Diagnostic description exceeds the upload limit"
        }
        screenshot?.let {
            require(it.file.isFile && it.file.length() in 1..DiagnosticSubmissionPolicy.MAX_SCREENSHOT_BYTES) {
                "Diagnostic screenshot exceeds the upload limit"
            }
            require(it.contentType in SCREENSHOT_MEDIA_TYPES) { "Unsupported diagnostic screenshot" }
        }

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("report_id", reportId)
            .addFormDataPart("trigger", trigger.wireValue)
            .addFormDataPart("app_version", BuildConfig.VERSION_NAME)
            .addFormDataPart("version_code", BuildConfig.VERSION_CODE.toString())
            .addFormDataPart("build_sha", BuildConfig.GIT_SHA)
            .addFormDataPart("platform", "android")
        if (description.isNotBlank()) {
            bodyBuilder.addFormDataPart("description", description)
        }
        screenshot?.let {
            bodyBuilder.addFormDataPart(
                "screenshot",
                "$reportId-screenshot.${extensionFor(it.contentType)}",
                it.file.asRequestBody(it.contentType.toMediaType()),
            )
        }
        val body = bodyBuilder
            .addFormDataPart(
                "report",
                "$reportId.zip",
                report.asRequestBody(ZIP_MEDIA_TYPE),
            )
            .build()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/reports")
            .header("Accept", "application/json")
            .header("User-Agent", "Anilili/${BuildConfig.VERSION_NAME} diagnostics")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val retryable = response.code == 408 || response.code == 425 ||
                    response.code == 429 || response.code >= 500
                throw DiagnosticUploadHttpException(
                    statusCode = response.code,
                    retryable = retryable,
                    message = "Diagnostic server returned HTTP ${response.code}",
                )
            }
            val payload = runCatching { JSON.decodeFromString<UploadResponse>(responseText) }
                .getOrElse { throw IOException("Diagnostic server returned an invalid receipt", it) }
            if (payload.reportId != reportId || payload.status != "accepted") {
                throw IOException("Diagnostic server did not confirm report $reportId")
            }
            DiagnosticUploadReceipt(payload.reportId, payload.receivedBytes)
        }
    }

    @Serializable
    private data class UploadResponse(
        val status: String,
        val reportId: String,
        val receivedBytes: Long = 0,
    )

    companion object {
        const val MAX_COMPRESSED_BYTES = 25L * 1_000_000
        private val ZIP_MEDIA_TYPE = "application/zip".toMediaType()
        private val SCREENSHOT_MEDIA_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun extensionFor(contentType: String): String = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> error("Unsupported diagnostic screenshot")
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .eventListenerFactory(DiagnosticsHttpEventListener.Factory)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .callTimeout(3, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
