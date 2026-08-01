package com.anilili.ui.components

import com.anilili.data.model.CoverImage
import com.anilili.data.model.Media
import com.anilili.data.model.Trailer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvHeroArtworkTest {
    @Test
    fun bannerWinsOverTrailerArtwork() {
        val media = Media(
            id = 1,
            bannerImage = "banner",
            trailer = Trailer(thumbnail = "trailer"),
        )

        assertEquals("banner", media.wideHeroArtwork())
    }

    @Test
    fun trailerThumbnailIsAUsableWideFallback() {
        val media = Media(id = 1, trailer = Trailer(thumbnail = "trailer"))

        assertEquals("trailer", media.wideHeroArtwork())
    }

    @Test
    fun coverOnlyArtworkUsesPosterTreatment() {
        val media = Media(id = 1, coverImage = CoverImage(extraLarge = "cover"))

        assertNull(media.wideHeroArtwork())
    }
}
