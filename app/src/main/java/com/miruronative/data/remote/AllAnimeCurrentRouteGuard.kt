package com.miruronative.data.remote

import java.io.IOException

/**
 * Suppresses only AllAnime's versioned current route after a deterministic protocol mismatch.
 * Catalog search and the legacy source route remain available, so one broken route cannot disable
 * the whole provider.
 */
internal class AllAnimeCurrentRouteGuard(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var blockedUntilMs = 0L

    fun shouldAttempt(): Boolean = nowMs() >= blockedUntilMs

    @Synchronized
    fun recordSuccess() {
        blockedUntilMs = 0L
    }

    @Synchronized
    fun recordFailure(throwable: Throwable): Boolean {
        if (!isDeterministicProtocolFailure(throwable)) return false
        blockedUntilMs = nowMs() + cooldownMs
        return true
    }

    internal fun isDeterministicProtocolFailure(throwable: Throwable): Boolean {
        val failure = generateSequence(throwable) { it.cause }.toList()
        val http = failure.filterIsInstance<AllAnimeHttpException>().firstOrNull()
        val text = failure.joinToString(" ") { it.message.orEmpty() } + " " + http?.responseBody.orEmpty()
        val protocolMarker = PROTOCOL_MARKERS.any { text.contains(it, ignoreCase = true) }
        if (http != null) return http.code in PROTOCOL_HTTP_CODES && protocolMarker
        return protocolMarker && LOCAL_PROTOCOL_MARKERS.any { text.contains(it, ignoreCase = true) }
    }

    companion object {
        private const val DEFAULT_COOLDOWN_MS = 30L * 60L * 1_000L
        private val PROTOCOL_HTTP_CODES = setOf(400, 401, 403, 404, 422)
        private val PROTOCOL_MARKERS = listOf(
            "missing_or_invalid_lane",
            "invalid lane",
            "boot token",
            "build id",
            "build-id",
            "persistedquery",
            "persisted query",
            "crypto bootstrap",
            "aa_req",
            "aareq",
        )
        private val LOCAL_PROTOCOL_MARKERS = listOf(
            "current route",
            "bootstrap",
            "epoch",
            "build",
            "persisted",
            "aa_req",
            "aareq",
        )
    }
}

internal class AllAnimeHttpException(
    val code: Int,
    val responseBody: String,
) : IOException("AllAnime HTTP $code")
