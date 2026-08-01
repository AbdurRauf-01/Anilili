package com.anilili.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AniListClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AniListClient

    @Before
    fun setUp() {
        server = MockWebServer()
        client = AniListClient(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            endpoint = server.url("/graphql").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `MAL ids are matched in bounded 50 item requests`() = runBlocking {
        server.enqueue(graphQlMedia(id = 101, malId = 1))
        server.enqueue(graphQlMedia(id = 151, malId = 51))

        val result = client.mediaByMalIds((1..51).toList())

        assertEquals(listOf(1, 51), result.mapNotNull { it.idMal })
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/graphql", first.path)
        assertEquals("/graphql", second.path)
        assertTrue(first.body.readUtf8().contains("\"ids\":[1,2,3"))
        assertTrue(second.body.readUtf8().contains("\"ids\":[51]"))
    }

    @Test
    fun `a stalled AniList request remains cancellable`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(150) { client.mediaByMalIds(listOf(1)) }
            }
        }
    }

    private fun graphQlMedia(id: Int, malId: Int): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"data":{"Page":{"media":[{"id":$id,"idMal":$malId,"title":{"english":"Anime $malId"}}]}}}""",
            )
}
