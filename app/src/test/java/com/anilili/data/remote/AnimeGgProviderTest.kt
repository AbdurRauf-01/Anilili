package com.anilili.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeGgProviderTest {
    @Test
    fun `explicit empty video source array is not treated as playable embed`() {
        val html = """<script>var videoSources = [];</script><script>jwplayer('player').setup({})</script>"""

        assertFalse(animeGgEmbedCanPlay(html, hasExtractedMedia = false))
    }

    @Test
    fun `extracted direct media keeps embed eligible as fallback`() {
        val html = """<script>var videoSources = [];</script>"""

        assertTrue(animeGgEmbedCanPlay(html, hasExtractedMedia = true))
    }
}
