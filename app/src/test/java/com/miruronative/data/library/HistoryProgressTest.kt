package com.miruronative.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HistoryProgressTest {
    private val current = HistoryEntry(
        anilistId = 197754,
        title = "Liar Game",
        cover = null,
        episodeNumber = 1.0,
        episodeTitle = "First Game",
        provider = "auto",
        category = "sub",
        positionMs = 1_200_000,
        durationMs = 1_440_000,
        fromRemote = true,
    )

    @Test
    fun `watched episode advances continue watching and clears resume position`() {
        val updated = historyAfterEpisodeWatched(
            existing = current,
            watchedEpisode = 1.0,
            nextEpisode = 2.0,
            nextEpisodeTitle = "Second Game",
            seriesCompleted = false,
        )

        requireNotNull(updated)
        assertEquals(2.0, updated.episodeNumber, 0.0)
        assertEquals("Second Game", updated.episodeTitle)
        assertEquals(0, updated.positionMs)
        assertEquals(0, updated.durationMs)
        assertEquals(false, updated.fromRemote)
    }

    @Test
    fun `known final episode removes continue watching`() {
        assertNull(
            historyAfterEpisodeWatched(
                existing = current,
                watchedEpisode = 1.0,
                nextEpisode = null,
                nextEpisodeTitle = null,
                seriesCompleted = true,
            ),
        )
    }

    @Test
    fun `unknown next episode keeps the current row`() {
        val updated = historyAfterEpisodeWatched(
            existing = current,
            watchedEpisode = 1.0,
            nextEpisode = null,
            nextEpisodeTitle = null,
            seriesCompleted = false,
        )

        assertSame(current, updated)
    }

    @Test
    fun `late callback cannot move an already advanced row`() {
        val advanced = current.copy(episodeNumber = 2.0, positionMs = 0, durationMs = 0)
        val updated = historyAfterEpisodeWatched(
            existing = advanced,
            watchedEpisode = 1.0,
            nextEpisode = 2.0,
            nextEpisodeTitle = "Second Game",
            seriesCompleted = false,
        )

        assertSame(advanced, updated)
    }
}
