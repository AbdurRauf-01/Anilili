package com.anilili.playback

import com.anilili.data.model.EpisodeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkEpisodeDownloadsTest {
    private fun episode(number: Double) = EpisodeItem(
        pipeId = "watch/bonk/1/sub/bonk-${number.toInt()}",
        number = number,
        title = "Episode ${number.toInt()}",
        image = null,
        filler = false,
    )

    private val episodes = (1..8).map { episode(it.toDouble()) }
    private val anilistId = 1
    private val category = "sub"

    @Test
    fun `a batch starts at the episode on screen, not the beginning`() {
        // Someone on episode 5 wants the rest of the run, not a re-download of 1 to 4.
        val pending = BulkEpisodeDownloads.pendingEpisodes(
            episodes = episodes,
            anilistId = anilistId,
            category = category,
            fromNumber = 5.0,
            alreadyHave = emptySet(),
        )
        assertEquals(listOf(5.0, 6.0, 7.0, 8.0), pending.map { it.number })
    }

    @Test
    fun `episodes already downloaded are skipped`() {
        val have = setOf(
            EpisodeDownloads.idFor(anilistId, category, "2"),
            EpisodeDownloads.idFor(anilistId, category, "4"),
        )
        val pending = BulkEpisodeDownloads.pendingEpisodes(
            episodes = episodes,
            anilistId = anilistId,
            category = category,
            fromNumber = 1.0,
            alreadyHave = have,
        )
        assertEquals(listOf(1.0, 3.0, 5.0, 6.0, 7.0, 8.0), pending.map { it.number })
    }

    @Test
    fun `a fully downloaded run leaves nothing to queue`() {
        val have = episodes.map { EpisodeDownloads.idFor(anilistId, category, it.displayNumber) }.toSet()
        assertTrue(
            BulkEpisodeDownloads.pendingEpisodes(episodes, anilistId, category, null, have).isEmpty(),
        )
    }

    @Test
    fun `a null start point takes the whole list`() {
        val pending = BulkEpisodeDownloads.pendingEpisodes(episodes, anilistId, category, null, emptySet())
        assertEquals(episodes.size, pending.size)
    }

    @Test
    fun `sub and dub downloads of the same episode are tracked apart`() {
        // The id carries the category, so having the sub must not suppress queueing the dub.
        val haveSub = setOf(EpisodeDownloads.idFor(anilistId, "sub", "3"))
        val dubPending = BulkEpisodeDownloads.pendingEpisodes(
            episodes = episodes,
            anilistId = anilistId,
            category = "dub",
            fromNumber = 3.0,
            alreadyHave = haveSub,
        )
        assertTrue("dub episode 3 should still be queued", dubPending.any { it.number == 3.0 })
    }
}
