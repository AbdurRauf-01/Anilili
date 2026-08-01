@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.anilili.ui.detail

import android.view.inputmethod.EditorInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.anilili.data.library.HistoryEntry
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.Media
import com.anilili.data.settings.SettingsStore
import kotlinx.coroutines.launch
import com.anilili.data.model.StudioNode
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.TvFocusTarget
import com.anilili.ui.adaptive.tvFocusTarget
import com.anilili.ui.components.EpisodeArtwork
import com.anilili.ui.components.EPISODE_BROWSER_MIN_EPISODES
import com.anilili.ui.components.TvHeroArtwork
import com.anilili.ui.components.WatchProgressBar
import com.anilili.ui.components.blockIndexContaining
import com.anilili.ui.components.episodeBlocks
import com.anilili.ui.components.episodeArtworkImage
import com.anilili.ui.components.episodeWatchFraction
import com.anilili.ui.components.filterEpisodes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val TvDetailPadding = 48.dp
private val TvDetailCardShape = RoundedCornerShape(12.dp)
private val TvEpisodeCardWidth = 270.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvDetailContent(
    data: DetailData,
    saved: Boolean,
    listStatusLabel: String?,
    resume: HistoryEntry?,
    history: List<HistoryEntry>,
    onToggleSaved: () -> Unit,
    onPlay: (animeId: Int, provider: String, category: String, episode: String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onStudioClick: (StudioNode) -> Unit,
    onSelectSeason: (Int) -> Unit,
    primaryActionFocusRequester: FocusRequester,
    onPrimaryFocusAcquired: () -> Unit,
) {
    val info = data.info
    val episodes = data.episodes
    val seasonResume = history.firstOrNull { it.anilistId == data.selectedSeasonId }
    val episodeKeys = episodes.map(EpisodeItem::pipeId)
    val blocks = remember(data.selectedSeasonId, episodeKeys) { episodeBlocks(episodes) }
    var selectedBlockIndex by remember(data.selectedSeasonId, episodeKeys) {
        mutableIntStateOf(blockIndexContaining(blocks, seasonResume?.episodeNumber))
    }
    var episodeQuery by remember(data.selectedSeasonId, episodeKeys) { mutableStateOf("") }
    var latestJumpRequest by remember(data.selectedSeasonId, episodeKeys) { mutableIntStateOf(0) }
    val blockIndex = selectedBlockIndex.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
    val shownEpisodes = if (episodeQuery.isBlank()) {
        blocks.getOrNull(blockIndex)?.episodes.orEmpty()
    } else {
        filterEpisodes(episodes, episodeQuery)
    }
    val fallbackImage = data.seasons.firstOrNull { it.id == data.selectedSeasonId }
        ?.let { it.bannerImage ?: it.coverImage.best }
        ?: info.bannerImage
        ?: info.coverImage.best
    val episodeFocusTarget = remember(data.selectedSeasonId, blockIndex) { TvFocusTarget() }
    val playCurrent: () -> Unit = {
        when {
            resume != null -> onPlay(info.id, resume.provider, resume.category, resume.episodeLabel)
            episodes.isNotEmpty() -> onPlay(
                data.selectedSeasonId,
                "auto",
                data.preferredCategory.api,
                episodes.first().displayNumber,
            )
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        TvHeroArtwork(
            media = info,
            posterEndPadding = 42,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.94f),
                    .46f to Color.Black.copy(alpha = 0.76f),
                    1f to Color.Black.copy(alpha = 0.52f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.26f),
                    .72f to Color.Black.copy(alpha = 0.28f),
                    1f to Color.Black.copy(alpha = 0.92f),
                ),
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 78.dp),
        ) {
            TvDetailInfoPane(
                info = info,
                resume = resume,
                saved = saved,
                listStatusLabel = listStatusLabel,
                canWatch = episodes.isNotEmpty() || resume != null,
                related = data.series.filter { it.id != info.id },
                onWatch = playCurrent,
                onToggleSaved = onToggleSaved,
                onAnimeClick = onAnimeClick,
                onStudioClick = onStudioClick,
                primaryActionFocusRequester = primaryActionFocusRequester,
                episodeFocusTarget = episodeFocusTarget.takeIf { shownEpisodes.isNotEmpty() },
                onPrimaryFocusAcquired = onPrimaryFocusAcquired,
                modifier = Modifier.weight(0.47f).fillMaxHeight(),
            )
            TvEpisodeBrowserPane(
                episodes = shownEpisodes,
                allEpisodeCount = episodes.size,
                seasons = data.seasons,
                selectedSeasonId = data.selectedSeasonId,
                blocks = blocks.map { it.label },
                selectedBlockIndex = blockIndex,
                query = episodeQuery,
                fallbackImage = fallbackImage,
                resume = seasonResume,
                loading = data.seasonEpisodesLoading,
                episodeFocusTarget = episodeFocusTarget,
                leftFocusRequester = primaryActionFocusRequester,
                onSelectSeason = onSelectSeason,
                onSelectBlock = {
                    episodeQuery = ""
                    selectedBlockIndex = it
                },
                onQueryChange = { episodeQuery = it },
                onJumpToLatest = {
                    episodeQuery = ""
                    selectedBlockIndex = blocks.lastIndex.coerceAtLeast(0)
                    latestJumpRequest++
                },
                latestJumpRequest = latestJumpRequest,
                onPlay = { episode ->
                    onPlay(
                        data.selectedSeasonId,
                        "auto",
                        data.preferredCategory.api,
                        episode.displayNumber,
                    )
                },
                modifier = Modifier
                    .weight(0.53f)
                    .fillMaxHeight()
                    .padding(end = 32.dp, bottom = 26.dp),
            )
        }
    }
}

