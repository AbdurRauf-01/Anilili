package com.miruronative.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeDownloadUiTest {

    @Test
    fun `downloading reports measured progress`() {
        val badge = badges(downloads = listOf(download(percent = 42f))).getValue(ID)

        assertEquals(EpisodeDownloadStage.DOWNLOADING, badge.stage)
        assertEquals(0.42f, badge.progress!!, 0.001f)
        assertTrue(badge.isBusy)
    }

    @Test
    fun `a queued download has no measurable progress`() {
        val badge = badges(
            downloads = listOf(download(state = EpisodeDownloadState.QUEUED, percent = 12f)),
        ).getValue(ID)

        assertEquals(EpisodeDownloadStage.QUEUED, badge.stage)
        // The percentage belongs to the download that has not started; showing it would be a lie.
        assertNull(badge.progress)
    }

    @Test
    fun `a stopped download is shown as paused with retained progress`() {
        val badge = badges(
            downloads = listOf(download(state = EpisodeDownloadState.STOPPED, percent = 37f)),
        ).getValue(ID)

        assertEquals(EpisodeDownloadStage.PAUSED, badge.stage)
        assertEquals(0.37f, badge.progress!!, 0.001f)
        assertTrue(badge.isBusy)
    }

    @Test
    fun `a running export outranks the completed download beneath it`() {
        val badge = badges(
            downloads = listOf(download(state = EpisodeDownloadState.COMPLETED, percent = 100f)),
            exports = listOf(status(EpisodeExportState.RUNNING, percent = 30)),
        ).getValue(ID)

        assertEquals(EpisodeDownloadStage.CONVERTING, badge.stage)
        assertEquals(0.3f, badge.progress!!, 0.001f)
    }

    @Test
    fun `a pending export only shows once the download itself is done`() {
        val stillDownloading = badges(
            downloads = listOf(download(percent = 20f)),
            exports = listOf(status(EpisodeExportState.PENDING)),
        ).getValue(ID)
        // Mid-download the download's own progress is the more useful thing to show.
        assertEquals(EpisodeDownloadStage.DOWNLOADING, stillDownloading.stage)

        val readyToConvert = badges(
            downloads = listOf(download(state = EpisodeDownloadState.COMPLETED)),
            exports = listOf(status(EpisodeExportState.PENDING)),
        ).getValue(ID)
        assertEquals(EpisodeDownloadStage.CONVERTING, readyToConvert.stage)
        assertNull(readyToConvert.progress)
    }

    @Test
    fun `an exported episode reads as saved with no download left`() {
        val badge = badges(exported = listOf(exported())).getValue(ID)

        assertEquals(EpisodeDownloadStage.SAVED, badge.stage)
        assertEquals(1f, badge.progress!!, 0.001f)
        assertTrue(!badge.isBusy)
    }

    @Test
    fun `a failure anywhere in the chain surfaces as failed`() {
        assertEquals(
            EpisodeDownloadStage.FAILED,
            badges(downloads = listOf(download(state = EpisodeDownloadState.FAILED))).getValue(ID).stage,
        )
        assertEquals(
            EpisodeDownloadStage.FAILED,
            badges(
                downloads = listOf(download(state = EpisodeDownloadState.COMPLETED)),
                exports = listOf(status(EpisodeExportState.FAILED)),
            ).getValue(ID).stage,
        )
    }

    @Test
    fun `an episode being removed stops showing a badge`() {
        val badge = badges(
            downloads = listOf(download(state = EpisodeDownloadState.REMOVING)),
        )[ID]

        assertNull(badge)
    }

    private fun badges(
        downloads: List<EpisodeDownload> = emptyList(),
        exports: List<EpisodeExportStatus> = emptyList(),
        exported: List<ExportedEpisode> = emptyList(),
    ) = episodeDownloadBadges(downloads, exports.associateBy { it.downloadId }, exported)

    private fun metadata() = EpisodeDownloadMetadata(
        anilistId = 1,
        seriesTitle = "Black Torch",
        episodeNumber = "1",
        provider = "anibd",
        category = "sub",
    )

    private fun download(
        state: EpisodeDownloadState = EpisodeDownloadState.DOWNLOADING,
        percent: Float? = null,
    ) = EpisodeDownload(
        id = ID,
        uri = "https://cdn.example/master.m3u8",
        metadata = metadata(),
        state = state,
        percent = percent,
        bytesDownloaded = 0,
        contentLength = null,
        updatedAtMs = 0,
    )

    private fun status(state: EpisodeExportState, percent: Int? = null) =
        EpisodeExportStatus(downloadId = ID, state = state, percent = percent)

    private fun exported() = ExportedEpisode(
        downloadId = ID,
        uri = "content://media/external/downloads/1",
        fileName = "Episode 1.mp4",
        metadata = metadata(),
    )

    private companion object {
        const val ID = "episode:1:sub:1"
    }
}
