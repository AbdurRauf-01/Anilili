package com.anilili.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollbarTest {
    @Test
    fun calculateScrollTargetIndexCalculatesCorrectIndexForDragFractions() {
        val totalItems = 100

        // Fraction 0.0 -> First item (0)
        assertEquals(0, calculateScrollTargetIndex(0.0f, totalItems))

        // Fraction 1.0 -> Last item (99)
        assertEquals(99, calculateScrollTargetIndex(1.0f, totalItems))

        // Fraction 0.5 -> Middle item (50)
        assertEquals(50, calculateScrollTargetIndex(0.5f, totalItems))

        // Fraction 0.25 -> Quarter item (25)
        assertEquals(25, calculateScrollTargetIndex(0.25f, totalItems))

        // Out of bounds fractions are clamped
        assertEquals(0, calculateScrollTargetIndex(-0.5f, totalItems))
        assertEquals(99, calculateScrollTargetIndex(1.5f, totalItems))
    }

    @Test
    fun calculateScrollTargetIndexHandlesEmptyList() {
        assertEquals(0, calculateScrollTargetIndex(0.5f, 0))
    }

    @Test
    fun `thumb travel is the track less the thumb, at any density`() {
        // 44.dp on a 3x screen is 132px, not the 100px that used to be hard-coded.
        val track = 1000f
        assertEquals(0f, thumbOffsetPx(track, thumbHeightPx = 132f, progressFraction = 0f), 0.01f)
        // At the end the thumb's bottom edge lands exactly on the track's, never past it.
        val end = thumbOffsetPx(track, thumbHeightPx = 132f, progressFraction = 1f)
        assertEquals(868f, end, 0.01f)
        assertEquals(track, end + 132f, 0.01f)
        // Same invariant at 2x, where the old constant stopped the thumb short instead.
        assertEquals(track, thumbOffsetPx(track, 88f, 1f) + 88f, 0.01f)
        assertEquals(434f, thumbOffsetPx(track, 132f, 0.5f), 0.01f)
    }

    @Test
    fun `thumb never escapes the track`() {
        // Out-of-range progress is clamped, and a thumb taller than its track simply pins to zero.
        assertEquals(0f, thumbOffsetPx(1000f, 132f, -1f), 0.01f)
        assertEquals(868f, thumbOffsetPx(1000f, 132f, 5f), 0.01f)
        assertEquals(0f, thumbOffsetPx(50f, 132f, 1f), 0.01f)
    }
}
