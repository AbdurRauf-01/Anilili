package com.miruronative.ui.home

import androidx.compose.ui.focus.FocusRequester
import com.miruronative.ui.adaptive.TvFocusTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TvHomeFocusPolicyTest {
    @Test
    fun topRatedIsTheBottomBoundaryWhenEveryRailExists() {
        assertEquals(
            "top-rated",
            lastTvHomeRailKey(
                hasHistory = true,
                hasTrending = true,
                hasNewest = true,
                hasPopular = true,
                hasMovies = true,
                hasTopRated = true,
            ),
        )
    }

    @Test
    fun boundaryFallsBackToTheLastVisibleRail() {
        assertEquals(
            "popular",
            lastTvHomeRailKey(
                hasHistory = true,
                hasTrending = true,
                hasNewest = false,
                hasPopular = true,
                hasMovies = false,
                hasTopRated = false,
            ),
        )
    }

    @Test
    fun historyCanBeTheOnlyBottomBoundary() {
        assertEquals(
            "continue-watching",
            lastTvHomeRailKey(
                hasHistory = true,
                hasTrending = false,
                hasNewest = false,
                hasPopular = false,
                hasMovies = false,
                hasTopRated = false,
            ),
        )
    }

    @Test
    fun noRowsMeansNoBottomBoundary() {
        assertNull(
            lastTvHomeRailKey(
                hasHistory = false,
                hasTrending = false,
                hasNewest = false,
                hasPopular = false,
                hasMovies = false,
                hasTopRated = false,
            ),
        )
    }

    @Test
    fun recycledRememberedCardFallsBackToSpatialFocus() {
        val recycled = TvFocusTarget()

        assertSame(
            FocusRequester.Default,
            rememberedTvRailRequester(listOf(recycled), index = 0),
        )
    }

    @Test
    fun attachedRememberedCardCanBeRestored() {
        val attached = TvFocusTarget().also(TvFocusTarget::onAttach)

        assertSame(
            attached.requester,
            rememberedTvRailRequester(listOf(attached), index = 0),
        )
    }
}
