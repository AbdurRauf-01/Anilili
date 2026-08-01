package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListRateSlotTest {
    @Test
    fun `a cold start with no observed budget does not throttle`() {
        // Regression: this used to reserve slots 2.1s apart before a single response had been
        // seen, queueing the home query behind startup work until it timed out.
        val (start, next) = nextRateSlot(
            now = 1_000,
            remaining = UNKNOWN_RATE_REMAINING,
            reset = 0,
            nextSlot = 0,
        )
        assertEquals(1_000L, start)
        assertEquals(1_000L + MIN_SPACING_MS_TEST, next)
    }

    @Test
    fun `a healthy budget lets a burst through`() {
        val (start, next) = nextRateSlot(now = 1_000, remaining = 50, reset = 61_000, nextSlot = 0)
        assertEquals(1_000L, start)
        assertEquals(1_000L + MIN_SPACING_MS_TEST, next)

        // A second call arriving at the same instant still takes the reserved slot, in order.
        val (start2, _) = nextRateSlot(now = 1_000, remaining = 50, reset = 61_000, nextSlot = next)
        assertEquals(next, start2)
    }

    @Test
    fun `a whole home screen fan-out fits inside the load budget`() {
        // Ten startup calls back to back must cost far less than HomeViewModel's 15s timeout.
        var slot = 0L
        repeat(10) {
            val (_, next) = nextRateSlot(
                now = 1_000,
                remaining = UNKNOWN_RATE_REMAINING,
                reset = 0,
                nextSlot = slot,
            )
            slot = next
        }
        assertTrue("ten queued calls cost ${slot - 1_000}ms", slot - 1_000 < 2_000)
    }

    @Test
    fun `spent budget holds until the window resets`() {
        val (start, _) = nextRateSlot(now = 1_000, remaining = 0, reset = 31_000, nextSlot = 0)
        assertEquals(31_000L, start) // waits ~30s for the reset instead of 429-ing
    }

    @Test
    fun `stale spent budget past the reset proceeds immediately`() {
        val (start, _) = nextRateSlot(now = 100_000, remaining = 0, reset = 31_000, nextSlot = 0)
        assertEquals(100_000L, start)
    }

    @Test
    fun `low budget spreads the remaining calls across the window`() {
        // 4 calls left, 8s until reset -> ~2s between calls.
        val (start, next) = nextRateSlot(now = 1_000, remaining = 4, reset = 9_000, nextSlot = 0)
        assertEquals(1_000L, start)
        assertEquals(3_100L, next)
    }

    @Test
    fun `the watermark is where pacing begins`() {
        val (_, pacedNext) = nextRateSlot(
            now = 1_000,
            remaining = RATE_LOW_WATERMARK,
            reset = 61_000,
            nextSlot = 0,
        )
        assertTrue("at the watermark the call is spread", pacedNext - 1_000 > MIN_SPACING_MS_TEST)

        val (_, freeNext) = nextRateSlot(
            now = 1_000,
            remaining = RATE_LOW_WATERMARK + 1,
            reset = 61_000,
            nextSlot = 0,
        )
        assertEquals(1_000L + MIN_SPACING_MS_TEST, freeNext)
    }

    @Test
    fun `spreading is capped so a single call never stalls too long`() {
        // 1 call left, 100s window -> would be 100s; capped at the max spacing.
        val (_, next) = nextRateSlot(now = 1_000, remaining = 1, reset = 101_000, nextSlot = 0)
        assertEquals(1_000L + MAX_SPACING_MS_TEST, next)
    }

    @Test
    fun `backoff to a far reset is bounded`() {
        val (start, _) = nextRateSlot(now = 1_000, remaining = 0, reset = 201_000, nextSlot = 0)
        assertEquals(1_000L + MAX_BACKOFF_MS_TEST, start)
    }

    private companion object {
        const val MIN_SPACING_MS_TEST = 100L
        const val MAX_SPACING_MS_TEST = 3_000L
        const val MAX_BACKOFF_MS_TEST = 60_000L
    }
}
