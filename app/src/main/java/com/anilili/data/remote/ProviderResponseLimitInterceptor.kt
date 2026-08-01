package com.anilili.data.remote

import java.io.IOException
import kotlin.math.min
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/**
 * Bounds decoded response bodies used by provider resolvers.
 *
 * This is intentionally installed only on AnivexaClient's resolver client. Playback and downloads
 * stream media through Media3/Cronet and must never inherit a small HTML/JSON response limit.
 */
internal class ProviderResponseLimitInterceptor(
    private val maxBytes: Long,
) : Interceptor {
    init {
        require(maxBytes > 0L) { "maxBytes must be positive" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val declaredLength = body.contentLength()
        if (declaredLength > maxBytes) {
            response.close()
            throw ProviderResponseTooLargeException(maxBytes)
        }

        val limitedSource = object : ForwardingSource(body.source()) {
            private var totalRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                // Read one byte beyond the boundary only to distinguish an exact-size body from
                // an oversized chunked/decompressed body. The extra byte never reaches callers.
                val remainingWithSentinel = maxBytes - totalRead + 1L
                val read = super.read(sink, min(byteCount, remainingWithSentinel))
                if (read > 0L) {
                    totalRead += read
                    if (totalRead > maxBytes) {
                        close()
                        throw ProviderResponseTooLargeException(maxBytes)
                    }
                }
                return read
            }
        }.buffer()

        val limitedBody = object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = declaredLength
            override fun source() = limitedSource
        }
        return response.newBuilder().body(limitedBody).build()
    }
}

internal class ProviderResponseTooLargeException(maxBytes: Long) :
    IOException("Provider response exceeded $maxBytes bytes")
