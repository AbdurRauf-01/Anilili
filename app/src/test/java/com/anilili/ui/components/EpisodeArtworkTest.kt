package com.anilili.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeArtworkTest {
    @Test
    fun `provider episode image takes priority when available`() {
        assertEquals(
            "https://cdn.example/episode.jpg",
            episodeArtworkImage(
                episodeImage = "https://cdn.example/episode.jpg",
                fallbackImage = "https://cdn.example/season.jpg",
            ),
        )
    }

    @Test
    fun `season artwork fills catalogs without episode images`() {
        assertEquals(
            "https://cdn.example/season.jpg",
            episodeArtworkImage(episodeImage = null, fallbackImage = "https://cdn.example/season.jpg"),
        )
        assertNull(episodeArtworkImage(episodeImage = null, fallbackImage = null))
    }

    @Test
    fun `artwork gesture toggles the global blur value in either direction`() {
        assertTrue(toggledEpisodeImageBlur(current = false))
        assertFalse(toggledEpisodeImageBlur(current = true))
    }
}
