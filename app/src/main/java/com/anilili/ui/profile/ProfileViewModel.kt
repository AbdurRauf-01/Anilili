package com.anilili.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.AppGraph
import com.anilili.data.auth.AccountService
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.model.MediaListEntry
import com.anilili.data.model.MediaListCollection
import com.anilili.data.model.Viewer
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.MalExport
import com.anilili.data.library.MalExportEntry
import com.anilili.data.library.MalExportFile
import com.anilili.data.library.MalImport
import com.anilili.data.library.MalImportSummary
import com.anilili.data.library.WatchlistEntry
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.UiState
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import java.io.IOException

data class AniListProfile(
    val viewer: Viewer,
    val watching: List<MediaListEntry>,
    val rewatching: List<MediaListEntry>,
    val planning: List<MediaListEntry>,
    val paused: List<MediaListEntry>,
    val completed: List<MediaListEntry>,
    val dropped: List<MediaListEntry>,
    /** Which service these lists came from; AniList and MAL entries share this shape. */
    val service: AccountService = AccountService.ANILIST,
)

enum class MalImportStage {
    READING,
    PARSING,
    MATCHING,
    SAVING,
}

data class MalImportProgress(
    val stage: MalImportStage,
    val completed: Int = 0,
    val total: Int = 0,
) {
    val label: String
        get() = when (stage) {
            MalImportStage.READING -> "Reading file"
            MalImportStage.PARSING -> "Parsing list"
            MalImportStage.MATCHING -> if (total > 0) {
                "Matching titles $completed/$total"
            } else {
                "Matching titles"
            }
            MalImportStage.SAVING -> "Saving watchlist"
        }
}

class ProfileViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _profile = MutableStateFlow<UiState<AniListProfile>?>(null)
    val profile = _profile.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun loadIfLoggedIn(refresh: Boolean = false) {
        val service = AccountService.active
        if (service == null) {
            _profile.value = null
            return
        }
        viewModelScope.launch {
            if (refresh && _profile.value is UiState.Success) _isRefreshing.value = true else _profile.value = UiState.Loading
            try {
                val (viewer, entries) = when (service) {
                    AccountService.ANILIST -> {
                        val viewer = repo.viewer() ?: error("Couldn't load your AniList profile")
                        viewer to repo.userAnimeList(viewer.id).allEntries()
                    }
                    AccountService.MAL -> repo.malViewer() to repo.malAnimeList()
                }
                val watching = entries.filter { it.status == "CURRENT" }
                val rewatching = entries.filter { it.status == "REPEATING" }
                val planning = entries.filter { it.status == "PLANNING" }
                val paused = entries.filter { it.status == "PAUSED" }
                val completed = entries.filter { it.status == "COMPLETED" }
                val dropped = entries.filter { it.status == "DROPPED" }
                LibraryStore.hydrateRemoteLibrary(entries)
                _profile.value = UiState.Success(
                    AniListProfile(viewer, watching, rewatching, planning, paused, completed, dropped, service),
                )
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _profile.value = UiState.Error(e.message ?: "Failed to load ${service.label}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refresh() = loadIfLoggedIn(refresh = true)

    fun onLoggedIn(token: String) {
        AuthManager.setToken(token)
        // AccountService promises one active service. Only clear the previous service after the
        // replacement login has succeeded, so a cancelled login never disconnects the user.
        MalAuthManager.logout()
        LibraryStore.syncSavedToRemote()
        loadIfLoggedIn()
    }

    /** MAL redirect hands back a code; trade it for tokens before loading the profile. */
    fun onMalCode(code: String) {
        _profile.value = UiState.Loading
        viewModelScope.launch {
            try {
                MalAuthManager.exchangeCode(code)
                AuthManager.logout()
                LibraryStore.syncSavedToRemote()
                loadIfLoggedIn()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                MalAuthManager.logout()
                _profile.value = UiState.Error(e.message ?: "MyAnimeList login failed")
            }
        }
    }

    fun logout() {
        AuthManager.logout()
        MalAuthManager.logout()
        LibraryStore.clearRemoteLibrary()
        _profile.value = null
    }

    /**
     * Imports a MyAnimeList XML export into the local watchlist. Every entry the file carries
     * is saved (the file is the user's own list); titles AniList can't map from a MAL id are
     * counted as unmatched rather than failing the run.
     */
    suspend fun importMalXml(
        bytes: ByteArray,
        onProgress: (MalImportProgress) -> Unit = {},
    ): MalImportSummary = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        var stage = MalImportStage.PARSING
        DiagnosticsLog.event(
            category = "mal_import",
            name = "import.started",
            attributes = mapOf("sourceBytes" to bytes.size),
        )
        try {
            reportMalImportProgress(onProgress, MalImportProgress(stage))
            val parsed = MalImport.parse(bytes)
            if (parsed.isEmpty()) error("No anime entries found in that file")

            val chunks = parsed.map { it.malId }.distinct().chunked(MAL_IMPORT_BATCH_SIZE)
            val matchingTimeoutMs = malImportMatchTimeoutMs(chunks.size)
            DiagnosticsLog.event(
                category = "mal_import",
                name = "matching.started",
                attributes = mapOf(
                    "entries" to parsed.size,
                    "batches" to chunks.size,
                    "timeoutMs" to matchingTimeoutMs,
                ),
            )
            val resolvedMedia = buildList {
                stage = MalImportStage.MATCHING
                try {
                    withTimeout(matchingTimeoutMs) {
                        chunks.forEachIndexed { index, chunk ->
                            reportMalImportProgress(
                                onProgress,
                                MalImportProgress(
                                    stage = stage,
                                    completed = index,
                                    total = chunks.size,
                                ),
                            )
                            val batchStartedAt = System.nanoTime()
                            val matched = repo.mediaByMalIds(chunk)
                            addAll(matched)
                            DiagnosticsLog.event(
                                category = "mal_import",
                                name = "matching.batch_completed",
                                attributes = mapOf(
                                    "batch" to (index + 1),
                                    "batches" to chunks.size,
                                    "requested" to chunk.size,
                                    "matched" to matched.size,
                                    "durationMs" to elapsedMs(batchStartedAt),
                                ),
                            )
                            reportMalImportProgress(
                                onProgress,
                                MalImportProgress(
                                    stage = stage,
                                    completed = index + 1,
                                    total = chunks.size,
                                ),
                            )
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    throw IOException(
                        "MAL import timed out while matching titles with AniList. " +
                            "Check your connection, private DNS, or VPN, then try again.",
                        error,
                    )
                }
            }
            val mediaByMalId = resolvedMedia
                .filter { it.idMal != null }
                .associateBy { it.idMal!! }
            val entries = parsed.mapNotNull { entry ->
                val media = mediaByMalId[entry.malId] ?: return@mapNotNull null
                WatchlistEntry(
                    anilistId = media.id,
                    title = media.title.preferred,
                    cover = media.coverImage.best,
                    format = media.format,
                    averageScore = media.averageScore,
                )
            }
            stage = MalImportStage.SAVING
            reportMalImportProgress(onProgress, MalImportProgress(stage))
            // Nothing is written until every AniList batch succeeds. Cancellation or a timeout
            // therefore leaves the existing watchlist unchanged instead of half-imported.
            val added = LibraryStore.importWatchlist(entries)
            val summary = MalImportSummary(
                totalEntries = parsed.size,
                added = added,
                alreadySaved = entries.size - added,
                unmatched = parsed.size - entries.size,
            )
            DiagnosticsLog.event(
                category = "mal_import",
                name = "import.completed",
                attributes = mapOf(
                    "entries" to summary.totalEntries,
                    "added" to summary.added,
                    "alreadySaved" to summary.alreadySaved,
                    "unmatched" to summary.unmatched,
                    "durationMs" to elapsedMs(startedAt),
                ),
            )
            summary
        } catch (error: CancellationException) {
            DiagnosticsLog.event(
                category = "mal_import",
                name = "import.cancelled",
                attributes = mapOf(
                    "stage" to stage.name.lowercase(),
                    "durationMs" to elapsedMs(startedAt),
                ),
            )
            throw error
        } catch (error: Exception) {
            DiagnosticsLog.event(
                category = "mal_import",
                name = "import.failed",
                attributes = mapOf(
                    "stage" to stage.name.lowercase(),
                    "durationMs" to elapsedMs(startedAt),
                    "errorType" to error.javaClass.simpleName,
                    "message" to (error.message ?: "none"),
                ),
            )
            throw error
        }
    }

    suspend fun buildMalExport(
        profile: AniListProfile?,
        watchlist: List<WatchlistEntry>,
        history: List<HistoryEntry>,
    ): MalExportFile = withContext(Dispatchers.IO) {
        val entries = LinkedHashMap<Int, MalExportEntry>()
        var skipped = 0

        suspend fun addMediaList(entry: MediaListEntry) {
            val media = entry.media ?: run {
                skipped++
                return
            }
            val resolved = if (media.idMal != null) media else repo.animeInfo(media.id) ?: media
            val (status, rewatching) = MalExport.statusFromAniList(entry.status)
            val exportEntry = MalExport.entryFromMedia(
                media = resolved,
                status = status,
                progress = entry.progress,
                score = entry.score,
                rewatching = rewatching,
            )
            if (exportEntry == null) skipped++ else entries[resolved.id] = exportEntry
        }

        listOf(
            profile?.watching.orEmpty(),
            profile?.rewatching.orEmpty(),
            profile?.completed.orEmpty(),
            profile?.paused.orEmpty(),
            profile?.dropped.orEmpty(),
            profile?.planning.orEmpty(),
        ).flatten().forEach { addMediaList(it) }

        val historyById = history.associateBy { it.anilistId }
        watchlist.forEach { saved ->
            if (entries.containsKey(saved.anilistId)) return@forEach
            val media = repo.animeInfo(saved.anilistId) ?: run {
                skipped++
                return@forEach
            }
            val progress = historyById[saved.anilistId]?.episodeNumber?.toInt() ?: 0
            val exportEntry = MalExport.entryFromMedia(
                media = media,
                status = MalExport.statusFromLocal(progress, media.episodes),
                progress = progress,
            )
            if (exportEntry == null) skipped++ else entries[saved.anilistId] = exportEntry
        }

        history.forEach { item ->
            // Remote-seeded rows point at the NEXT unwatched episode and mirror the service's
            // own list anyway; exporting them would overstate progress by one.
            if (item.fromRemote) return@forEach
            if (entries.containsKey(item.anilistId)) return@forEach
            val media = repo.animeInfo(item.anilistId) ?: run {
                skipped++
                return@forEach
            }
            val progress = item.episodeNumber.toInt()
            val exportEntry = MalExport.entryFromMedia(
                media = media,
                status = MalExport.statusFromLocal(progress, media.episodes),
                progress = progress,
            )
            if (exportEntry == null) skipped++ else entries[item.anilistId] = exportEntry
        }

        MalExport.fromEntries(profile?.viewer?.name, entries.values.toList(), skipped)
    }
}

private fun elapsedMs(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000L

private suspend fun reportMalImportProgress(
    callback: (MalImportProgress) -> Unit,
    progress: MalImportProgress,
) = withContext(Dispatchers.Main.immediate) {
    callback(progress)
}

internal fun malImportMatchTimeoutMs(batchCount: Int): Long =
    (MAL_IMPORT_BASE_TIMEOUT_MS + batchCount.coerceAtLeast(1) * MAL_IMPORT_PER_BATCH_TIMEOUT_MS)
        .coerceAtMost(MAL_IMPORT_MAX_TIMEOUT_MS)

private const val MAL_IMPORT_BATCH_SIZE = 50
private const val MAL_IMPORT_BASE_TIMEOUT_MS = 3L * 60L * 1_000L
private const val MAL_IMPORT_PER_BATCH_TIMEOUT_MS = 5_000L
private const val MAL_IMPORT_MAX_TIMEOUT_MS = 10L * 60L * 1_000L

/** Custom-list-only entries are duplicated across groups; flatten and classify by entry status. */
internal fun MediaListCollection?.allEntries(): List<MediaListEntry> = this?.lists.orEmpty()
    .flatMap { it.entries }
    .distinctBy { it.id }
