package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class AniDbAppProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AniDbAppProvider

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        provider = AniDbAppProvider(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun resolvesExactIdentityBuildsLanguageAvailabilityAndExtractsHls() {
        server.enqueue(
            html(
                """
                <a href="/anime/death-note-1199" data-search-item>
                  <p class="text-sm font-medium">Death Note</p>
                </a>
                """.trimIndent(),
            ),
        )
        server.enqueue(
            html(
                """
                <h1>Death Note</h1>
                <a href="https://anilist.co/anime/1535">AniList</a>
                <a href="https://myanimelist.net/anime/1535">MAL</a>
                """.trimIndent(),
            ),
        )
        server.enqueue(
            json(
                """{"episodes":[
                    {"id":10,"number":1,"filler":false},
                    {"id":11,"number":2,"filler":false}
                ]}""",
            ),
        )
        server.enqueue(
            json(
                """{"languages":[
                    {"code":"eng","name":"English","embed_url":"${server.url("/embed/eng-1")}"},
                    {"code":"jpn","name":"Japanese","embed_url":"${server.url("/embed/jpn-1")}"}
                ]}""",
            ),
        )
        server.enqueue(
            json(
                """{"languages":[
                    {"code":"jpn","name":"Japanese","embed_url":"${server.url("/embed/jpn-2")}"}
                ]}""",
            ),
        )

        val media = Media(
            id = 1_535,
            idMal = 1_535,
            title = MediaTitle(english = "Death Note"),
            episodes = 2,
        )
        val availability = provider.episodeAvailability(media, expectedCount = 2)

        assertEquals(setOf(1, 2), availability.sub)
        assertEquals(setOf(1), availability.dub)

        server.enqueue(
            html(
                """var setup = { sources: [{ file: 'https://hls.test/death-note/master.m3u8', type: 'hls' }] };""",
            ),
        )
        val sources = provider.sources(media, audio = "sub", episode = 2)

        assertEquals("https://hls.test/death-note/master.m3u8", sources.streams.single().url)
        assertEquals("hls", sources.streams.single().type)
        assertEquals("sub", sources.streams.single().audio)
        assertTrue(sources.streams.single().isActive)
        assertEquals("/embed/jpn-2", server.takeRequestSequence().last())
    }

    @Test
    fun rejectsAWeakTitleMatchWhenExternalIdentityDiffers() {
        server.enqueue(
            html(
                """
                <a href="/anime/death-note-rewrite-1200" data-search-item>
                  <p class="text-sm">Death Note</p>
                </a>
                """.trimIndent(),
            ),
        )
        server.enqueue(
            html(
                """
                <a href="https://anilist.co/anime/2994">AniList</a>
                <a href="https://myanimelist.net/anime/2994">MAL</a>
                """.trimIndent(),
            ),
        )

        val error = runCatching {
            provider.episodeAvailability(
                Media(
                    id = 1_535,
                    idMal = 1_535,
                    title = MediaTitle(english = "Death Note"),
                    episodes = 37,
                ),
                expectedCount = 37,
            )
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("match not found"))
    }

    @Test
    fun parserHandlesOffsetNumberingAndCloudflarePages() {
        assertEquals(12, AniDbAppParser.inferOffset(listOf(13, 14, 15, 16), expectedCount = 4))
        assertEquals(0, AniDbAppParser.inferOffset(listOf(1, 2, 3, 4), expectedCount = 4))
        assertTrue(AniDbAppParser.isCloudflareChallenge("<script src=\"https://challenges.cloudflare.com/x\"></script>"))
    }

    @Test
    fun liveDeathNoteCatalogAndDirectHlsResolution() {
        assumeTrue(System.getenv("RUN_LIVE_ANIDBAPP_TESTS") == "1")
        val liveProvider = AniDbAppProvider(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
        )
        val media = Media(
            id = 1_535,
            idMal = 1_535,
            title = MediaTitle(english = "Death Note", romaji = "Death Note"),
            episodes = 37,
            status = "FINISHED",
        )

        val availability = liveProvider.episodeAvailability(media, expectedCount = 37)
        assertTrue(1 in availability.sub)
        val source = liveProvider.sources(media, audio = "sub", episode = 1).streams.single()
        assertTrue(source.url.startsWith("https://hls.anidb.app/"))
        assertTrue(source.url.contains(".m3u8"))
    }

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html")
        .setBody(body)

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun MockWebServer.takeRequestSequence(): List<String?> =
        (1..requestCount).map { takeRequest().path }
}
