package com.anilili.ui.detail

import com.anilili.data.model.EpisodeItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TvDetailEpisodeBrowserTest {
    @Test
    fun `latest jump targets the final episode in a long block`() {
        assertEquals(
            47,
            tvEpisodeScrollIndex(
                episodes = episodes(101..148),
                resumeEpisode = null,
                jumpToLatest = true,
            ),
        )
    }

    @Test
    fun `ordinary block changes keep the resumed episode visible`() {
        assertEquals(
            24,
            tvEpisodeScrollIndex(
                episodes = episodes(101..148),
                resumeEpisode = 125.0,
                jumpToLatest = false,
            ),
        )
    }

    @Test
    fun `short and empty results use safe starting positions`() {
        assertEquals(0, tvEpisodeScrollIndex(episodes(1..4), 4.0, jumpToLatest = false))
        assertEquals(-1, tvEpisodeScrollIndex(emptyList(), null, jumpToLatest = true))
    }

    private fun episodes(range: IntRange): List<EpisodeItem> = range.map { number ->
        EpisodeItem(
            pipeId = "episode-$number",
            number = number.toDouble(),
            title = null,
            image = null,
            filler = false,
        )
    }
}
