package com.anilili.data.library

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

        // Nothing to advance to — the row stays put, keeping its episode and resume position.
        // It does gain the watch itself, so finishing the latest aired episode still counts.
        requireNotNull(updated)
        assertEquals(current.episodeNumber, updated.episodeNumber, 0.0)
        assertEquals(current.positionMs, updated.positionMs)
        assertEquals(current.durationMs, updated.durationMs)
        assertEquals(current.copy(watchedEpisodes = updated.watchedEpisodes), updated)
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

    @Test
    fun `finishing an episode records it rather than implying the ones below it`() {
        val updated = historyAfterEpisodeWatched(
            existing = current.copy(episodeNumber = 3.0),
            watchedEpisode = 3.0,
            nextEpisode = 4.0,
            nextEpisodeTitle = null,
            seriesCompleted = false,
        )

        requireNotNull(updated)
        assertEquals(setOf(3.0), updated.watchedEpisodes)
        assertEquals(4.0, updated.episodeNumber, 0.0)
    }

    @Test
    fun `a skipped episode is not counted as watched`() {
        // Watch 1, jump to 3, finish 3. Episode 2 was never played and must stay unmarked —
        // the whole point of recording rather than inferring from the episode number.
        val afterOne = requireNotNull(
            historyAfterEpisodeWatched(current, 1.0, 2.0, null, seriesCompleted = false),
        )
        val jumpedToThree = afterOne.copy(episodeNumber = 3.0, positionMs = 0, durationMs = 0)
        val afterThree = requireNotNull(
            historyAfterEpisodeWatched(jumpedToThree, 3.0, 4.0, null, seriesCompleted = false),
        )

        assertEquals(setOf(1.0, 3.0), afterThree.watchedEpisodes)
        assertEquals(
            "a skipped episode draws no progress bar",
            0f,
            com.anilili.ui.components.episodeWatchFraction(afterThree, 2.0),
            0f,
        )
        assertEquals(
            "a watched episode is full",
            1f,
            com.anilili.ui.components.episodeWatchFraction(afterThree, 1.0),
            0f,
        )
    }

    @Test
    fun `the episode in progress shows its own position, not a full bar`() {
        val watching = current.copy(
            episodeNumber = 5.0,
            positionMs = 360_000,
            durationMs = 1_440_000,
            watchedEpisodes = setOf(1.0, 2.0),
        )
        assertEquals(0.25f, com.anilili.ui.components.episodeWatchFraction(watching, 5.0), 0.001f)
        assertEquals(0f, com.anilili.ui.components.episodeWatchFraction(watching, 6.0), 0f)
    }

    @Test
    fun `the final episode is recorded before the row is retired`() {
        val finished = historyAfterEpisodeWatched(
            existing = current.copy(episodeNumber = 12.0),
            watchedEpisode = 12.0,
            nextEpisode = null,
            nextEpisodeTitle = null,
            seriesCompleted = true,
        )
        assertNull("a completed series drops out of Continue Watching", finished)
    }

    @Test
    fun `a last episode with no successor still records the watch`() {
        val updated = requireNotNull(
            historyAfterEpisodeWatched(
                existing = current.copy(episodeNumber = 9.0),
                watchedEpisode = 9.0,
                nextEpisode = null,
                nextEpisodeTitle = null,
                seriesCompleted = false,
            ),
        )
        assertEquals(setOf(9.0), updated.watchedEpisodes)
        assertEquals("the row stays on the episode just finished", 9.0, updated.episodeNumber, 0.0)
    }
}
