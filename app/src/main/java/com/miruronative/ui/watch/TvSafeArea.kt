package com.miruronative.ui.watch

import androidx.compose.ui.unit.dp

/**
 * Inset that keeps player overlays clear of a TV's overscan.
 *
 * Television sets crop the outer edge of the picture — around 5%, which at 1080p is roughly 27px
 * vertically and 48px horizontally. Anything drawn closer to an edge than that may never reach the
 * viewer's screen even though it renders correctly in a screenshot or on an emulator, which is why
 * "skip intro doesn't show on TV" looked unreproducible. This matches the 48.dp already used by
 * TvHomeScreen and TvDetailScreen.
 */
internal val TV_SAFE_AREA_INSET = 48.dp
