package com.miruronative.diagnostics

import android.net.Uri
import java.io.File

data class DiagnosticSubmissionInput(
    val description: String = "",
    val screenshotUri: Uri? = null,
)

internal data class DiagnosticScreenshot(
    val file: File,
    val contentType: String,
)

internal object DiagnosticSubmissionPolicy {
    const val MAX_DESCRIPTION_CHARS = 2_000
    const val MAX_SCREENSHOT_BYTES = 5L * 1_000_000

    fun normalizeDescription(value: String): String =
        DiagnosticRedactor.redactText(value)
            .replace("\u0000", "")
            .trim()
            .take(MAX_DESCRIPTION_CHARS)

    fun screenshotContentType(header: ByteArray): String? = when {
        header.size >= 3 &&
            header[0] == 0xff.toByte() &&
            header[1] == 0xd8.toByte() &&
            header[2] == 0xff.toByte() -> "image/jpeg"

        header.size >= 8 &&
            header.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            ) -> "image/png"

        header.size >= 12 &&
            header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"

        else -> null
    }
}
