package com.anilili.ui.components

import com.anilili.data.remote.MalClient
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeListStatusTest {
    @Test
    fun `player offers the five standard list destinations in display order`() {
        assertEquals(
            listOf("CURRENT", "PLANNING", "COMPLETED", "PAUSED", "DROPPED"),
            animeListStatusOptions.map(AnimeListStatusOption::status),
        )
        assertEquals(
            listOf("Watching", "Plan to Watch", "Completed", "On-Hold", "Dropped"),
            animeListStatusOptions.map(AnimeListStatusOption::label),
        )
    }

    @Test
    fun `every player destination maps to MAL vocabulary`() {
        assertEquals(
            listOf("watching", "plan_to_watch", "completed", "on_hold", "dropped"),
            animeListStatusOptions.map { MalClient.malStatus(it.status) },
        )
    }
}
