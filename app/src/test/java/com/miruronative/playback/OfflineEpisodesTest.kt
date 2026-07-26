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
