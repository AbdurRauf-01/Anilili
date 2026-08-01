package com.anilili.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.anilili.data.model.Media

/** A real landscape image is safe to crop across a television hero. */
internal fun Media.wideHeroArtwork(): String? = bannerImage ?: trailer?.thumbnail

/**
 * TV hero art that does not turn a portrait-only cover into a narrow, face-cropping backdrop.
 *
 * Landscape artwork keeps the normal edge-to-edge treatment. When AniList only supplies a cover,
 * a deliberately small copy becomes the soft ambient background while the complete poster remains
 * visible at the right. The low-resolution request also avoids decoding a second full-size bitmap
 * just to blur it.
 */
@Composable
fun TvHeroArtwork(
    media: Media,
    modifier: Modifier = Modifier,
    posterEndPadding: Int = 56,
) {
    val wideArtwork = media.wideHeroArtwork()
    val cover = media.coverImage.best

    Box(modifier.clipToBounds().background(Color.Black)) {
        if (wideArtwork != null) {
            AsyncImage(
                model = wideArtwork,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
        } else if (cover != null) {
            val context = LocalContext.current
            val ambientRequest = remember(cover) {
                ImageRequest.Builder(context)
                    .data(cover)
                    .size(320, 480)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = ambientRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.16f
                        scaleY = 1.16f
                    }
                    .blur(34.dp),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = posterEndPadding.dp, top = 24.dp, bottom = 24.dp)
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
