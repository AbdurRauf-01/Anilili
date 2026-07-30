package com.miruronative.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MalClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MalClient

    @Before
    fun setUp() {
        server = MockWebServer()
        client = MalClient(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            apiBaseUrl = server.url("/v2").toString(),
            accessTokenProvider = { "test-token" },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `progress patch is authenticated and server result is returned`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"watching","num_episodes_watched":1,"is_rewatching":false}""",
                ),
        )

        val confirmed = client.updateListStatus(
            malId = 62331,
            progress = 1,
            status = "watching",
        )

        assertEquals(1, confirmed.numEpisodesWatched)
        assertEquals("watching", confirmed.status)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v2/anime/62331/my_list_status", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        val form = request.body.readUtf8()
        assertTrue(form.contains("num_watched_episodes=1"))
        assertTrue(form.contains("status=watching"))
    }

    @Test
    fun `progress patch fails when MAL confirms a different episode`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"watching","num_episodes_watched":0,"is_rewatching":false}""",
                ),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                client.updateListStatus(malId = 62331, progress = 1, status = "watching")
            }
        }

        assertTrue(error.message.orEmpty().contains("confirmed 0 watched episodes"))
    }

    @Test
    fun `on hold list selection is mapped and confirmed`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"on_hold","num_episodes_watched":1,"is_rewatching":false}""",
                ),
        )

        val confirmed = client.updateListStatus(
            malId = 62331,
            status = MalClient.malStatus("PAUSED"),
            isRewatching = false,
        )

        assertEquals("on_hold", confirmed.status)
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("status=on_hold"))
        assertTrue(form.contains("is_rewatching=false"))
    }

    @Test
    fun `MAL no-op reasons distinguish completed and already synced entries`() {
        assertEquals(
            MalProgressSyncSkipReason.REMOTE_COMPLETED,
            malProgressSkipReason("COMPLETED", currentProgress = 12, targetProgress = 13),
        )
        assertEquals(
            MalProgressSyncSkipReason.ALREADY_AT_OR_AHEAD,
            malProgressSkipReason("CURRENT", currentProgress = 1, targetProgress = 1),
        )
        assertEquals(
            MalProgressSyncSkipReason.NO_CHANGE,
            malProgressSkipReason("CURRENT", currentProgress = 0, targetProgress = 1),
        )
    }
}
