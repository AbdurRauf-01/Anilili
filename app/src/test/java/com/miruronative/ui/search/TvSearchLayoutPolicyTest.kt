package com.miruronative.ui.search

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSearchLayoutPolicyTest {
    @Test
    fun searchColumnCountRespondsToAvailableTvWidth() {
        assertEquals(
            4,
            adaptiveColumnCount(
                availableWidth = 960.dp,
                horizontalPadding = 48.dp,
                minimumTileWidth = 200.dp,
                spacing = 18.dp,
            ),
        )
        assertEquals(
            3,
            adaptiveColumnCount(
                availableWidth = 800.dp,
                horizontalPadding = 48.dp,
                minimumTileWidth = 200.dp,
                spacing = 18.dp,
            ),
        )
    }

    @Test
    fun tvHeaderCanCollapseEvenWhileGridRemainsAtTop() {
        assertFalse(
            searchHeaderVisible(
                isTv = true,
                tvHeaderExpanded = false,
                scrollingUp = true,
            ),
        )
    }

    @Test
    fun phoneHeaderContinuesFollowingScrollDirection() {
        assertTrue(searchHeaderVisible(isTv = false, tvHeaderExpanded = false, scrollingUp = true))
        assertFalse(searchHeaderVisible(isTv = false, tvHeaderExpanded = true, scrollingUp = false))
    }

    @Test
    fun onlyFirstGridRowReturnsToSearchControls() {
        assertTrue(isFirstTvSearchResultRow(index = 0, columnCount = 4))
        assertTrue(isFirstTvSearchResultRow(index = 3, columnCount = 4))
        assertFalse(isFirstTvSearchResultRow(index = 4, columnCount = 4))
        assertFalse(isFirstTvSearchResultRow(index = -1, columnCount = 4))
    }
}
