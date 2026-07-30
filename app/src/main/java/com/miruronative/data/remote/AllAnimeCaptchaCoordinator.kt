package com.miruronative.data.remote

import com.miruronative.diagnostics.DiagnosticsLog
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AllAnimeCaptchaSolution(val token: String, val provider: String)

/** One visible, cancellable challenge at a time; tokens live only long enough for one retry. */
internal object AllAnimeCaptchaCoordinator {
    data class Challenge(val id: Long, val url: String)
    private data class Pending(val challenge: Challenge, val result: CompletableDeferred<AllAnimeCaptchaSolution?>)

    private val serial = Mutex()
    private val ids = AtomicLong(0L)
    private val _challenge = MutableStateFlow<Challenge?>(null)
    val challenge = _challenge.asStateFlow()

    @Volatile
    private var pending: Pending? = null

    suspend fun request(url: String): AllAnimeCaptchaSolution? = serial.withLock {
        val challenge = Challenge(ids.incrementAndGet(), url)
        val current = Pending(challenge, CompletableDeferred())
        synchronized(this) {
            pending = current
            _challenge.value = challenge
        }
        DiagnosticsLog.event("AllAnime CAPTCHA requested")
        try {
            current.result.await()
        } finally {
            synchronized(this) {
                if (pending === current) {
                    pending = null
                    _challenge.value = null
                }
            }
        }
    }

    fun submit(id: Long, solution: AllAnimeCaptchaSolution) {
        val current = pending ?: return
        if (current.challenge.id != id || solution.token.isBlank()) return
        if (solution.provider !in SUPPORTED_PROVIDERS) return
        DiagnosticsLog.event("AllAnime CAPTCHA completed provider=${solution.provider}")
        current.result.complete(solution)
    }

    fun cancel(id: Long) {
        val current = pending ?: return
        if (current.challenge.id != id) return
        DiagnosticsLog.event("AllAnime CAPTCHA cancelled")
        current.result.complete(null)
    }

    internal fun resetForTest() {
        pending?.result?.complete(null)
        pending = null
        _challenge.value = null
    }

    private val SUPPORTED_PROVIDERS = setOf("turnstile", "google", "turnstile1")
}

internal class AllAnimeCaptchaRequiredException(
    val currentRoute: Boolean,
) : IllegalStateException("AllAnime requires a security check")
