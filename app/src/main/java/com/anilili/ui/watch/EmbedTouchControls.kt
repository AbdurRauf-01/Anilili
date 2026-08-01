package com.anilili.ui.watch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The embed player's touch controls: the shared [PlayerControlsScaffold] with settings and
 * fullscreen as its trailing icons. Drawn over the WebView when the injected JS reaches its `<video>`;
 * the touch-swallowing layer beneath keeps the provider's own chrome from ever appearing.
 */
@Composable
internal fun EmbedTouchControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onCaptions: () -> Unit,
    onSettings: () -> Unit,
    onAddToList: (() -> Unit)? = null,
    onEpisodes: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
    onInteract: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    PlayerControlsScaffold(
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        hasPrevious = hasPrevious,
        hasNext = hasNext,
        onPrevious = onPrevious,
        onRewind = onRewind,
        onPlayPause = onPlayPause,
        onForward = onForward,
        onNext = onNext,
        onSeek = onSeek,
        onInteract = onInteract,
        modifier = modifier,
        topRightIcons = {
            onAddToList?.let { openList ->
                PlayerControlIconButton(
                    "Add to My List",
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    onClick = {
                        openList()
                        onInteract()
                    },
                )
            }
        },
    ) {
        onEpisodes?.let { openEpisodes ->
            PlayerControlIconButton(
                "Episode list",
                Icons.AutoMirrored.Filled.ViewList,
                onClick = {
                    openEpisodes()
                    onInteract()
                },
            )
        }
        PlayerControlIconButton(
            "Captions",
            Icons.Default.ClosedCaption,
            onClick = {
                onCaptions()
                onInteract()
            },
        )
        PlayerControlIconButton(
            "Settings",
            Icons.Default.Settings,
            onClick = {
                onSettings()
                onInteract()
            },
        )
        onFullscreen?.let { toggle ->
            PlayerControlIconButton(
                if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                onClick = {
                    toggle()
                    onInteract()
                },
            )
        }
    }
}
