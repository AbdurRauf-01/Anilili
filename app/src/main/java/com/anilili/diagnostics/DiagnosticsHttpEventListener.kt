package com.anilili.diagnostics

import android.os.SystemClock
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong

/** One compact, redacted timing event per OkHttp call instead of noisy request/response dumps. */
class DiagnosticsHttpEventListener private constructor(private val callId: Long) : EventListener() {
    private var startedMs = 0L
    private var dnsStartedMs = 0L
    private var dnsMs = -1L
    private var connectStartedMs = 0L
    private var connectMs = -1L
    private var tlsStartedMs = 0L
    private var tlsMs = -1L
    private var responseHeadersMs = -1L
    private var responseCode = -1
    private var responseProtocol = "unknown"
    private var cache = "unknown"
    private var responseBytes = -1L
    private var host = "unknown"
    private var method = "unknown"
    private var serverTiming = "none"
    private var serverInstanceAge = "none"
    private var serverColdStart = "none"

    override fun callStart(call: Call) {
        startedMs = SystemClock.elapsedRealtime()
        val request = call.request()
        host = request.url.host
        method = request.method
        DiagnosticsLog.event(
            category = "network",
            name = "http.call.started",
            attributes = mapOf(
                "callId" to callId,
                "method" to method,
                "host" to host,
            ),
        )
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartedMs = SystemClock.elapsedRealtime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        dnsMs = elapsedSince(dnsStartedMs)
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartedMs = SystemClock.elapsedRealtime()
    }

    override fun secureConnectStart(call: Call) {
        tlsStartedMs = SystemClock.elapsedRealtime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs = elapsedSince(tlsStartedMs)
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        connectMs = elapsedSince(connectStartedMs)
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        responseHeadersMs = elapsedSince(startedMs)
        responseCode = response.code
        responseProtocol = response.protocol.toString()
        serverTiming = response.header("Server-Timing")?.take(240) ?: "none"
        serverInstanceAge = response.header("X-Anilili-Instance-Age")?.take(40) ?: "none"
        serverColdStart = response.header("X-Anilili-Cold-Start")?.take(20) ?: "none"
        cache = when {
            response.cacheResponse != null && response.networkResponse != null -> "conditional"
            response.cacheResponse != null -> "hit"
            else -> "miss"
        }
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        responseBytes = byteCount
    }

    override fun callEnd(call: Call) {
        emit("http.call.complete", null)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        emit(httpCallOutcomeName(call.isCanceled(), ioe.message), ioe)
    }

    private fun emit(name: String, failure: IOException?) {
        DiagnosticsLog.event(
            category = "network",
            name = name,
            attributes = mapOf(
                "callId" to callId,
                "method" to method,
                "host" to host,
                "totalMs" to elapsedSince(startedMs),
                "dnsMs" to dnsMs,
                "connectMs" to connectMs,
                "tlsMs" to tlsMs,
                "timeToHeadersMs" to responseHeadersMs,
                "status" to responseCode,
                "protocol" to responseProtocol,
                "cache" to cache,
                "responseBytes" to responseBytes,
                "serverTiming" to serverTiming,
                "serverInstanceAge" to serverInstanceAge,
                "serverColdStart" to serverColdStart,
                "failureType" to (failure?.javaClass?.simpleName ?: "none"),
                "failureMessage" to (failure?.message ?: "none"),
            ),
        )
    }

    private fun elapsedSince(startMs: Long): Long = if (startMs <= 0) -1 else {
        (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0)
    }

    object Factory : EventListener.Factory {
        private val ids = AtomicLong(0)
        override fun create(call: Call): EventListener = DiagnosticsHttpEventListener(ids.incrementAndGet())
    }
}

internal fun httpCallOutcomeName(callCanceled: Boolean, failureMessage: String?): String =
    if (callCanceled || failureMessage.equals("Canceled", ignoreCase = true)) {
        "http.call.canceled"
    } else {
        "http.call.failed"
    }
