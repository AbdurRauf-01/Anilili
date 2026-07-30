package com.miruronative.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllAnimeCurrentRouteGuardTest {
    @Test
    fun deterministicLaneFailureOpensOnlyUntilCooldownExpires() {
        var now = 1_000L
        val guard = AllAnimeCurrentRouteGuard(cooldownMs = 100L) { now }

        assertTrue(
            guard.recordFailure(
                AllAnimeHttpException(400, """{"error":"missing_or_invalid_lane"}"""),
            ),
        )
        assertFalse(guard.shouldAttempt())

        now = 1_101L
        assertTrue(guard.shouldAttempt())
    }

    @Test
    fun successClosesAnOpenGuard() {
        val guard = AllAnimeCurrentRouteGuard(cooldownMs = 1_000L) { 10L }
        guard.recordFailure(AllAnimeHttpException(422, "invalid lane"))

        guard.recordSuccess()

        assertTrue(guard.shouldAttempt())
    }

    @Test
    fun transientAndGenericChallengeFailuresDoNotDisableCurrentRoute() {
        val guard = AllAnimeCurrentRouteGuard(cooldownMs = 1_000L) { 10L }

        assertFalse(guard.recordFailure(AllAnimeHttpException(503, "missing_or_invalid_lane")))
        assertFalse(guard.recordFailure(AllAnimeHttpException(403, "Cloudflare challenge")))
        assertTrue(guard.shouldAttempt())
    }

    @Test
    fun expiredCryptoBootstrapIsAProtocolFailure() {
        val guard = AllAnimeCurrentRouteGuard(cooldownMs = 1_000L) { 10L }

        assertTrue(guard.recordFailure(IllegalStateException("AllAnime crypto bootstrap expired")))
        assertFalse(guard.shouldAttempt())
    }
}
