package com.miruronative.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Provider art wins when available; the anime/season art keeps sparse catalogs presentable. */
internal fun episodeArtworkImage(episodeImage: String?, fallbackImage: String?): String? =
    episodeImage ?: fallbackImage

internal fun toggledEpisodeImageBlur(current: Boolean): Boolean = !current

/**
 * Episode artwork with a global spoiler-control gesture.
 *
 * Pointer taps are consumed here so a tap on the image changes the blur preference while a tap on
 * the rest of the row still plays the episode. The artwork is deliberately not a Compose focus
 * target: TV remotes continue to activate the parent episode card, while Settings remains the
 * remote-friendly way to change this preference.
 */
@Composable
fun EpisodeArtwork(
    image: String?,
    blurred: Boolean,
    onBlurredChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    compact: Boolean = false,
) {
    val next = toggledEpisodeImageBlur(blurred)
    val actionLabel = if (blurred) "Show all episode images" else "Blur all episode images"
    Box(
        modifier = modifier
            .clipToBounds()
            .semantics {
                contentDescription = "Episode image"
                stateDescription = if (blurred) "Spoilers hidden" else "Visible"
                role = Role.Switch
                onClick(actionLabel) {
                    onBlurredChange(next)
                    true
                }
            }
            .pointerInput(blurred, onBlurredChange) {
                detectTapGestures { onBlurredChange(toggledEpisodeImageBlur(blurred)) }
            },
    ) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (blurred) {
                        Modifier
                            .graphicsLayer {
                                // Fill the soft edge introduced by the blur without changing the
                                // card's measured size or the sharp overlays drawn by the caller.
                                scaleX = 1.10f
                                scaleY = 1.10f
                            }
                            .blur(20.dp)
                    } else {
                        Modifier
                    },
                ),
        )
        if (blurred) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Compose blur is not rendered on older Android versions. This veil is the
                    // privacy fallback and also keeps high-contrast stills unreadable everywhere.
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.68f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.58f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(50),
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = if (compact) 6.dp else 9.dp,
                            vertical = if (compact) 4.dp else 6.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = null,
                            modifier = Modifier.size(if (compact) 14.dp else 17.dp),
                        )
                        if (!compact) {
                            Text(
                                text = "Spoilers hidden",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
