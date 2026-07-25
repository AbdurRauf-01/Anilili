package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RareAnimesParserTest {
    @Test
    fun parsesSearchResults() {
        val html = """
            <article class="herald-lay-b post-42798 post type-post hentry category-animes">
              <div class="row">
                <a href="https://www.rareanimes.mov/hindi/naruto-shippuden-season-02-episodes-hindi-dubbed-download-hd/" title="Naruto Shippuden Season 02 &#8211; Episodes Hindi Dubbed Download HD">
                  <img src="x.jpg" />
                </a>
              </div>
            </article>
            <article class="herald-lay-b post-42653 post type-post hentry category-animes">
              <a href="https://www.rareanimes.mov/hindi/naruto-shippuden-season-01-episodes-hindi-dubbed-download-hd/" title="Naruto Shippuden Season 01 &#8211; Episodes Hindi Dubbed Download HD">x</a>
            </article>
        """.trimIndent()

        val results = RareAnimesParser.parseSearch(html)

        assertEquals(2, results.size)
        assertEquals(
            "https://www.rareanimes.mov/hindi/naruto-shippuden-season-02-episodes-hindi-dubbed-download-hd/",
            results[0].url,
        )
        assertEquals("Naruto Shippuden Season 02 – Episodes Hindi Dubbed Download HD", results[0].title)
    }

    @Test
    fun parsesParagraphStyleEpisodes() {
        val html = """
            <hr>
            <p>Episode 01 – The New Target</p>
            <p><span style="color: #99cc00;">Hindi Uncut </span>– [<a href="https://codedew.com/zipper/?url=HINDI_MQ" target="_blank">WatchMultiQuality</a>] [<a href="https://codedew.com/zipper/?url=HINDI_SB" target="_blank">StreamBeta</a>] [<a href="https://codedew.com/zipper/?url=HINDI_DL" target="_blank">DLBeta</a>]</p>
            <p><span style="color: #ff00ff;">Tamil </span>– [<a href="https://codedew.com/zipper/?url=TAMIL_SB" target="_blank">StreamBeta</a>]</p>
            <p><span style="color: #00ccff;">Telugu </span>– [<a href="https://codedew.com/zipper/?url=TELUGU_SB" target="_blank">StreamBeta</a>]</p>
            <hr>
            <p>Episode 02 – Formation! New Team Kakashi!</p>
            <p><span style="color: #99cc00;">Hindi Uncut </span>– [<a href="https://codedew.com/zipper/?url=EP2_MQ" target="_blank">WatchMultiQuality</a>]</p>
            <hr>
        """.trimIndent()

        val episodes = RareAnimesParser.parseEpisodes(html)

        assertEquals(listOf(1, 2), episodes.map { it.number })
        val ep1 = episodes[0]
        assertEquals(setOf("hindi", "tamil", "telugu"), ep1.languages.keys)
        assertEquals(3, ep1.languages.getValue("hindi").size)
        assertEquals(
            "https://codedew.com/zipper/?url=HINDI_MQ",
            RareAnimesParser.preferred(ep1.languages.getValue("hindi"))!!.url,
        )
        assertEquals(
            "https://codedew.com/zipper/?url=TAMIL_SB",
            RareAnimesParser.preferred(ep1.languages.getValue("tamil"))!!.url,
        )
        assertEquals(setOf("hindi"), episodes[1].languages.keys)
    }

    @Test
    fun parsesSpanStyleEpisodes() {
        // Older markup generation: ra-ep-title / ra-l-* spans instead of plain paragraphs.
        val html = """
            <span class="ra-ep-title"><strong>Episode 01 – The Beginning</strong></span>
            <span class="ra-l-hindi">Hindi</span>
            [<a href="https://codedew.com/zipper/?url=SPAN_MQ">WatchMultQuality</a>]
            [<a href="https://codedew.com/zipper/?url=SPAN_SB">StreamBeta</a>]
        """.trimIndent()

        val episodes = RareAnimesParser.parseEpisodes(html)

        assertEquals(1, episodes.size)
        assertEquals(
            "https://codedew.com/zipper/?url=SPAN_MQ",
            RareAnimesParser.preferred(episodes[0].languages.getValue("hindi"))!!.url,
        )
    }

    @Test
    fun skipsEpisodesWithoutLinks() {
        val html = """
            <p>Episode 01 – Real</p>
            <p><span>Hindi </span>– [<a href="https://codedew.com/zipper/?url=OK">WatchMultiQuality</a>]</p>
            <p>Episode 02 – Empty</p>
            <p>No links here yet.</p>
        """.trimIndent()

        assertEquals(listOf(1), RareAnimesParser.parseEpisodes(html).map { it.number })
    }

    @Test
    fun findsEmbedUrl() {
        val html = """
            <iframe src="https://argon.razorshell.space/embed/5GQAmPt6K445Vk7" referrerpolicy="origin" allowfullscreen></iframe>
        """.trimIndent()
        assertEquals(
            "https://argon.razorshell.space/embed/5GQAmPt6K445Vk7",
            RareAnimesParser.embedUrl(html),
        )
        assertNull(RareAnimesParser.embedUrl("<p>nothing</p>"))
    }
}
