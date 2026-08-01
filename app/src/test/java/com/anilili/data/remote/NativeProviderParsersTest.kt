package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProviderParsersTest {
    @Test
    fun parsesNativeEpisodeIds() {
        val request = NativeProviderParsers.episodeRequest("watch/animegg/16498/dub/animegg-7")

        assertEquals("animegg", request?.provider)
        assertEquals(16498, request?.anilistId)
        assertEquals("dub", request?.audio)
        assertEquals(7, request?.episode)
        assertNull(NativeProviderParsers.episodeRequest("https://example.com/not-an-episode"))
    }

    @Test
    fun extractsAttributesAndDecodesEntities() {
        val tag = "<track src=\"https://cdn.test/sub.vtt?a=1&amp;b=2\" label='English'>"

        assertEquals("https://cdn.test/sub.vtt?a=1&b=2", NativeProviderParsers.attr(tag, "src"))
        assertEquals("English", NativeProviderParsers.attr(tag, "label"))
        assertEquals("Attack & Titan", NativeProviderParsers.stripTags("<b>Attack</b> &amp; Titan"))
    }

    @Test
    fun titleMatchingAndHlsDiscoveryHandleProviderMarkup() {
        assertEquals(1.0, NativeProviderParsers.titleScore("Attack on Titan", "Attack on Titan"), 0.0)
        assertTrue(NativeProviderParsers.titleScore("Re:Zero", "ReZero Season 2") > 0.8)
        assertTrue(
            NativeProviderParsers.titleSelectionScore("Jujutsu Kaisen", "Jujutsu Kaisen (TV)") >
                NativeProviderParsers.titleSelectionScore("Jujutsu Kaisen", "Jujutsu Kaisen: The Culling Game Part 1"),
        )
        assertEquals(
            listOf("https://cdn.test/master.m3u8?token=abc"),
            NativeProviderParsers.hlsUrls("file: \"https:\\/\\/cdn.test\\/master.m3u8?token=abc\""),
        )
    }

    @Test
    fun parsesPerEpisodeAudioBadges() {
        val dataAttributes = """
            <a href="/watch/show/ep-1" data-num="1" data-sub="1" data-dub="1">1</a>
            <a href="/watch/show/ep-2" data-num="2" data-hsub="1" data-sub="1" data-dub="0">2</a>
        """.trimIndent()
        val kai = NativeProviderParsers.dataAudioEpisodes(dataAttributes)
        assertEquals(setOf(1, 2), kai.sub)
        assertEquals(setOf(1), kai.dub)

        val animeGg = NativeProviderParsers.animeGgEpisodes(
            """
            <li><a class="anm_det_pop"><strong>Show 7</strong></a><span class="btn-subbed">SUBBED</span></li>
            <li><a class="anm_det_pop"><strong>Show 8</strong></a><span class="btn-dubbed">DUBBED</span></li>
            """.trimIndent(),
        )
        assertEquals(setOf(7), animeGg.sub)
        assertEquals(setOf(8), animeGg.dub)

        val aniNeko = NativeProviderParsers.aniNekoEpisodes(
            """
            <article class="nv-info-episode-item"><a href="/watch/show/ep-3">3</a><span>SUB</span><span>DUB</span></article>
            <article class="nv-info-episode-item"><a href="/watch/show/ep-4">4</a><span>HSUB</span></article>
            """.trimIndent(),
        )
        assertEquals(setOf(3, 4), aniNeko.sub)
        assertEquals(setOf(3), aniNeko.dub)
        assertEquals(1172, NativeProviderParsers.labelledEpisodeCount("<span>1172 Episodes</span>"))
    }
}
