package com.anilili.data.remote

import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AniSkipClientTest {
    @Test
    fun requestsV2TypesAndMatchesTheClosestVideoDuration() {
        lateinit var requestedUrl: HttpUrl
        val client = client(
            body = """
                {
                  "found": true,
                  "results": [
                    {"skipType":"op","episodeLength":1200.0,"interval":{"startTime":10.0,"endTime":100.0}},
                    {"skipType":"op","episodeLength":1440.0,"interval":{"startTime":20.0,"endTime":110.0}},
                    {"skipType":"ed","episodeLength":1440.0,"interval":{"startTime":1300.0,"endTime":1390.0}}
                  ]
                }
            """.trimIndent(),
            onRequest = { requestedUrl = it },
        )

        val skip = AniSkipClient(client, Json).skipTimes(813, 56, 1438.0)

        assertEquals(listOf("op", "ed", "mixed-op", "mixed-ed"), requestedUrl.queryParameterValues("types[]"))
        assertEquals("1438.0", requestedUrl.queryParameter("episodeLength"))
        assertEquals(20.0, skip?.introStart)
        assertEquals(110.0, skip?.introEnd)
        assertEquals(1300.0, skip?.outroStart)
        assertEquals(1390.0, skip?.outroEnd)
    }

    @Test
    fun mixedSegmentsFillMissingPlainOpeningAndInvalidIntervalsAreIgnored() {
        val client = client(
            body = """
                {
                  "found": true,
                  "results": [
                    {"skipType":"op","episodeLength":1440.0,"interval":{"startTime":90.0,"endTime":20.0}},
                    {"skipType":"mixed-op","episodeLength":1440.0,"interval":{"startTime":4.5,"endTime":94.5}}
                  ]
                }
            """.trimIndent(),
        )

        val skip = AniSkipClient(client, Json).skipTimes(1, 1, 1440.0)

        assertEquals(4.5, skip?.introStart)
        assertEquals(94.5, skip?.introEnd)
        assertEquals(null, skip?.outroStart)
    }

    @Test
    fun serverErrorsRemainObservableToTheCaller() {
        val client = client(body = "{\"statusCode\":500,\"message\":\"Internal server error\"}", code = 500)

        val error = assertThrows(IOException::class.java) {
            AniSkipClient(client, Json).skipTimes(813, 56, 1440.0)
        }

        assertEquals("AniSkip HTTP 500", error.message)
    }

    private fun client(
        body: String,
        code: Int = 200,
        onRequest: (HttpUrl) -> Unit = {},
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            onRequest(request.url)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()
}
