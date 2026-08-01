package com.anilili.ui.watch

import com.anilili.data.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFilterTest {
    private fun option(provider: String, category: Category) =
        WatchSourceOption(provider, category, hasCurrentEpisode = true, episodeCount = 12)

    private val bonkSub = option("bonk", Category.SUB)
    private val bonkDub = option("bonk", Category.DUB)
    private val rareDub = option("rareanimes", Category.DUB)
    private val options = listOf(bonkSub, bonkDub, rareDub)

    private val capabilities = mapOf(
        (bonkSub.provider to Category.SUB) to SourceCapabilities(
            subtitleLanguages = setOf("English"),
            known = true,
        ),
        (bonkDub.provider to Category.DUB) to SourceCapabilities(
            audioLanguages = setOf("English"),
            known = true,
        ),
        (rareDub.provider to Category.DUB) to SourceCapabilities(
            audioLanguages = setOf("Hindi", "Tamil"),
            known = true,
        ),
    )

    @Test
    fun `audio filter comes straight from the catalog`() {
        assertEquals(options, filterSourceOptions(options, capabilities, AudioFilter.ANY, null))
        assertEquals(
            listOf(bonkSub),
            filterSourceOptions(options, capabilities, AudioFilter.SUB, null),
        )
        assertEquals(
            listOf(bonkDub, rareDub),
            filterSourceOptions(options, capabilities, AudioFilter.DUB, null),
        )
    }

    @Test
    fun `language filter narrows to sources carrying that track`() {
        assertEquals(
            listOf(rareDub),
            filterSourceOptions(options, capabilities, AudioFilter.ANY, "Hindi"),
        )
        // The headline case: a Hindi dub, found without scrolling every server.
        assertEquals(
            listOf(rareDub),
            filterSourceOptions(options, capabilities, AudioFilter.DUB, "Hindi"),
        )
        assertTrue(filterSourceOptions(options, capabilities, AudioFilter.SUB, "Hindi").isEmpty())
    }

    @Test
    fun `sources that have not been checked yet are never filtered out by language`() {
        // Hiding these would turn "still resolving" into a confident, wrong "no French here".
        val unchecked = capabilities - (rareDub.provider to Category.DUB)
        assertEquals(
            listOf(rareDub),
            filterSourceOptions(options, unchecked, AudioFilter.ANY, "French"),
        )
        // Once checked and known not to carry it, it drops out.
        val checked = unchecked + ((rareDub.provider to Category.DUB) to
            SourceCapabilities(audioLanguages = setOf("Hindi"), known = true))
        assertTrue(filterSourceOptions(options, checked, AudioFilter.ANY, "French").isEmpty())
    }

    @Test
    fun `completeness tracks whether every option has been inspected`() {
        assertTrue(languageFilterIsComplete(options, capabilities))
        assertFalse(
            languageFilterIsComplete(options, capabilities - (rareDub.provider to Category.DUB)),
        )
        assertFalse(
            languageFilterIsComplete(
                options,
                capabilities + ((rareDub.provider to Category.DUB) to SourceCapabilities()),
            ),
        )
    }
}
