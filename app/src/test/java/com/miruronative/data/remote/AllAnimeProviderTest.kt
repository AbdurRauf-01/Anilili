package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaTitle
import com.miruronative.data.model.StreamItem
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AllAnimeProviderTest {
    @Test
    fun decodesClockUrlsAndSourceRows() {
        assertEquals("/clock.json", AllAnimeCodec.decodeSourceUrl("--175b54575b53"))

        val root = Json.parseToJsonElement(
            """{"episode":{"sourceUrls":[{"sourceName":"Yt-mp4","sourceUrl":"https://cdn.test/video.mp4","type":"player","resolutionStr":"1080p","priority":10}]}}""",
        )
        val source = AllAnimeCodec.parseSources(root).single()

        assertEquals("Yt-mp4", source.name)
        assertEquals("https://cdn.test/video.mp4", source.url)
        assertEquals("1080p", source.quality)
        assertEquals(10.0, source.priority, 0.0)
    }

    @Test
    fun decryptsAllAnimeAesCtrEnvelope() {
        val plaintext = """{"sourceUrls":[{"sourceName":"S","sourceUrl":"https://cdn.test/master.m3u8","priority":2}]}"""
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val counter = iv + byteArrayOf(0, 0, 0, 2)
        val key = MessageDigest.getInstance("SHA-256").digest("Xot36i3lK3:v1".toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val envelope = byteArrayOf(0) + iv + ciphertext + ByteArray(16) { 7 }
        val encoded = Base64.getEncoder().encodeToString(envelope)

        val source = AllAnimeCodec.parseSources(AllAnimeCodec.decrypt(encoded)).single()

        assertEquals("https://cdn.test/master.m3u8", source.url)
        assertEquals(2.0, source.priority, 0.0)
    }

    @Test
    fun derivesCurrentEpochKeyAndSignsLaneScopedAuthenticatedRequest() {
        val mask = ByteArray(32) { it.toByte() }
        val partB = ByteArray(32) { (it * 3).toByte() }
        val key = AllAnimeMkissaCrypto.deriveKey(mask, partB)!!
        val signed = AllAnimeMkissaCrypto.signRequest(
            key = key,
            epoch = 6_887,
            buildId = "75",
            queryHash = "query-hash",
            lane = "k7",
            nowMs = 1_785_436_123_456,
        )
        val request = Json.parseToJsonElement(AllAnimeMkissaCrypto.decrypt(signed, key)!!).jsonObject

        assertEquals("1", request.getValue("v").jsonPrimitive.content)
        assertEquals("6887", request.getValue("epoch").jsonPrimitive.content)
        assertEquals("75", request.getValue("buildId").jsonPrimitive.content)
        assertEquals("query-hash", request.getValue("qh").jsonPrimitive.content)
        assertEquals("k7", request.getValue("k").jsonPrimitive.content)
        assertEquals("1785435900000", request.getValue("ts").jsonPrimitive.content)
    }

    @Test
    fun preservesCurrentIframeAndExtensionMetadata() {
        val root = Json.parseToJsonElement(
            """{"episode":{"sourceUrls":[{"sourceName":"Mp4","sourceUrl":"https://mp4upload.com/embed-test.html","type":"iframe","priority":4},{"sourceName":"Yt-mp4","sourceUrl":"https://cdn.test/signed-video?token=x","type":"player","fileExtenstion":"mp4","priority":8}]}}""",
        )
        val sources = AllAnimeCodec.parseSources(root)

        assertEquals("iframe", sources[0].type)
        assertEquals(null, sources[0].fileExtension)
        assertEquals("player", sources[1].type)
        assertEquals("mp4", sources[1].fileExtension)
    }

    @Test
    fun extractsSoftSubtitlesFromEpisodeAndClockPayloads() {
        val root = Json.parseToJsonElement(
            """{
                "episode":{"subtitles":[
                    {"url":"https://cdn.test/en.vtt","label":"English","language":"en"},
                    {"file":"/captions/es.ass","name":"Spanish","lang":"es"}
                ]},
                "tracks":[{"src":"https://cdn.test/signs.srt","kind":"captions","srclang":"ja"}],
                "links":[{"link":"https://cdn.test/video.mp4"}]
            }""".trimIndent(),
        )

        val subtitles = AllAnimeCodec.parseSubtitles(root)

        assertEquals(3, subtitles.size)
        assertEquals(setOf("en", "es", "ja"), subtitles.map { it.language }.toSet())
        assertFalse(subtitles.any { it.url.endsWith("video.mp4") })
    }

    @Test
    fun separatesApiAndPlayerReferersInVersionedConfiguration() {
        val protocol = AllAnimeProtocolConfig.active

        assertEquals("mkissa-dynamic-v1", protocol.version)
        assertEquals("k7", protocol.contentLane)
        assertTrue(protocol.buildId.isBlank())
        assertEquals("https://youtu-chan.com/", protocol.apiReferer)
        assertEquals("https://allanime.day/", protocol.playerReferer)
        assertFalse(protocol.apiReferer == protocol.playerReferer)
    }

    @Test
    fun networkErrorsDoNotPassDirectStreamVerification() {
        val provider = AllAnimeProvider(
            OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(250))
                .readTimeout(Duration.ofMillis(250))
                .build(),
            Json { ignoreUnknownKeys = true },
        )
        val unreachable = StreamItem(
            url = "http://127.0.0.1:1/unreachable.mp4",
            type = "mp4",
            quality = "test",
            audio = "sub",
            referer = AllAnimeProtocolConfig.active.playerReferer,
            isActive = false,
            width = null,
            height = null,
        )

        assertFalse(provider.isPlayable(unreachable))
    }

    @Test
    fun deterministicCurrentProtocolFailureFallsBackAndSkipsOnlyThatRoute() {
        val server = MockWebServer()
        server.start()
        try {
            val origin = server.url("/").toString().removeSuffix("/")
            val protocol = AllAnimeProtocolVersion(
                version = "test",
                buildId = "44",
                currentSourcesHash = "current",
                legacySourcesHash = "legacy",
                cryptoMask = "00".repeat(32),
                currentApiOrigin = origin,
                legacyApi = "$origin/api",
                apiReferer = "$origin/",
                apiOrigin = origin,
                playerReferer = "$origin/",
            )
            val legacyPayload =
                """{"data":{"episode":{"sourceUrls":[{"sourceName":"embed","sourceUrl":"https://embed.test/player","type":"iframe","priority":1}]}}}"""
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":"missing_or_invalid_lane"}"""),
            )
            server.enqueue(MockResponse().setBody(legacyPayload))
            server.enqueue(MockResponse().setBody(legacyPayload))
            val provider = AllAnimeProvider(
                OkHttpClient(),
                Json { ignoreUnknownKeys = true },
                protocol,
            )

            repeat(2) {
                val result = provider.sourcesForShow("show", "sub", 1)
                assertEquals(AllAnimeProvider.SourceRoute.LEGACY, provider.lastSourceRoute)
                assertEquals("https://embed.test/player", result.streams.single().url)
            }

            val paths = (1..server.requestCount).map { server.takeRequest().path.orEmpty() }
            assertEquals(3, paths.size)
            assertTrue(paths.first().startsWith("/client-crypto/v1/bootstrap"))
            assertTrue(paths.drop(1).all { it.startsWith("/api?") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun currentCaptchaUsesVisibleSolutionOnceAndRetriesAsPostWithoutLegacyFallback() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val origin = server.url("/").toString().removeSuffix("/")
            val partB = ByteArray(32) { (it + 1).toByte() }
            val encodedPartB = Base64.getEncoder().encodeToString(partB)
            val protocol = AllAnimeProtocolVersion(
                version = "captcha-test",
                buildId = "75",
                currentSourcesHash = "current-hash",
                legacySourcesHash = "legacy-hash",
                cryptoMask = "00".repeat(32),
                currentApiOrigin = origin,
                legacyApi = "$origin/legacy",
                apiReferer = "$origin/",
                apiOrigin = origin,
                playerReferer = "$origin/",
                siteUrl = origin,
            )
            val decrypted =
                """{"episode":{"sourceUrls":[{"sourceName":"embed","sourceUrl":"https://embed.test/player","type":"iframe","priority":1}]}}"""
            val encrypted = encryptCurrentPayload(decrypted, partB)
            server.enqueue(MockResponse().setBody("""{"epoch":6887,"partB":"$encodedPartB","k":"k7"}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"errors":[{"message":"NEED_CAPTCHA","extensions":{"code":"INTERNAL_SERVER_ERROR"}}],"data":{"episode":null}}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{"data":{"tobeparsed":"$encrypted"}}"""))
            var requestedUrl: String? = null
            val provider = AllAnimeProvider(
                client = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
                protocol = protocol,
                captchaRequester = { url ->
                    requestedUrl = url
                    AllAnimeCaptchaSolution("one-time-token", "turnstile")
                },
            )

            val result = provider.sourcesForShowInteractive("show", "sub", 1)

            assertEquals("$origin/captcha/turnstile", requestedUrl)
            assertEquals(AllAnimeProvider.SourceRoute.CURRENT, provider.lastSourceRoute)
            assertEquals("https://embed.test/player", result.streams.single().url)
            val bootstrap = server.takeRequest()
            val firstSource = server.takeRequest()
            val retry = server.takeRequest()
            assertTrue(bootstrap.path.orEmpty().contains("k=k7"))
            assertNotNull(bootstrap.getHeader("x-aa-boot"))
            assertEquals("GET", firstSource.method)
            assertEquals("POST", retry.method)
            assertEquals("/api", retry.path)
            assertFalse(retry.path.orEmpty().contains("one-time-token"))
            val retryBody = Json.parseToJsonElement(retry.body.readUtf8()).jsonObject
            val extensions = retryBody.getValue("extensions").jsonObject
            assertEquals("k7", extensions.getValue("k").jsonPrimitive.content)
            assertEquals(
                "one-time-token",
                extensions.getValue("captcha").jsonObject.getValue("token").jsonPrimitive.content,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun liveCatalogAndCurrentCaptchaHandshakeForJujutsuKaisenAndDeathNote() {
        assumeTrue(System.getenv("RUN_LIVE_ALLANIME_TESTS") == "1")
        var requestedCaptchaUrl: String? = null
        val provider = AllAnimeProvider(
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(Duration.ofSeconds(30))
                .build(),
            Json { ignoreUnknownKeys = true },
            captchaRequester = { url ->
                requestedCaptchaUrl = url
                null
            },
        )

        val fixtures = listOf(
            CatalogFixture(
                media = Media(
                    id = 1_535,
                    idMal = 1_535,
                    title = MediaTitle(romaji = "Death Note", english = "Death Note"),
                ),
                subCount = 37,
                dubCount = 37,
            ),
            CatalogFixture(
                media = Media(
                    id = 113_415,
                    idMal = 40_748,
                    title = MediaTitle(romaji = "Jujutsu Kaisen", english = "Jujutsu Kaisen"),
                ),
                subCount = 24,
                dubCount = 24,
            ),
        )

        fixtures.forEachIndexed { index, fixture ->
            if (index > 0) Thread.sleep(6_000L)
            val availability = provider.episodeAvailability(fixture.media)
            assertEquals("${fixture.media.title.preferred} Sub count", fixture.subCount, availability.sub.size)
            assertEquals("${fixture.media.title.preferred} Dub count", fixture.dubCount, availability.dub.size)

            requestedCaptchaUrl = null
            val failure = runBlocking {
                runCatching {
                    provider.sourcesInteractive(fixture.media, audio = "sub", episode = 1)
                }.exceptionOrNull()
            }
            assertEquals(
                "${AllAnimeProtocolConfig.active.currentApiOrigin}/captcha/turnstile",
                requestedCaptchaUrl,
            )
            assertTrue(
                "${fixture.media.title.preferred} did not reach the current CAPTCHA handshake",
                failure?.message.orEmpty().contains("cancelled", ignoreCase = true),
            )
        }
    }

    private data class CatalogFixture(
        val media: Media,
        val subCount: Int,
        val dubCount: Int,
    )

    private fun encryptCurrentPayload(plaintext: String, key: ByteArray): String {
        val iv = ByteArray(12) { (it + 5).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(
            byteArrayOf(1) + iv + cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8)),
        )
    }
}
