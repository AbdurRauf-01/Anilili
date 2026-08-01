package com.anilili.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionDelayPersistenceTest {
    @Test
    fun `persistent delays encode deterministically and keep an opted-in zero`() {
        assertEquals(
            "10:-30000,20:0,30:30000",
            encodePersistentCaptionDelays(
                mapOf(
                    30 to 80_000L,
                    10 to -80_000L,
                    20 to 0L,
                    -1 to 500L,
                ),
            ),
        )
    }

    @Test
    fun `persistent delays ignore malformed entries and clamp stored values`() {
        assertEquals(
            mapOf(10 to -250L, 20 to 0L, 30 to MAX_CAPTION_DELAY_MS),
            decodePersistentCaptionDelays("broken,0:12,x:10,10:-250,20:0,30:999999,40:"),
        )
    }

    @Test
    fun `an empty preference means no anime opted in`() {
        assertEquals(emptyMap<Int, Long>(), decodePersistentCaptionDelays(null))
        assertEquals(emptyMap<Int, Long>(), decodePersistentCaptionDelays(""))
    }
}
