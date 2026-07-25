package com.miruronative.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageTest {
    @Test
    fun `estimates scale with resolution`() {
        val p1080 = DownloadStorage.estimatedEpisodeBytes(1080)
        val p720 = DownloadStorage.estimatedEpisodeBytes(720)
        val p480 = DownloadStorage.estimatedEpisodeBytes(480)
        assertTrue("1080p should be the largest", p1080 > p720)
        assertTrue("720p should beat 480p", p720 > p480)
    }

    @Test
    fun `unknown and best-available resolutions fall back to a middling estimate`() {
        // "Best available" carries a null height, and an odd rendition height should not throw.
        val fallback = DownloadStorage.estimatedEpisodeBytes(null)
        assertEquals(fallback, DownloadStorage.estimatedEpisodeBytes(1440))
        assertTrue(fallback > DownloadStorage.estimatedEpisodeBytes(480))
        assertTrue(fallback < DownloadStorage.estimatedEpisodeBytes(1080))
    }

    @Test
    fun `headroom is large enough to matter on a TV stick`() {
        // A Fire TV stick has roughly 5 GB usable. Reserving under a few hundred MB would let a
        // download run the device dry, which is the failure this whole check exists to avoid.
        assertTrue(DownloadStorage.HEADROOM_BYTES >= 500L * 1024 * 1024)
    }
}
