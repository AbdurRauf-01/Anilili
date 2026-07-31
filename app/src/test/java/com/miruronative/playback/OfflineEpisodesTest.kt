package com.miruronative.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineEpisodesTest {

    @Test
    fun `an exported episode survives its cached copy being dropped`() {
        val merged = offlineEpisodes(downloads = emptyList(), exported = listOf(exported("ep1")))

        assertEquals(listOf("ep1"), merged.map(OfflineEpisode::id))
        val episode = merged.single()
        assertNull(episode.download)
        assertTrue(episode.isPlayable)
        assertTrue(episode.isInDownloadsFolder)
        assertFalse(episode.isDownloading)
        assertEquals("Cowboy Bebop", episode.metadata.seriesTitle)
    }

    @Test
    fun `an episode held both ways collapses to one entry`() {
        val merged = offlineEpisodes(
            downloads = listOf(download("ep1", EpisodeDownloadState.COMPLETED)),
            exported = listOf(exported("ep1")),
        )

        val episode = merged.single()
        assertTrue(episode.download != null && episode.exported != null)
        assertTrue(episode.isPlayable)
        assertTrue(episode.isInDownloadsFolder)
    }

    @Test
    fun `an in-progress download is listed but not playable`() {
        val episode = offlineEpisodes(
            downloads = listOf(download("ep1", EpisodeDownloadState.DOWNLOADING)),
            exported = emptyList(),
        ).single()

        assertFalse(episode.isPlayable)
        assertTrue(episode.isDownloading)
        assertFalse(episode.isInDownloadsFolder)
    }

    @Test
    fun `entries are newest first regardless of which store they came from`() {
        val merged = offlineEpisodes(
            downloads = listOf(download("older", EpisodeDownloadState.COMPLETED, updatedAtMs = 100)),
            exported = listOf(exported("newer", exportedAtMs = 500)),
        )

        assertEquals(listOf("newer", "older"), merged.map(OfflineEpisode::id))
    }

    @Test
    fun `exported size wins over downloaded bytes when both are known`() {
        val episode = offlineEpisodes(
            downloads = listOf(download("ep1", EpisodeDownloadState.COMPLETED, bytes = 10)),
            exported = listOf(exported("ep1", sizeBytes = 99)),
        ).single()

        assertEquals(99L, episode.sizeBytes)
    }

    private fun metadata() = EpisodeDownloadMetadata(
        anilistId = 1,
        seriesTitle = "Cowboy Bebop",
        episodeNumber = "3",
        provider = "pewe",
        category = "sub",
    )

    @Test
    fun `offlineSeries groups episodes by anime and sorts them by episode number`() {
        val series = offlineSeries(
            listOf(
                episode(id = "s1e10", anilistId = 1, number = "10"),
                episode(id = "s1e2", anilistId = 1, number = "2"),
                episode(id = "s1e9-5", anilistId = 1, number = "9.5"),
                episode(id = "s2e1", anilistId = 2, number = "1", title = "Bebop Kai"),
            ),
        )

        assertEquals(listOf(1, 2), series.map(OfflineSeries::anilistId))
        assertEquals(
            listOf("s1e2", "s1e9-5", "s1e10"),
            series.first().episodes.map(OfflineEpisode::id),
        )
        assertEquals("Bebop Kai", series[1].title)
    }

    @Test
    fun `offlineSeries sorts sub before dub and non-numeric episodes last`() {
        val series = offlineSeries(
            listOf(
                episode(id = "ova", anilistId = 1, number = "OVA"),
                episode(id = "dub3", anilistId = 1, number = "3", category = "dub"),
                episode(id = "sub3", anilistId = 1, number = "3"),
            ),
        ).single()

        assertEquals(listOf("dub3", "sub3", "ova"), series.episodes.map(OfflineEpisode::id))
    }

    @Test
    fun `offlineSeries aggregates size, counts, and the first available artwork`() {
        val series = offlineSeries(
            listOf(
                episode(id = "e1", anilistId = 1, number = "1", bytes = 100),
                episode(id = "e2", anilistId = 1, number = "2", bytes = 50, artwork = "https://img/e2.jpg"),
                episode(
                    id = "e3", anilistId = 1, number = "3",
                    state = EpisodeDownloadState.DOWNLOADING,
                ),
            ),
        ).single()

        assertEquals("https://img/e2.jpg", series.artworkUrl)
        assertEquals(150L, series.totalBytes)
        assertEquals(2, series.playableCount)
        assertEquals(1, series.activeCount)
    }

    @Test
    fun `offlineSeries orders series by their most recently updated episode`() {
        val series = offlineSeries(
            listOf(
                episode(id = "old", anilistId = 1, number = "1", updatedAtMs = 100),
                episode(id = "new", anilistId = 2, number = "1", title = "Newer", updatedAtMs = 500),
            ),
        )

        assertEquals(listOf(2, 1), series.map(OfflineSeries::anilistId))
    }

    @Test
    fun `offlineEpisodeBlocks labels ranges by their end episodes`() {
        val episodes = (1..150).map { episode(id = "e$it", anilistId = 1, number = "$it") }
        val blocks = offlineEpisodeBlocks(episodes)

        assertEquals(2, blocks.size)
        assertEquals("1 – 100", blocks[0].label)
        assertEquals("101 – 150", blocks[1].label)
        assertEquals(50, blocks[1].episodes.size)
    }

    @Test
    fun `offlineEpisodeBlocks collapses a single-episode range to one number`() {
        val block = offlineEpisodeBlocks(listOf(episode(id = "e7", anilistId = 1, number = "7"))).single()
        assertEquals("7", block.label)
    }

    private fun episode(
        id: String,
        anilistId: Int,
        number: String,
        title: String = "Cowboy Bebop",
        category: String = "sub",
        bytes: Long = 0,
        artwork: String? = null,
        updatedAtMs: Long = 0,
        state: EpisodeDownloadState = EpisodeDownloadState.COMPLETED,
    ) = OfflineEpisode(
        id = id,
        download = EpisodeDownload(
            id = id,
            uri = "https://cdn.example/$id/master.m3u8",
            metadata = EpisodeDownloadMetadata(
                anilistId = anilistId,
                seriesTitle = title,
                episodeNumber = number,
                provider = "pewe",
                category = category,
                artworkUrl = artwork,
            ),
            state = state,
            percent = null,
            bytesDownloaded = bytes,
            contentLength = null,
            updatedAtMs = updatedAtMs,
        ),
        exported = null,
    )

    private fun download(
        id: String,
        state: EpisodeDownloadState,
        updatedAtMs: Long = 0,
        bytes: Long = 0,
    ) = EpisodeDownload(
        id = id,
        uri = "https://cdn.example/$id/master.m3u8",
        metadata = metadata(),
        state = state,
        percent = null,
        bytesDownloaded = bytes,
        contentLength = null,
        updatedAtMs = updatedAtMs,
    )

    private fun exported(
        id: String,
        exportedAtMs: Long = 0,
        sizeBytes: Long = 0,
    ) = ExportedEpisode(
        downloadId = id,
        uri = "content://media/external/downloads/$id",
        fileName = "Episode 3.mp4",
        metadata = metadata(),
        sizeBytes = sizeBytes,
        exportedAtMs = exportedAtMs,
    )
}
