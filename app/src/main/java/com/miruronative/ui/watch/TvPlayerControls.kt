package com.miruronative.ui.watch

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miruronative.ui.adaptive.focusHighlight

import androidx.compose.material.icons.automirrored.filled.ViewList

internal enum class TvPlayerControl {
    PREVIOUS,
    REWIND,
    PLAY_PAUSE,
    FORWARD,
    NEXT,
    EPISODES,
    MY_LIST,
    MUTE,
    CAPTIONS,
    SETTINGS,
    FULLSCREEN,
}

/**
 * Stable left-to-right remote order; the progress bar is deliberately display-only.
 * Loudness is the remote's own volume rocker, so the row carries a single mute toggle rather
 * than a level pair — three near-identical speaker glyphs read as one broken control.
 */
internal fun tvPlayerControlOrder(
    hasEpisodes: Boolean = false,
    hasList: Boolean = false,
    hasCaptions: Boolean = false,
    hasSettings: Boolean = false,
    hasFullscreen: Boolean = false,
): List<TvPlayerControl> = buildList {
    add(TvPlayerControl.PREVIOUS)
    add(TvPlayerControl.REWIND)
    add(TvPlayerControl.PLAY_PAUSE)
    add(TvPlayerControl.FORWARD)
    add(TvPlayerControl.NEXT)
    if (hasEpisodes) add(TvPlayerControl.EPISODES)
    if (hasList) add(TvPlayerControl.MY_LIST)
    add(TvPlayerControl.MUTE)
    if (hasCaptions) add(TvPlayerControl.CAPTIONS)
    if (hasSettings) add(TvPlayerControl.SETTINGS)
    if (hasFullscreen) add(TvPlayerControl.FULLSCREEN)
}

internal fun opensTvPlayerControls(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
    keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
    keyCode == KeyEvent.KEYCODE_ENTER ||
    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

internal fun opensTvPlayerControls(key: Key): Boolean = key == Key.DirectionLeft ||
    key == Key.DirectionRight ||
    key == Key.DirectionUp ||
    key == Key.DirectionDown ||
    key == Key.DirectionCenter ||
    key == Key.Enter ||
    key == Key.NumPadEnter

@Composable
internal fun TvPlayerControls(
    seriesTitle: String = "",
    episodeTitle: String = "",
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isMuted: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    playPauseFocusRequester: FocusRequester,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onToggleMute: () -> Unit,
    onEpisodes: (() -> Unit)? = null,
    onAddToList: (() -> Unit)? = null,
    onCaptions: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onFullscreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    var focusedLabel by remember { mutableStateOf(if (isPlaying) "Pause" else "Play") }
    val visibleFocusedLabel = if (focusedLabel == "Play" || focusedLabel == "Pause") {
        if (isPlaying) "Pause" else "Play"
    } else {
        focusedLabel
    }
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)

    Box(modifier.fillMaxSize()) {
        if (seriesTitle.isNotBlank() || episodeTitle.isNotBlank()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 32.dp, top = 28.dp)
                    .widthIn(max = 620.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (seriesTitle.isNotBlank()) {
                    Text(
                        text = seriesTitle,
                        color = Color.White,
                        fontSize = 22.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (episodeTitle.isNotBlank()) {
                    Text(
                        text = episodeTitle,
                        color = Color.White.copy(alpha = 0.70f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        .32f to Color.Black.copy(alpha = 0.68f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    ),
                )
                .padding(start = 32.dp, end = 32.dp, top = 30.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTvPlayerTime(positionMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
                Text(
                    text = visibleFocusedLabel,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "-${formatTvPlayerTime(remainingMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.68f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvControlButton("Previous episode", enabled = hasPrevious, onFocused = { focusedLabel = it }, onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = null)
                }
                TvControlButton("Rewind 10 seconds", onFocused = { focusedLabel = it }, onClick = onRewind) {
                    Icon(Icons.Default.FastRewind, contentDescription = null)
                }
                TvControlButton(
                    label = if (isPlaying) "Pause" else "Play",
                    onClick = onPlayPause,
                    onFocused = { focusedLabel = it },
                    large = true,
                    modifier = Modifier.focusRequester(playPauseFocusRequester),
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                    )
                }
                TvControlButton("Forward 10 seconds", onFocused = { focusedLabel = it }, onClick = onForward) {
                    Icon(Icons.Default.FastForward, contentDescription = null)
                }
                TvControlButton("Next episode", enabled = hasNext, onFocused = { focusedLabel = it }, onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = null)
                }
                onEpisodes?.let { callback ->
                    TvControlButton("Episode list", onFocused = { focusedLabel = it }, onClick = callback) {
                        Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null)
                    }
                }
                onAddToList?.let { callback ->
                    TvControlButton("Add to My List", onFocused = { focusedLabel = it }, onClick = callback) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                    }
                }
                TvControlButton(
                    if (isMuted) "Unmute" else "Mute",
                    onFocused = { focusedLabel = it },
                    onClick = onToggleMute,
                ) {
                    Icon(
                        if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                    )
                }
                onCaptions?.let { callback ->
                    TvControlButton("Captions", onFocused = { focusedLabel = it }, onClick = callback) {
                        Icon(Icons.Default.ClosedCaption, contentDescription = null)
                    }
                }
                onSettings?.let { callback ->
                    TvControlButton("Playback settings", onFocused = { focusedLabel = it }, onClick = callback) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    }
                }
                onFullscreen?.let { callback ->
                    TvControlButton("Toggle fullscreen", onFocused = { focusedLabel = it }, onClick = callback) {
                        Icon(Icons.Default.Fullscreen, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvControlButton(
    label: String,
    enabled: Boolean = true,
    large: Boolean = false,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(if (large) 62.dp else 48.dp)
            .onFocusChanged { if (it.isFocused) onFocused(label) }
            .semantics { contentDescription = label }
            .focusHighlight(RoundedCornerShape(32.dp), focusedScale = if (large) 1.15f else 1.10f),
    ) {
        icon()
    }
}

private fun formatTvPlayerTime(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
