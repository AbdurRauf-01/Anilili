package com.miruronative.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.util.Base64Compat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
internal class FlixcloudPlaylistDataSource(
    private val upstream: DataSource,
    private val playlistKey: () -> String?,
) : DataSource {
    private var buffered: ByteArray? = null
    private var bufferedPosition = 0
    private var bufferedLimit = 0
    private var upstreamOpen = false
    private var resolvedUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        buffered = null
        bufferedPosition = 0
        bufferedLimit = 0
        resolvedUri = null
        responseHeaders = emptyMap()

        val length = upstream.open(dataSpec)
        upstreamOpen = true
        val key = playlistKey()
        val path = dataSpec.uri.path.orEmpty()
        val isPlaylist = dataSpec.position == 0L && path.endsWith(".m3u8", ignoreCase = true)
        val isWrappedSegment = dataSpec.position == 0L &&
            (path.endsWith(".png", ignoreCase = true) || path.endsWith(".webp", ignoreCase = true))
        if (key.isNullOrBlank() || (!isPlaylist && !isWrappedSegment)) return length

        val maximumBytes = if (isPlaylist) MAX_PLAYLIST_BYTES else MAX_WRAPPED_SEGMENT_BYTES
        val raw = try {
            readBounded(length, maximumBytes)
        } catch (error: Exception) {
            if (upstreamOpen) {
                upstreamOpen = false
                runCatching { upstream.close() }
            }
            DiagnosticsLog.throwable(
                "Flixcloud response rejected host=${dataSpec.uri.host ?: "unknown"} " +
                    "kind=${if (isPlaylist) "playlist" else "segment"} limitBytes=$maximumBytes",
                error,
            )
            throw error
        }
        resolvedUri = upstream.uri
        responseHeaders = upstream.responseHeaders
        upstream.close()
        upstreamOpen = false

        val decoded: ByteArray
        val decodedOffset: Int
        if (isPlaylist) {
            decoded = decodeFlixcloudPlaylist(raw, key) ?: raw
            decodedOffset = 0
        } else {
            decoded = raw
            decodedOffset = unwrapFlixcloudSegmentInPlace(raw) ?: 0
        }
        val wasDecoded = decoded !== raw || decodedOffset > 0
        if (wasDecoded) {
            val decodedBytes = decoded.size - decodedOffset
            val unusuallyLarge = decodedBytes >= LARGE_SEGMENT_LOG_BYTES
            if (isPlaylist || unusuallyLarge || segmentLogCount.getAndIncrement() < 3) {
                DiagnosticsLog.event(
                    "Flixcloud ${if (isPlaylist) "playlist decoded" else "segment unwrapped"} " +
                        "host=${dataSpec.uri.host ?: "unknown"} bytes=$decodedBytes " +
                        "transportBytes=${raw.size} large=$unusuallyLarge",
                )
            }
        }
        buffered = decoded
        bufferedPosition = decodedOffset
        bufferedLimit = decoded.size
        return (bufferedLimit - bufferedPosition).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = buffered ?: return upstream.read(buffer, offset, length)
        if (bufferedPosition >= bufferedLimit) return C.RESULT_END_OF_INPUT
        val count = minOf(length, bufferedLimit - bufferedPosition)
        data.copyInto(buffer, offset, bufferedPosition, bufferedPosition + count)
        bufferedPosition += count
        return count
    }

    override fun getUri(): Uri? = resolvedUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (responseHeaders.isNotEmpty()) responseHeaders else upstream.responseHeaders

    override fun close() {
        buffered = null
        bufferedPosition = 0
        bufferedLimit = 0
        resolvedUri = null
        responseHeaders = emptyMap()
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 2 * 1_024 * 1_024
        const val MAX_WRAPPED_SEGMENT_BYTES = 24 * 1_024 * 1_024
        const val LARGE_SEGMENT_LOG_BYTES = 8 * 1_024 * 1_024
        val segmentLogCount = AtomicInteger(0)
    }

    private fun readBounded(reportedLength: Long, maximumBytes: Int): ByteArray {
        if (reportedLength > maximumBytes) {
            throw IOException("Flixcloud response exceeds the $maximumBytes byte safety limit")
        }
        if (reportedLength in 0..maximumBytes.toLong()) {
            val expected = reportedLength.toInt()
            val bytes = ByteArray(expected)
            var position = 0
            while (position < expected) {
                val read = upstream.read(bytes, position, expected - position)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read > 0) position += read
            }
            return if (position == expected) bytes else bytes.copyOf(position)
        }
        return ByteArrayOutputStream(64 * 1_024).use { output ->
            val chunk = ByteArray(16 * 1_024)
            while (true) {
                val read = upstream.read(chunk, 0, chunk.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read > 0) {
                    if (output.size() + read > maximumBytes) {
                        throw IOException("Flixcloud response exceeds the $maximumBytes byte safety limit")
                    }
                    output.write(chunk, 0, read)
                }
            }
            output.toByteArray()
        }
    }
}

internal fun decodeFlixcloudPlaylist(payload: ByteArray, keyBase64: String): ByteArray? {
    val encoded = payload.toString(Charsets.UTF_8).trim()
    if (encoded.startsWith("#EXTM3U")) return null
    return runCatching {
        val key = Base64Compat.decode(keyBase64)
        require(key.isNotEmpty())
        val encrypted = Base64Compat.decode(encoded)
        ByteArray(encrypted.size) { index ->
            (encrypted[index].toInt() xor key[index % key.size].toInt()).toByte()
        }.takeIf { it.toString(Charsets.UTF_8).startsWith("#EXTM3U") }
    }.getOrNull()
}

internal fun decodeFlixcloudSegment(payload: ByteArray): ByteArray? {
    val mutable = payload.copyOf()
    val headerSize = unwrapFlixcloudSegmentInPlace(mutable) ?: return null
    return mutable.copyOfRange(headerSize, mutable.size)
}

private fun unwrapFlixcloudSegmentInPlace(payload: ByteArray): Int? {
    val headerSize = when {
        payload.matchesAt(0, "RIFF".toByteArray()) && payload.matchesAt(8, "WEBP".toByteArray()) -> 12
        payload.matchesAt(
            0,
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        ) -> 8
        else -> return null
    }
    if (payload.size == headerSize) return headerSize

    if (payload[headerSize].toInt() and 0xff == 0x47) return headerSize
    for (index in headerSize until payload.size) {
        payload[index] = (
            payload[index].toInt() xor segmentMask[(index - headerSize) and 15].toInt()
            ).toByte()
    }
    if (payload[headerSize].toInt() and 0xff == 0x47) return headerSize
    // Restore unrecognised payloads so callers can pass the original response through unchanged.
    for (index in headerSize until payload.size) {
        payload[index] = (
            payload[index].toInt() xor segmentMask[(index - headerSize) and 15].toInt()
            ).toByte()
    }
    return null
}

private fun ByteArray.matchesAt(offset: Int, expected: ByteArray): Boolean {
    if (offset < 0 || size - offset < expected.size) return false
    return expected.indices.all { index -> this[offset + index] == expected[index] }
}

private val segmentMask = byteArrayOf(
    157.toByte(), 42, 241.toByte(), 71, 179.toByte(), 142.toByte(), 92, 112,
    166.toByte(), 25, 228.toByte(), 59, 216.toByte(), 98, 15, 197.toByte(),
)
