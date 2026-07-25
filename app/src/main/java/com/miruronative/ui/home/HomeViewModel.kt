package com.miruronative.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HomeTab(val label: String) {
    NEWEST("NEWEST"),
    POPULAR("POPULAR"),
    MOVIES("MOVIES"),
    TOP_RATED("TOP RATED"),
}

data class HomeData(
    val spotlight: List<Media>,
    val newest: List<Media>,
    val popular: List<Media>,
    val movies: List<Media>,
    val topRated: List<Media>,
) {
    fun tab(tab: HomeTab): List<Media> = when (tab) {
        HomeTab.NEWEST -> newest
        HomeTab.POPULAR -> popular
        HomeTab.MOVIES -> movies
        HomeTab.TOP_RATED -> topRated
    }
}

class HomeViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<HomeData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    /** Per-section paging cursor. Rebuilt on every load so a refresh starts from page 1 again. */
    private class Paging {
        var page = 1
        var loading = false
        var hasMore = true
    }

    private val tabPaging = HomeTab.entries.associateWith { Paging() }
    private val trendingPaging = Paging()

    /** Sections with a page in flight, so the Load More control can show progress and disable. */
    private val _loadingMore = MutableStateFlow<Set<HomeTab>>(emptySet())
    val loadingMore = _loadingMore.asStateFlow()

    /** Sections AniList says are exhausted, so the Load More control can retire itself. */
    private val _exhausted = MutableStateFlow<Set<HomeTab>>(emptySet())
    val exhausted = _exhausted.asStateFlow()

    /**
     * Bumped on every completed load so in-flight paging can tell it belongs to a previous
     * catalog. Without it a Load More that started before a pull-to-refresh would finish
     * afterwards and republish the pre-refresh list it had captured.
     */
    private var catalogGeneration = 0

    var selectedTab by mutableStateOf(HomeTab.POPULAR)
        private set

    init { load() }

    fun selectTab(tab: HomeTab) { selectedTab = tab }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            DiagnosticsLog.event("Home load start force=$force")
            if (force && _state.value is UiState.Success) _isRefreshing.value = true else _state.value = UiState.Loading
            try {
                val collections = repo.homeCollections(force)
                val data = HomeData(
                    spotlight = collections.spotlight,
                    newest = collections.newest,
                    popular = collections.popular,
                    movies = collections.movies,
                    topRated = collections.topRated,
                )
                catalogGeneration++
                tabPaging.values.forEach { it.page = 1; it.hasMore = true }
                trendingPaging.page = 1
                trendingPaging.hasMore = true
                _exhausted.value = emptySet()
                _loadingMore.value = emptySet()
                DiagnosticsLog.event(
                    "Home load success spotlight=${data.spotlight.size} newest=${data.newest.size} " +
                        "popular=${data.popular.size} movies=${data.movies.size} topRated=${data.topRated.size}",
                )
                _state.value = UiState.Success(data)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Home load failed", e)
                _state.value = UiState.Error(e.message ?: "Failed to load home")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadMoreTab(tab: HomeTab) {
        val paging = tabPaging.getValue(tab)
        if (paging.loading || !paging.hasMore) return
        if (_state.value !is UiState.Success) return
        paging.loading = true
        _loadingMore.value = _loadingMore.value + tab

        viewModelScope.launch {
            val generation = catalogGeneration
            try {
                val nextPage = paging.page + 1
                val page = when (tab) {
                    HomeTab.POPULAR -> repo.popular(nextPage)
                    HomeTab.NEWEST -> repo.recentlyReleased(nextPage)
                    HomeTab.MOVIES -> repo.movies(nextPage)
                    HomeTab.TOP_RATED -> repo.topRated(nextPage)
                }
                // A refresh landed while this page was in flight; its results belong to a catalog
                // that no longer exists, and appending them would resurrect the old list.
                if (generation != catalogGeneration) return@launch
                val current = (_state.value as? UiState.Success)?.data ?: return@launch

                paging.page = nextPage
                // AniList tells us directly when a section runs out — no need to guess from an
                // empty page, and no need to leave a button that can no longer do anything.
                paging.hasMore = page.hasNextPage && page.items.isNotEmpty()
                if (!paging.hasMore) _exhausted.value = _exhausted.value + tab

                if (page.items.isNotEmpty()) {
                    val updatedList = (current.tab(tab) + page.items).distinctBy { it.id }
                    _state.value = UiState.Success(
                        when (tab) {
                            HomeTab.POPULAR -> current.copy(popular = updatedList)
                            HomeTab.NEWEST -> current.copy(newest = updatedList)
                            HomeTab.MOVIES -> current.copy(movies = updatedList)
                            HomeTab.TOP_RATED -> current.copy(topRated = updatedList)
                        },
                    )
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                // The page stays un-advanced, so the button remains live for another attempt.
                DiagnosticsLog.throwable("Home load more failed tab=${tab.name}", e)
            } finally {
                paging.loading = false
                _loadingMore.value = _loadingMore.value - tab
            }
        }
    }

    fun loadMoreTrending() {
        if (trendingPaging.loading || !trendingPaging.hasMore) return
        if (_state.value !is UiState.Success) return
        trendingPaging.loading = true

        viewModelScope.launch {
            val generation = catalogGeneration
            try {
                val nextPage = trendingPaging.page + 1
                val page = repo.trending(nextPage)
                if (generation != catalogGeneration) return@launch
                val current = (_state.value as? UiState.Success)?.data ?: return@launch

                trendingPaging.page = nextPage
                trendingPaging.hasMore = page.hasNextPage && page.items.isNotEmpty()
                if (page.items.isNotEmpty()) {
                    val updatedList = (current.spotlight + page.items).distinctBy { it.id }
                    _state.value = UiState.Success(current.copy(spotlight = updatedList))
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Home load more trending failed", e)
            } finally {
                trendingPaging.loading = false
            }
        }
    }

    fun refresh() = load(force = true)
}
