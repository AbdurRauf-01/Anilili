package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MegaPlay hands some dub streams the sub encode's subtitle file. The URLs below are the live
 * payloads for How a Realist Hero Rebuilt the Kingdom episode 5 (captured 2026-07-20): the dub
 * stream sits under one encode hash while all four of its subtitle tracks quote another — the sub
 * encode's, whose own cut runs ~15 s shorter at the head.
 */
class MegaPlaySubtitleOffsetTest {
    private val subEncode = "b4253477b9876b2c343102127d430aae"
    private val dubEncode = "8750f75c6c6d1a7564ee94cf872c1f0b"
    private val episode = "06b5f6218cc47f55b40a9c165b39c10b"

    private val dubStream = "https://9hjkrt.nekostream.site/$episode/$dubEncode/master.m3u8"
    private val subStream = "https://9hjkrt.nekostream.site/$episode/$subEncode/master.m3u8"
    private val borrowedSubtitle =
        "https://1oe.lostproject.club/anime/$episode/$subEncode/subtitles/eng-2.vtt"

    @Test
    fun `reads the encode hash, not the episode hash`() {
        assertEquals(dubEncode, megaPlayEncodeTag(dubStream))
        assertEquals(subEncode, megaPlayEncodeTag(borrowedSubtitle))
        assertNotEquals(megaPlayEncodeTag(dubStream), megaPlayEncodeTag(borrowedSubtitle))
    }

    @Test
    fun `subtitles served with their own encode are left alone`() {
        assertEquals(megaPlayEncodeTag(subStream), megaPlayEncodeTag(borrowedSubtitle))
    }

    @Test
    fun `unfamiliar paths yield no tag rather than a wrong one`() {
        assertNull(megaPlayEncodeTag(null))
        assertNull(megaPlayEncodeTag("https://example.com/video/master.m3u8"))
        // Only the episode hash present: nothing to compare an encode against.
        assertNull(megaPlayEncodeTag("https://9hjkrt.nekostream.site/$episode/master.m3u8"))
    }

    @Test
    fun `offset comes from the gap between the two encodes' intro marks`() {
        // Episode 5: sub intro at 18 s, dub at 33 s; the streams themselves differ by 14.6 s.
        assertEquals(15_000L, borrowedSubtitleOffsetMs(33.0, 18.0))
        // Episode 2 of the same show pads less, so the shift has to be measured per episode.
        assertEquals(7_000L, borrowedSubtitleOffsetMs(62.0, 55.0))
    }

    @Test
    fun `implausible gaps are ignored`() {
        assertEquals(0L, borrowedSubtitleOffsetMs(55.0, 55.0)) // aligned encodes, as Solo Leveling
        assertEquals(0L, borrowedSubtitleOffsetMs(55.4, 55.0)) // sub-second noise in their marks
        assertEquals(0L, borrowedSubtitleOffsetMs(400.0, 55.0)) // not head footage: bad episode data
        assertEquals(0L, borrowedSubtitleOffsetMs(null, 18.0)) // no marks published
        assertEquals(0L, borrowedSubtitleOffsetMs(33.0, null))
    }

    @Test
    fun `a dub that runs shorter shifts the other way`() {
        assertEquals(-4_000L, borrowedSubtitleOffsetMs(14.0, 18.0))
    }
}
