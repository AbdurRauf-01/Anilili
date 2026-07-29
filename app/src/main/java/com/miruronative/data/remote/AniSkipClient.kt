package com.miruronative.data.remote

import com.miruronative.data.model.SkipTimes
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs

/**
 * AniSkip community skip-time database (aniskip.com), keyed by MAL id. Fallback for streaming
 * providers that don't carry intro/outro markers of their own (most Anivexa providers).
 */
class AniSkipClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    /**
     * Intro/outro windows for one episode; null when AniSkip has none (unknown episodes 404).
     * [episodeLengthSeconds] lets AniSkip select timestamps for the actual encode being watched.
     */
    fun skipTimes(malId: Int, episode: Int, episodeLengthSeconds: Double = 0.0): SkipTimes? {
        require(episodeLengthSeconds.isFinite() && episodeLengthSeconds >= 0.0) {
            "episodeLengthSeconds must be finite and non-negative"
        }
        val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode".toHttpUrl().newBuilder()
            .addQueryParameter("types[]", "op")
            .addQueryParameter("types[]", "ed")
            .addQueryParameter("types[]", "mixed-op")
            .addQueryParameter("types[]", "mixed-ed")
            .addQueryParameter("episodeLength", episodeLengthSeconds.toString())
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val root = client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("AniSkip HTTP ${response.code}")
            json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }
        if (root["found"]?.jsonPrimitive?.booleanOrNull != true) return null
        val results = root["results"]?.jsonArray?.mapNotNull { element ->
            val result = element.jsonObject
            val interval = result["interval"]?.jsonObject ?: return@mapNotNull null
            val start = interval["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = interval["endTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val type = result["skipType"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!start.isFinite() || !end.isFinite() || end <= start) return@mapNotNull null
            AniSkipSegment(
                type = type,
                start = start,
                end = end,
                episodeLength = result["episodeLength"]?.jsonPrimitive?.doubleOrNull,
            )
        }.orEmpty()
        val intro = pickSegment(results, "op", "mixed-op", episodeLengthSeconds)
        val outro = pickSegment(results, "ed", "mixed-ed", episodeLengthSeconds)
        if (intro == null && outro == null) return null
        return SkipTimes(intro?.start, intro?.end, outro?.start, outro?.end)
    }

    private fun pickSegment(
        results: List<AniSkipSegment>,
        primaryType: String,
        fallbackType: String,
        requestedLength: Double,
    ): AniSkipSegment? {
        val candidates = results.filter { it.type == primaryType }.ifEmpty {
            results.filter { it.type == fallbackType }
        }
        return candidates.minByOrNull { segment ->
            if (requestedLength <= 0.0) 0.0 else {
                segment.episodeLength?.takeIf(Double::isFinite)?.let { abs(it - requestedLength) }
                    ?: Double.MAX_VALUE
            }
        }
    }

    private data class AniSkipSegment(
        val type: String,
        val start: Double,
        val end: Double,
        val episodeLength: Double?,
    )
}