@Composable
private fun TvDetailInfoPane(
    info: Media,
    resume: HistoryEntry?,
    saved: Boolean,
    listStatusLabel: String?,
    canWatch: Boolean,
    related: List<Media>,
    onWatch: () -> Unit,
    onToggleSaved: () -> Unit,
    onAnimeClick: (Int) -> Unit,
    onStudioClick: (StudioNode) -> Unit,
    primaryActionFocusRequester: FocusRequester,
    episodeFocusTarget: TvFocusTarget?,
    onPrimaryFocusAcquired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = remember(info.description) {
        info.description
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.trim()
            .orEmpty()
    }
    val studio = info.studios.nodes.firstOrNull { it.isAnimationStudio && !it.name.isNullOrBlank() }

    Column(
        modifier = modifier.padding(start = TvDetailPadding, end = 26.dp, top = 28.dp),
    ) {
        Text(
            text = info.title.preferred,
            color = Color.White,
            fontSize = 36.sp,
            lineHeight = 39.sp,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TvDetailMetadata(info, maxGenres = 1)
        if (description.isNotBlank()) {
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onWatch,
                enabled = canWatch,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
                contentPadding = PaddingValues(horizontal = 19.dp, vertical = 9.dp),
                modifier = Modifier
                    .focusRequester(primaryActionFocusRequester)
                    .then(
                        episodeFocusTarget?.takeIf(TvFocusTarget::isAttached)?.let { target ->
                            Modifier.focusProperties { right = target.requester }
                        } ?: Modifier,
                    )
                    .onFocusChanged {
                        if (it.isFocused || it.hasFocus) onPrimaryFocusAcquired()
                    }
                    .focusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.04f),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(21.dp))
                Text(
                    resume?.let { "Continue E${it.episodeLabel}" } ?: "Watch",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
            OutlinedButton(
                onClick = onToggleSaved,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.52f),
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 17.dp, vertical = 9.dp),
                modifier = Modifier.focusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.04f),
            ) {
                Icon(
                    if (saved || listStatusLabel != null) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    listStatusLabel ?: if (saved) "In library" else "Add to list",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 12.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            studio?.let { selectedStudio ->
                item(key = "studio") {
                    Text(
                        text = selectedStudio.name.orEmpty(),
                        color = Color.White.copy(alpha = 0.72f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .focusHighlight(RoundedCornerShape(9.dp), focusedScale = 1.03f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.Black.copy(alpha = 0.42f))
                            .clickable(enabled = selectedStudio.id > 0) { onStudioClick(selectedStudio) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            item(key = "about") {
                Text(
                    text = "About",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                val facts = buildList {
                    info.status?.let { add(tvDetailPretty(it)) }
                    info.episodes?.let { add("$it episodes") }
                    info.season?.let { add(tvDetailPretty(it)) }
                }.joinToString("  •  ")
                if (facts.isNotBlank()) {
                    Text(
                        text = facts,
                        color = Color.White.copy(alpha = 0.66f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                info.nextAiringEpisode?.airingAt?.let { airingAt ->
                    val date = Instant.ofEpochSecond(airingAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a"))
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            "Episode ${info.nextAiringEpisode.episode ?: "?"}  •  $date",
                            color = Color.White.copy(alpha = 0.66f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 7.dp),
                        )
                    }
                }
            }
            if (related.isNotEmpty()) {
                item(key = "related") {
                    TvRelatedCompactRail(
                        related = related,
                        episodeFocusTarget = episodeFocusTarget,
                        onAnimeClick = onAnimeClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvRelatedCompactRail(
    related: List<Media>,
    episodeFocusTarget: TvFocusTarget?,
    onAnimeClick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            text = "Related",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        LazyRow(
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 5.dp),
        ) {
            items(related, key = { it.id }) { media ->
                Column(
                    modifier = Modifier
                        .width(142.dp)
                        .then(
                            episodeFocusTarget?.takeIf(TvFocusTarget::isAttached)?.let { target ->
                                Modifier.focusProperties { right = target.requester }
                            } ?: Modifier,
                        )
                        .focusHighlight(RoundedCornerShape(9.dp), focusedScale = 1.04f)
                        .clickable { onAnimeClick(media.id) },
                ) {
                    AsyncImage(
                        model = media.bannerImage ?: media.coverImage.best,
                        contentDescription = media.title.preferred,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        text = media.title.preferred,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvEpisodeBrowserPane(
    episodes: List<EpisodeItem>,
    allEpisodeCount: Int,
    seasons: List<Media>,
    selectedSeasonId: Int,
    blocks: List<String>,
    selectedBlockIndex: Int,
    query: String,
    fallbackImage: String?,
    resume: HistoryEntry?,
    loading: Boolean,
    episodeFocusTarget: TvFocusTarget,
    leftFocusRequester: FocusRequester,
    onSelectSeason: (Int) -> Unit,
    onSelectBlock: (Int) -> Unit,
    onQueryChange: (String) -> Unit,
    onJumpToLatest: () -> Unit,
    latestJumpRequest: Int,
    onPlay: (EpisodeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blurEpisodeImages by SettingsStore.blurEpisodeImages.collectAsState()
    val listState = rememberLazyListState()
    val resumeIndex = episodes.indexOfFirst { it.number == resume?.episodeNumber }.coerceAtLeast(0)
    val latestButtonFocusRequester = remember { FocusRequester() }
    val latestEpisodeFocusRequester = remember { FocusRequester() }
    var handledLatestJumpRequest by remember { mutableIntStateOf(latestJumpRequest) }

    LaunchedEffect(
        selectedSeasonId,
        selectedBlockIndex,
        episodes.map(EpisodeItem::pipeId),
        latestJumpRequest,
    ) {
        val jumpingToLatest = latestJumpRequest > handledLatestJumpRequest
        val targetIndex = tvEpisodeScrollIndex(
            episodes = episodes,
            resumeEpisode = resume?.episodeNumber,
            jumpToLatest = jumpingToLatest,
        )
        if (targetIndex >= 0) listState.scrollToItem(targetIndex)
        if (jumpingToLatest && targetIndex >= 0) {
            repeat(10) {
                withFrameNanos {}
                if (runCatching { latestEpisodeFocusRequester.requestFocus() }.isSuccess) {
                    handledLatestJumpRequest = latestJumpRequest
                    return@LaunchedEffect
                }
            }
        }
        handledLatestJumpRequest = latestJumpRequest
    }

    val requestFirstEpisodeFocus: () -> Unit = {
        if (episodeFocusTarget.isAttached) runCatching { episodeFocusTarget.requester.requestFocus() }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(top = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "Episodes",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "$allEpisodeCount total",
                color = Color.White.copy(alpha = 0.52f),
                fontSize = 12.sp,
            )
        }

        if (seasons.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusGroup(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(seasons, key = { _, season -> season.id }) { index, season ->
                    TvDetailFilterChip(
                        label = "Season ${index + 1}",
                        selected = season.id == selectedSeasonId,
                        onClick = { onSelectSeason(season.id) },
                    )
                }
            }
        }

        if (blocks.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp).focusGroup(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(blocks) { index, label ->
                    TvDetailFilterChip(
                        label = label,
                        selected = index == selectedBlockIndex,
                        onClick = { onSelectBlock(index) },
                    )
                }
            }
        }

        if (allEpisodeCount >= EPISODE_BROWSER_MIN_EPISODES) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 9.dp)
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvNativeTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    hint = "Find episode number or title",
                    modifier = Modifier.weight(1f),
                    imeAction = EditorInfo.IME_ACTION_SEARCH,
                    onImeAction = requestFirstEpisodeFocus,
                    onMoveDown = requestFirstEpisodeFocus,
                    onMoveRight = { runCatching { latestButtonFocusRequester.requestFocus() } },
                )
                TvDetailFilterChip(
                    label = "Latest",
                    selected = false,
                    onClick = onJumpToLatest,
                    modifier = Modifier.focusRequester(latestButtonFocusRequester),
                )
                if (query.isNotBlank()) {
                    TvDetailFilterChip(
                        label = "Clear",
                        selected = false,
                        onClick = { onQueryChange("") },
                    )
                }
            }
        }

        when {
            episodes.isNotEmpty() -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 12.dp)
                    .focusGroup(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                itemsIndexed(episodes, key = { _, episode -> episode.pipeId }) { index, episode ->
                    TvEpisodeListRow(
                        episode = episode,
                        image = episodeArtworkImage(episode.image, fallbackImage),
                        blurred = blurEpisodeImages,
                        onBlurredChange = SettingsStore::setBlurEpisodeImages,
                        watchedFraction = episodeWatchFraction(resume, episode.number),
                        isResumeEpisode = episode.number == resume?.episodeNumber,
                        focusTarget = episodeFocusTarget.takeIf { index == resumeIndex },
                        focusRequester = latestEpisodeFocusRequester.takeIf { index == episodes.lastIndex },
                        leftFocusRequester = leftFocusRequester,
                        blockDown = index == episodes.lastIndex,
                        onClick = { onPlay(episode) },
                    )
                }
            }
            query.isNotBlank() -> Text(
                text = "No episode matches “$query”.",
                color = Color.White.copy(alpha = 0.58f),
                modifier = Modifier.padding(20.dp),
            )
            loading -> Text(
                text = "Loading episodes…",
                color = Color.White.copy(alpha = 0.58f),
                modifier = Modifier.padding(20.dp),
            )
            else -> Text(
                text = "Episode information is not available yet.",
                color = Color.White.copy(alpha = 0.58f),
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

internal fun tvEpisodeScrollIndex(
    episodes: List<EpisodeItem>,
    resumeEpisode: Double?,
    jumpToLatest: Boolean,
): Int = when {
    episodes.isEmpty() -> -1
    jumpToLatest -> episodes.lastIndex
    episodes.size <= 6 -> 0
    else -> episodes.indexOfFirst { it.number == resumeEpisode }.coerceAtLeast(0)
}

@Composable
private fun TvDetailFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.64f),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 12.sp,
        modifier = modifier
            .focusHighlight(RoundedCornerShape(9.dp), focusedScale = 1.05f)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                else Color.White.copy(alpha = 0.07f),
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

@Composable
private fun TvEpisodeListRow(
    episode: EpisodeItem,
    image: String?,
    blurred: Boolean,
    onBlurredChange: (Boolean) -> Unit,
    watchedFraction: Float,
    isResumeEpisode: Boolean,
    focusTarget: TvFocusTarget?,
    focusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester,
    blockDown: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.018f else 1f, label = "tv-episode-row-scale")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .tvFocusTarget(focusTarget)
            .focusProperties {
                left = leftFocusRequester
                if (blockDown) down = FocusRequester.Cancel
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.055f))
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClickLabel = "Play episode ${episode.displayNumber}", onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EpisodeArtwork(
            image = image,
            blurred = blurred,
            onBlurredChange = onBlurredChange,
            compact = true,
            modifier = Modifier
                .width(142.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "EPISODE ${episode.displayNumber}",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                )
                if (isResumeEpisode) {
                    Text(
                        text = "CONTINUE",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                text = episode.distinctTitle ?: "Episode ${episode.displayNumber}",
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun TvDetailHero(
    info: Media,
    resume: HistoryEntry?,
    saved: Boolean,
    listStatusLabel: String?,
    canWatch: Boolean,
    onWatch: () -> Unit,
    onToggleSaved: () -> Unit,
    onStudioClick: (StudioNode) -> Unit,
    primaryActionFocusRequester: FocusRequester,
    onPrimaryFocusAcquired: () -> Unit,
) {
    val image = info.heroImage
    val description = remember(info.description) {
        info.description
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.trim()
            .orEmpty()
    }
    val studio = info.studios.nodes.firstOrNull { it.isAnimationStudio && !it.name.isNullOrBlank() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(305.dp)
            .focusGroup(),
    ) {
        AsyncImage(
            model = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            // Matches the home hero: CenterEnd showed a banner's empty right edge, and the middle
            // of a portrait cover when no banner exists. See TvHomeScreen's hero for the details.
            alignment = Alignment.TopCenter,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black,
                    .44f to Color.Black.copy(.92f),
                    .74f to Color.Black.copy(.30f),
                    1f to Color.Black.copy(.04f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(.20f),
                    .60f to Color.Transparent,
                    1f to Color.Black,
                ),
            ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(545.dp)
                .padding(start = TvDetailPadding, top = 55.dp),
        ) {
            Text(
                info.title.preferred,
                color = Color.White,
                fontSize = 36.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TvDetailMetadata(info)
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = Color.White.copy(.74f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onWatch,
                    enabled = canWatch,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 19.dp, vertical = 9.dp),
                    modifier = Modifier
                        .focusRequester(primaryActionFocusRequester)
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) onPrimaryFocusAcquired()
                        }
                        .focusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.04f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(21.dp))
                    Text(
                        resume?.let { "Continue E${it.episodeLabel}" } ?: "Watch",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
                OutlinedButton(
                    onClick = onToggleSaved,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Black.copy(.42f),
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 9.dp),
                    modifier = Modifier.focusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.04f),
                ) {
                    Icon(
                        if (saved || listStatusLabel != null) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        listStatusLabel ?: if (saved) "In library" else "Add to list",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
                studio?.let {
                    Text(
                        it.name.orEmpty(),
                        color = Color.White.copy(.68f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(.34f))
                            .clickable(enabled = it.id > 0) { onStudioClick(it) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvDetailMetadata(
    info: Media,
    maxGenres: Int = 3,
) {
    val cells = buildList {
        info.averageScore?.takeIf { it > 0 }?.let { add("score" to "$it%") }
        info.format?.let { add("text" to tvDetailPretty(it)) }
        (info.seasonYear ?: info.startDate?.year)?.let { add("text" to it.toString()) }
        info.duration?.takeIf { it > 0 }?.let { add("text" to "${it}m") }
        info.genres.take(maxGenres).forEach { add("text" to it) }
    }
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { index, (kind, label) ->
            if (index > 0) {
                Box(Modifier.size(3.dp).clip(CircleShape).background(Color.White.copy(.38f)))
            }
            if (kind == "score") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        label,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            } else {
                Text(label, color = Color.White.copy(.74f), fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TvSeasonRail(
    seasons: List<Media>,
    selectedSeasonId: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        TvDetailSectionTitle("Seasons")
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(horizontal = TvDetailPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(seasons.size) { index ->
                val season = seasons[index]
                val active = season.id == selectedSeasonId
                Text(
                    text = buildString {
                        append("Season ${index + 1}")
                        (season.seasonYear ?: season.startDate?.year)?.let { append("  •  $it") }
                    },
                    color = if (active) Color.White else Color.White.copy(.62f),
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .focusHighlight(RoundedCornerShape(10.dp), focusedScale = 1.05f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary.copy(.30f)
                            else MaterialTheme.colorScheme.surface,
                        )
                        .border(
                            1.dp,
                            if (active) MaterialTheme.colorScheme.primary.copy(.72f)
                            else Color.White.copy(.09f),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelect(season.id) }
                        .padding(horizontal = 15.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun TvEpisodeRail(
    episodes: List<EpisodeItem>,
    fallbackImage: String?,
    resume: HistoryEntry?,
    loading: Boolean,
    onPlay: (EpisodeItem) -> Unit,
) {
    val blurEpisodeImages by SettingsStore.blurEpisodeImages.collectAsState()
    Column {
        TvDetailSectionTitle("Episodes")
        when {
            episodes.isNotEmpty() -> LazyRow(
                modifier = Modifier.focusGroup(),
                contentPadding = PaddingValues(horizontal = TvDetailPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(episodes, key = { it.pipeId }) { episode ->
                    TvEpisodeCard(
                        episode = episode,
                        image = episodeArtworkImage(episode.image, fallbackImage),
                        blurred = blurEpisodeImages,
                        onBlurredChange = SettingsStore::setBlurEpisodeImages,
                        watchedFraction = episodeWatchFraction(resume, episode.number),
                        onClick = { onPlay(episode) },
                    )
                }
            }
            loading -> Text(
                "Loading episodes…",
                color = Color.White.copy(.55f),
                modifier = Modifier.padding(horizontal = TvDetailPadding, vertical = 18.dp),
            )
            else -> Text(
                "Episode information is not available yet.",
                color = Color.White.copy(.55f),
                modifier = Modifier.padding(horizontal = TvDetailPadding, vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun TvEpisodeCard(
    episode: EpisodeItem,
    image: String?,
    blurred: Boolean,
    onBlurredChange: (Boolean) -> Unit,
    watchedFraction: Float,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-episode-card-scale")

    Column(
        modifier = Modifier
            .width(TvEpisodeCardWidth)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = "Play episode ${episode.displayNumber}", onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(TvDetailCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    if (focused) 3.dp else 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(.08f),
                    TvDetailCardShape,
                ),
        ) {
            EpisodeArtwork(
                image = image,
                blurred = blurred,
                onBlurredChange = onBlurredChange,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(.02f),
                        .50f to Color.Black.copy(.08f),
                        1f to Color.Black.copy(.86f),
                    ),
                ),
            )
            Text(
                "EPISODE ${episode.displayNumber}",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(.70f))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp),
            ) {
                Text(
                    episode.distinctTitle ?: "Episode ${episode.displayNumber}",
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                WatchProgressBar(
                    fraction = watchedFraction,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun TvOverview(info: Media) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TvDetailPadding),
    ) {
        TvDetailSectionTitle("About", includePadding = false)
        val facts = buildList {
            info.status?.let { add(tvDetailPretty(it)) }
            info.episodes?.let { add("$it episodes") }
            info.season?.let { add(tvDetailPretty(it)) }
        }.joinToString("  •  ")
        if (facts.isNotBlank()) {
            Text(
                facts,
                color = Color.White.copy(.72f),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        info.nextAiringEpisode?.airingAt?.let { airingAt ->
            val date = Instant.ofEpochSecond(airingAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a"))
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Next episode${info.nextAiringEpisode.episode?.let { " $it" }.orEmpty()}  •  $date",
                    color = Color.White.copy(.64f),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TvRelatedRail(
    related: List<Media>,
    onAnimeClick: (Int) -> Unit,
) {
    Column {
        TvDetailSectionTitle("Related")
        LazyRow(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(horizontal = TvDetailPadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(related, key = { it.id }) { media ->
                var focused by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-related-scale")
                Column(
                    Modifier
                        .width(TvEpisodeCardWidth)
                        .zIndex(if (focused) 1f else 0f)
                        .scale(scale)
                        .onFocusChanged { focused = it.isFocused }
                        .clickable { onAnimeClick(media.id) },
                ) {
                    AsyncImage(
                        model = media.bannerImage ?: media.coverImage.best,
                        contentDescription = media.title.preferred,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(TvDetailCardShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                if (focused) 3.dp else 1.dp,
                                if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(.08f),
                                TvDetailCardShape,
                            ),
                        contentScale = ContentScale.Crop,
                    )
                    Text(
                        media.title.preferred,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvDetailSectionTitle(
    title: String,
    includePadding: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (includePadding) Modifier.padding(horizontal = TvDetailPadding) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .height(2.dp)
                .width(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(.7f)),
        )
    }
}

private fun tvDetailPretty(value: String): String =
    value.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
