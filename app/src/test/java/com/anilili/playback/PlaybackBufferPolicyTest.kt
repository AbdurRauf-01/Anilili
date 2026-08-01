package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlaybackBufferPolicyTest {
    @Test
    fun `tv and constrained phones receive a bounded buffer`() {
        assertEquals(30_000, playbackBufferPolicy(true, false, 512)?.maximumMs)
        assertEquals(30_000, playbackBufferPolicy(false, true, 512)?.maximumMs)
        assertEquals(30_000, playbackBufferPolicy(false, false, 256)?.maximumMs)
    }

    @Test
    fun `larger phones retain Media3 defaults`() {
        assertNull(playbackBufferPolicy(false, false, 384))
        assertNotNull(playbackBufferPolicy(true, false, 384))
    }
}
