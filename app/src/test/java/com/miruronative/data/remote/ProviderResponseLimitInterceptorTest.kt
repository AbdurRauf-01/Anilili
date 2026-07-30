package com.miruronative.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderResponseLimitInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun acceptsAnExactSizeResponse() {
        server.enqueue(MockResponse().setBody("1234"))

        assertEquals("1234", readBody(limit = 4))
    }

    @Test
    fun rejectsAnOversizedDeclaredResponseBeforeBufferingIt() {
        server.enqueue(MockResponse().setBody("12345"))

        val error = runCatching { readBody(limit = 4) }.exceptionOrNull()

        assertTrue(error is ProviderResponseTooLargeException)
    }

    @Test
    fun rejectsAnOversizedChunkedResponseWhileReading() {
        server.enqueue(MockResponse().setChunkedBody("12345", 2))

        val error = runCatching { readBody(limit = 4) }.exceptionOrNull()

        assertTrue(error is ProviderResponseTooLargeException)
    }

    private fun readBody(limit: Long): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(ProviderResponseLimitInterceptor(limit))
            .build()
        val request = Request.Builder().url(server.url("/body")).build()
        return client.newCall(request).execute().use { it.body!!.string() }
    }
}
