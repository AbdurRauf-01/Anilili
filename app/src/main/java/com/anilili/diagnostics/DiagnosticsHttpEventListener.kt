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
    /**
     * How far the call got, so a request that never answers still says where it stopped.
     *
     * A TV report showed an AniList call open for twelve seconds with no completion event at all,
     * and the phase timings only exist on callEnd/callFailed — so the archive proved "no response"
     * but could not distinguish a DNS black hole from a TLS handshake being dropped. This is
     * carried into the in-flight registry below, which the periodic snapshot reads.
     */
    @Volatile private var phase = "queued"

    override fun callStart(call: Call) {
        startedMs = SystemClock.elapsedRealtime()
        val request = call.request()
        host = request.url.host
        method = request.method
        phase = "started"
        InFlight.add(callId, this)
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
        phase = "dns"
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        dnsMs = elapsedSince(dnsStartedMs)
        phase = "dns-done"
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStartedMs = SystemClock.elapsedRealtime()
        phase = "connect"
    }

    override fun secureConnectStart(call: Call) {
        tlsStartedMs = SystemClock.elapsedRealtime()
        phase = "tls"
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs = elapsedSince(tlsStartedMs)
        phase = "tls-done"
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        connectMs = elapsedSince(connectStartedMs)
        phase = "connected"
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        responseHeadersMs = elapsedSince(startedMs)
        phase = "headers"
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
        InFlight.remove(callId)
        emit("http.call.complete", null)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        InFlight.remove(callId)
        emit(httpCallOutcomeName(call.isCanceled(), ioe.message), ioe)
    }

    private fun stallDescription(): String = "$host:$phase:${elapsedSince(startedMs)}ms"

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

    /**
     * Calls that have started and not finished.
     *
     * A request the caller abandons — the home screen gives up at fifteen seconds while OkHttp
     * waits forty-five — produces no completion event at all, so the report that captures the
     * symptom can miss the cause entirely by a second. Anything lingering here is named in the
     * periodic snapshot instead, with the phase it reached.
     */
    object InFlight {
        private const val STALL_AFTER_MS = 4_000L
        private val calls = java.util.concurrent.ConcurrentHashMap<Long, DiagnosticsHttpEventListener>()

        internal fun add(callId: Long, listener: DiagnosticsHttpEventListener) {
            calls[callId] = listener
        }

        internal fun remove(callId: Long) {
            calls.remove(callId)
        }

        /** "graphql.anilist.co:tls:12300ms" for every call stuck longer than the threshold. */
        fun stalled(): String = calls.values
            .filter { it.elapsedSince(it.startedMs) >= STALL_AFTER_MS }
            .sortedByDescending { it.elapsedSince(it.startedMs) }
            .take(6)
            .joinToString(",") { it.stallDescription() }
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
