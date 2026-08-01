package com.anilili.playback

/**
 * One episode in the offline library, whichever way it happens to be stored.
 *
 * Since exports default to replacing the segment cache with a single MP4, an episode can be held
 * as a Media3 download, as an MP4 in the device's Downloads folder, or briefly as both while an
 * export runs. The library should not care: it shows one card per episode either way, and the
 * player picks whichever copy is actually on disk.
 */
data class OfflineEpisode(
    val id: String,
    val download: EpisodeDownload?,
    val exported: ExportedEpisode?,
) {
    val metadata: EpisodeDownloadMetadata
        get() = download?.metadata ?: requireNotNull(exported).metadata

    val updatedAtMs: Long
        get() = maxOf(download?.updatedAtMs ?: 0L, exported?.exportedAtMs ?: 0L)

    /** Whether something on this device can actually be played right now. */
    val isPlayable: Boolean get() = download?.isComplete == true || exported != null

    val isDownloading: Boolean get() = download?.isActive == true

    /** True once the MP4 exists, whether or not the library copy was kept alongside it. */
    val isInDownloadsFolder: Boolean get() = exported != null

    val sizeBytes: Long
        get() = exported?.sizeBytes?.takeIf { it > 0 } ?: download?.bytesDownloaded ?: 0L
}

/**
 * Folds Media3's download index together with the app's exported-MP4 records into one list, newest
 * first. An episode present in both — an export that kept the library copy, or one still running —
 * collapses to a single entry rather than appearing twice.
 */
fun offlineEpisodes(
    downloads: List<EpisodeDownload>,
    exported: List<ExportedEpisode>,
): List<OfflineEpisode> {
    val exportedById = exported.associateBy(ExportedEpisode::downloadId)
    val fromDownloads = downloads.map { download ->
        OfflineEpisode(download.id, download, exportedById[download.id])
    }
    val downloadIds = downloads.mapTo(mutableSetOf(), EpisodeDownload::id)
    val exportOnly = exported
        .filterNot { it.downloadId in downloadIds }
        .map { OfflineEpisode(it.downloadId, null, it) }
    return (fromDownloads + exportOnly).sortedByDescending(OfflineEpisode::updatedAtMs)
}

/**
 * One anime's worth of offline episodes. AniList ids scope separate seasons, so a split-cour
 * show naturally appears once per season — the same seasons the streaming detail page offers.
 */
data class OfflineSeries(
    val anilistId: Int,
    val title: String,
    val artworkUrl: String?,
    val episodes: List<OfflineEpisode>,
) {
    val totalBytes: Long get() = episodes.sumOf(OfflineEpisode::sizeBytes)
    val playableCount: Int get() = episodes.count(OfflineEpisode::isPlayable)
    val activeCount: Int get() = episodes.count(OfflineEpisode::isDownloading)
}

/**
 * Groups the flat offline library by series for the Library screen. Episodes inside a series are
 * ordered by episode number (decimals like 87.5 included, unparseable numbers last) and then by
 * category so sub and dub never interleave. Series are ordered by their most recent episode.
 */
fun offlineSeries(episodes: List<OfflineEpisode>): List<OfflineSeries> =
    episodes
        .groupBy { it.metadata.anilistId }
        .map { (anilistId, group) ->
            val sorted = group.sortedWith(
                compareBy(
                    { episodeNumberSortKey(it.metadata.episodeNumber) },
                    { it.metadata.category },
                ),
            )
            val newest = group.maxBy(OfflineEpisode::updatedAtMs)
            OfflineSeries(
                anilistId = anilistId,
                title = newest.metadata.seriesTitle,
                artworkUrl = group.firstNotNullOfOrNull { it.metadata.artworkUrl },
                episodes = sorted,
            )
        }
        .sortedByDescending { series -> series.episodes.maxOf(OfflineEpisode::updatedAtMs) }

/** Numeric sort key for episode numbers; non-numeric labels (OVA, S1) sort after every number. */
fun episodeNumberSortKey(raw: String): Double =
    raw.trim().toDoubleOrNull() ?: Double.MAX_VALUE

/** One range chip in the series download browser, labelled by the episode numbers at its ends. */
data class OfflineEpisodeBlock(val label: String, val episodes: List<OfflineEpisode>)

/**
 * Slices a series' episodes into labelled ranges the same way the streaming episode browser does
 * (positional chunking, so gaps and decimal episodes still produce even blocks).
 */
fun offlineEpisodeBlocks(
    episodes: List<OfflineEpisode>,
    blockSize: Int = 100,
): List<OfflineEpisodeBlock> = episodes.chunked(blockSize).map { chunk ->
    val first = chunk.first().metadata.episodeNumber
    val last = chunk.last().metadata.episodeNumber
    OfflineEpisodeBlock(if (first == last) first else "$first – $last", chunk)
}
