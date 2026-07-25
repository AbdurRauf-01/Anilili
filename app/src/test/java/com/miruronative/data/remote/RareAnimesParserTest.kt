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
    fun `single-language posts label rows by dub source, not language`() {
        // Jujutsu Kaisen Season 1's markup: the post is Hindi-only, so its rows are headed by the
        // dub studio. Requiring a Hindi/Tamil/Telugu word here dropped 23 of its 24 episodes.
        val html = """
            <p>Episode 01 – Ryomen Sukuna</p>
            <p><span style="color: #ff0000;">New Sony Dub – </span>[<a href="https://codedew.com/zipper/?url=EP1_MQ" target="_blank">WatchMultQuality</a>] [<a href="https://codedew.com/zipper/?url=EP1_MF">MediaFire</a>]</p>
            <p><span style="color: #99cc00;">Crunchyroll</span> – [<a href="https://codedew.com/zipper/?url=EP1_CR">WatchMultQuality</a>]</p>
            <hr>
            <p>Episode 02 – For Myself</p>
            <p><span>New Sony Dub – </span>[<a href="https://codedew.com/zipper/?url=EP2_MQ">WatchMultQuality</a>]</p>
        """.trimIndent()

        val episodes = RareAnimesParser.parseEpisodes(html)

        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals(setOf("hindi"), episodes[0].languages.keys)
        // Every server in the block is collected, not just the first row's.
        assertEquals(3, episodes[0].languages.getValue("hindi").size)
        assertEquals(
            "https://codedew.com/zipper/?url=EP1_MQ",
            RareAnimesParser.preferred(episodes[0].languages.getValue("hindi"))!!.url,
        )
    }

    @Test
    fun `preferred accepts both MultiQuality spellings the site uses`() {
        val multi = RareAnimesParser.ServerLink("u1", "WatchMultiQuality")
        val mult = RareAnimesParser.ServerLink("u2", "WatchMultQuality")
        val beta = RareAnimesParser.ServerLink("u3", "StreamBeta")
        assertEquals("u1", RareAnimesParser.preferred(listOf(beta, multi))!!.url)
        assertEquals("u2", RareAnimesParser.preferred(listOf(beta, mult))!!.url)
        // The decodable server wins even when another "Watch…" row comes first.
        assertEquals(
            "u2",
            RareAnimesParser.preferred(
                listOf(RareAnimesParser.ServerLink("u4", "Watch Online"), mult),
            )!!.url,
        )
        assertNull(RareAnimesParser.preferred(emptyList()))
    }

    @Test
    fun `series title drops the site's stock suffix`() {
        assertEquals(
            "Demon Slayer",
            RareAnimesParser.seriesTitle("Demon Slayer Season 1 – Episodes Hindi Dubbed Download HD Jio Cinema"),
        )
        assertEquals(
            "Naruto Shippuden",
            RareAnimesParser.seriesTitle("Naruto Shippuden Season 02 – Episodes Hindi Dubbed Download HD"),
        )
        // Nothing to strip.
        assertEquals("Death Note", RareAnimesParser.seriesTitle("Death Note"))
    }

    @Test
    fun `season number is read from the post title`() {
        assertEquals(4, RareAnimesParser.seasonNumber("Demon Slayer Season 4 (Hashira Training Arc) Hindi"))
        assertEquals(2, RareAnimesParser.seasonNumber("Naruto Shippuden Season 02 – Episodes"))
        assertNull(RareAnimesParser.seasonNumber("Demon Slayer Mugen Train Movie Hindi Dubbed"))
    }

    @Test
    fun `duplicate episode headings collapse to one entry`() {
        val html = """
            <p>Episode 01 – Real</p>
            <p><span>Hindi </span>– [<a href="https://codedew.com/zipper/?url=A">WatchMultiQuality</a>]</p>
            <p>Episode 01 – Repeated in a footer index</p>
            <p><span>Hindi </span>– [<a href="https://codedew.com/zipper/?url=B">WatchMultiQuality</a>]</p>
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
