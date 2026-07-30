package com.miruronative.data.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSyncPolicyTest {
    @Test
    fun `first launch and clock rollback both request a startup sync`() {
        assertTrue(shouldRunStartupReleaseSync(nowUtcMs = 10_000, lastSuccessUtcMs = 0))
        assertTrue(shouldRunStartupReleaseSync(nowUtcMs = 10_000, lastSuccessUtcMs = 20_000))
    }

    @Test
    fun `recent success suppresses duplicate cold launch work`() {
        assertFalse(
            shouldRunStartupReleaseSync(
                nowUtcMs = 30 * 60 * 1_000L,
                lastSuccessUtcMs = 1_000L,
            ),
        )
    }

    @Test
    fun `one hour old success becomes stale`() {
        assertTrue(
            shouldRunStartupReleaseSync(
                nowUtcMs = 2 * 60 * 60 * 1_000L,
                lastSuccessUtcMs = 60 * 60 * 1_000L,
            ),
        )
    }

    @Test
    fun `television fan out is more conservative`() {
        assertEquals(2, releaseSyncParallelism(isTv = true))
        assertEquals(4, releaseSyncParallelism(isTv = false))
    }
}
