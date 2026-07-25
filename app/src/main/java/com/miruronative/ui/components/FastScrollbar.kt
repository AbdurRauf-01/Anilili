package com.miruronative.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_HEIGHT = 44.dp
private val TRACK_WIDTH = 8.dp

/**
 * Calculates target index in [totalItems] corresponding to a drag fraction in range [0f, 1f].
 */
fun calculateScrollTargetIndex(fraction: Float, totalItems: Int): Int {
    if (totalItems <= 0) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return ((totalItems - 1) * clamped).roundToInt().coerceIn(0, totalItems - 1)
}

/**
 * How far down the track the thumb sits. The thumb travels the track *less its own height*, so
 * this needs the thumb measured in the same pixels as the track — 44.dp is ~100px only around
 * 2.3x density, and hard-coding that overran the track on a 3x screen and fell short on a 2x one.
 */
internal fun thumbOffsetPx(trackHeightPx: Float, thumbHeightPx: Float, progressFraction: Float): Float =
    (trackHeightPx - thumbHeightPx).coerceAtLeast(0f) * progressFraction.coerceIn(0f, 1f)

/**
 * A draggable fast scrollbar with click-and-drag, track tap, and direct jump support.
 */
@Composable
fun FastScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White.copy(alpha = 0.16f),
    thumbColor: Color = MaterialTheme.colorScheme.primary,
) {
    val coroutineScope = rememberCoroutineScope()
    val totalItems = state.layoutInfo.totalItemsCount

    if (totalItems <= 0) return

    // Progress deliberately ignores how many rows happen to be on screen: visibleItemsInfo.size
    // flickers between n and n+1 as a partial row enters view, and putting that in the divisor
    // made the thumb jitter during an ordinary scroll.
    val lastScrollableIndex = (totalItems - 1).coerceAtLeast(1)
    val progressFraction =
        (state.firstVisibleItemIndex.toFloat() / lastScrollableIndex.toFloat()).coerceIn(0f, 1f)

    var trackHeightPx by remember { mutableFloatStateOf(1f) }
    val thumbHeightPx = with(LocalDensity.current) { THUMB_HEIGHT.toPx() }

    // A drag emits a pointer event per frame. Launching a coroutine for each one left dozens
    // racing for the list's scroll lock, so keep a single job and let the newest position win.
    val scrollJob = remember { mutableStateOf<Job?>(null) }
    fun scrollTo(offsetY: Float) {
        val fraction = (offsetY / trackHeightPx).coerceIn(0f, 1f)
        val target = calculateScrollTargetIndex(fraction, totalItems)
        scrollJob.value?.cancel()
        scrollJob.value = coroutineScope.launch { state.scrollToItem(target) }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(16.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(TRACK_WIDTH)
                .onGloballyPositioned { trackHeightPx = it.size.height.toFloat().coerceAtLeast(1f) }
                .clip(RoundedCornerShape(4.dp))
                .background(trackColor)
                .pointerInput(totalItems) {
                    detectTapGestures { offset -> scrollTo(offset.y) }
                }
                .pointerInput(totalItems) {
                    var dragY = 0f
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragY = offset.y
                            scrollTo(dragY)
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragY = (dragY + dragAmount).coerceIn(0f, trackHeightPx)
                            scrollTo(dragY)
                        },
                    )
                },
        ) {
            val thumbOffsetYPx = thumbOffsetPx(trackHeightPx, thumbHeightPx, progressFraction)

            Box(
                modifier = Modifier
                    .offset { IntOffset(x = 0, y = thumbOffsetYPx.roundToInt()) }
                    .width(TRACK_WIDTH)
                    .height(THUMB_HEIGHT)
                    .clip(RoundedCornerShape(4.dp))
                    .background(thumbColor),
            )
        }
    }
}
