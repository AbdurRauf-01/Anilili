package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Native AniDB App resolver.
 *
 * The provider is matched by AniList/MAL identity rather than title alone, then its direct HLS
 * master is handed to the app's existing Media3/Cronet playback path. No provider JavaScript or
 * WebView is kept alive during playback.
 */
internal class AniDbAppProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = BASE,
) {
    private data class Series(
        val slug: String,
        val siteId: Int,
    )

    internal data class Episode(
        val id: Int,
        val number: Int,
        val filler: Boolean,
    )

    internal data class Language(
        val code: String,
        val name: String,
        val embedUrl: String,
    )

    private val seriesCache = ConcurrentHashMap<Int, Series>()
    private val episodeCache = ConcurrentHashMap<Int, List<Episode>>()
    private val languageCache = ConcurrentHashMap<Int, List<Language>>()

    fun episodeAvailability(media: Media, expectedCount: Int): EpisodeAvailability {
        val series = resolveSeries(media)
        val episodes = episodes(series)
        if (episodes.isEmpty()) error("AniDB App returned no episode catalog")
        val offset = AniDbAppParser.inferOffset(episodes.map(Episode::number), expectedCount)
        val normalized = episodes.mapNotNull { episode ->
            (episode.number - offset).takeIf { it >= 1 && (expectedCount <= 0 || it <= expectedCount) }
                ?.let { number -> episode to number }
        }
        if (normalized.isEmpty()) error("AniDB App episode numbering did not match this series")

        val firstLanguages = languages(normalized.first().first, series)
        val sub = if (
            firstLanguages.isEmpty() ||
            AniDbAppParser.languageForAudio(firstLanguages, "sub") != null
        ) {
            normalized.mapTo(linkedSetOf()) { it.second }
        } else {
            emptySet()
        }

        val dub = when {
            AniDbAppParser.languageForAudio(firstLanguages, "dub") == null -> emptySet()
            normalized.size == 1 -> setOf(normalized.first().second)
            AniDbAppParser.languageForAudio(languages(normalized.last().first, series), "dub") != null ->
                normalized.mapTo(linkedSetOf()) { it.second }
            else -> {
                // Dubs normally release as a contiguous prefix. Binary search the actual provider
                // episode rows so long-running shows do not trigger hundreds of language calls.
                var low = 0
                var high = normalized.lastIndex
                var lastDubIndex = 0
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    val hasDub = AniDbAppParser.languageForAudio(
                        languages(normalized[mid].first, series),
                        "dub",
                    ) != null
                    if (hasDub) {
                        lastDubIndex = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                normalized.take(lastDubIndex + 1).mapTo(linkedSetOf()) { it.second }
            }
        }
        return EpisodeAvailability(sub, dub)
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val series = resolveSeries(media)
        val episodes = episodes(series)
        val expected = expectedCount(media)
        val offset = AniDbAppParser.inferOffset(episodes.map(Episode::number), expected)
        val providerNumber = episode + offset
        val providerEpisode = episodes.firstOrNull { it.number == providerNumber }
            ?: error("AniDB App episode $episode was not found")
        val language = AniDbAppParser.languageForAudio(languages(providerEpisode, series), audio)
            ?: error("AniDB App episode $episode has no ${audio.uppercase()} source")
        val embedHtml = get(
            language.embedUrl,
            referer = "$baseUrl/",
            accept = "text/html, */*",
        )
        val hls = AniDbAppParser.hls(embedHtml)
            ?: error("AniDB App episode $episode returned no direct HLS stream")
        val stream = StreamItem(
            url = hls,
            type = "hls",
            quality = "AniDB App ${language.name}",
            audio = audio,
            referer = "$baseUrl/",
            isActive = true,
            width = null,
            height = null,
        )
        return SourcesResult(listOf(stream), emptyList(), null, null)
    }

    private fun resolveSeries(media: Media): Series {
        seriesCache[media.id]?.let { return it }
        val candidates = linkedMapOf<String, AniDbAppParser.Candidate>()
        titles(media).forEach { title ->
            AniDbAppParser.searchResults(
                get(
                    "$baseUrl/search/suggestions?q=${encode(title)}",
                    referer = "$baseUrl/home",
                    accept = "text/html, */*",
                    xhr = true,
                ),
            ).forEach { candidate -> candidates.putIfAbsent(candidate.slug, candidate) }
        }

        val ordered = candidates.values.sortedByDescending { candidate ->
            titles(media).maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, candidate.title) } ?: 0.0
        }
        var successfulIdentityPages = 0
        var lastIdentityFailure: Throwable? = null
        ordered.take(MAX_IDENTITY_CANDIDATES).forEach { candidate ->
            val pageResult = runCatching {
                get("$baseUrl/anime/${candidate.slug}", referer = "$baseUrl/home", accept = "text/html, */*")
            }.onFailure { lastIdentityFailure = it }
            val page = pageResult.getOrNull() ?: return@forEach
            successfulIdentityPages++
            val ids = AniDbAppParser.externalIds(page)
            val exactAniList = ids.anilistId == media.id
            val exactMalFallback = ids.anilistId == null && media.idMal != null && ids.malId == media.idMal
            if (!exactAniList && !exactMalFallback) return@forEach
            val series = Series(candidate.slug, candidate.siteId)
            seriesCache[media.id] = series
            return series
        }
        if (successfulIdentityPages == 0 && lastIdentityFailure != null) {
            throw IllegalStateException("AniDB App identity lookup failed", lastIdentityFailure)
        }
        error("AniDB App match not found for AniList ${media.id}")
    }

    private fun episodes(series: Series): List<Episode> {
        episodeCache[series.siteId]?.let { return it }
        val parsed = AniDbAppParser.episodes(
            json.parseToJsonElement(
                get(
                    "$baseUrl/api/frontend/anime/${series.siteId}/episodes",
                    referer = "$baseUrl/anime/${series.slug}",
                    accept = "application/json, */*",
                    xhr = true,
                ),
            ),
        )
        if (parsed.isNotEmpty()) episodeCache[series.siteId] = parsed
        return parsed
    }

    private fun languages(episode: Episode, series: Series): List<Language> {
        languageCache[episode.id]?.let { return it }
        val parsed = AniDbAppParser.languages(
            json.parseToJsonElement(
                get(
                    "$baseUrl/api/frontend/episode/${episode.id}/languages",
                    referer = "$baseUrl/anime/${series.slug}",
                    accept = "application/json, */*",
                    xhr = true,
                ),
            ),
        )
        // Empty results can be a temporary upstream state, so only retain positive responses.
        if (parsed.isNotEmpty()) languageCache[episode.id] = parsed
        return parsed
    }

    private fun get(
        url: String,
        referer: String,
        accept: String,
        xhr: Boolean = false,
    ): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", accept)
        if (xhr) builder.header("X-Requested-With", "XMLHttpRequest")
        client.newCall(builder.get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (AniDbAppParser.isCloudflareChallenge(body)) {
                error("AniDB App blocked this resolver with a Cloudflare challenge")
            }
            if (!response.isSuccessful) {
                error("AniDB App HTTP ${response.code}")
            }
            return body
        }
    }

    private fun expectedCount(media: Media): Int = when {
        media.status == "RELEASING" && (media.nextAiringEpisode?.episode ?: 0) > 1 ->
            media.nextAiringEpisode!!.episode!! - 1
        (media.episodes ?: 0) > 0 -> media.episodes!!
        media.format == "MOVIE" -> 1
        else -> 0
    }

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.userPreferred,
        media.title.native,
    ).map(String::trim).filter { it.length >= 2 }.distinct()

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    companion object {
        private const val BASE = "https://anidb.app"
        private const val MAX_IDENTITY_CANDIDATES = 8
        private const val USER_AGENT = "Miruro Android/1.0"
    }
}

internal object AniDbAppParser {
    data class Candidate(
        val slug: String,
        val siteId: Int,
        val title: String,
    )

    data class ExternalIds(
        val anilistId: Int?,
        val malId: Int?,
    )

    fun searchResults(html: String): List<Candidate> =
        Regex(
            """<a\b[^>]*\bdata-search-item\b[^>]*>[\s\S]*?</a>""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).mapNotNull { match ->
            val openTag = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE)
                .find(match.value)?.value ?: return@mapNotNull null
            val href = NativeProviderParsers.attr(openTag, "href")
            val slug = Regex("""/anime/([^/?#]+)""", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val siteId = Regex("""-(\d+)$""").find(slug)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val title = Regex(
                """<p\b[^>]*class=["'][^"']*\btext-sm\b[^"']*["'][^>]*>([\s\S]*?)</p>""",
                RegexOption.IGNORE_CASE,
            ).find(match.value)?.groupValues?.get(1)?.let(NativeProviderParsers::stripTags)
                .orEmpty().ifBlank { slug.replace('-', ' ') }
            Candidate(slug, siteId, title)
        }.distinctBy(Candidate::slug).toList()

    fun externalIds(html: String): ExternalIds = ExternalIds(
        anilistId = Regex("""https://anilist\.co/anime/(\d+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.toIntOrNull(),
        malId = Regex("""https://myanimelist\.net/anime/(\d+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.toIntOrNull(),
    )

    fun episodes(root: Any?): List<AniDbAppProvider.Episode> {
        val array = (root as? JsonObject)?.get("episodes") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val episode = element as? JsonObject ?: return@mapNotNull null
            val id = (episode["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val number = (episode["number"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            if (id <= 0 || number <= 0) return@mapNotNull null
            AniDbAppProvider.Episode(
                id = id,
                number = number,
                filler = (episode["filler"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }.distinctBy(AniDbAppProvider.Episode::number).sortedBy(AniDbAppProvider.Episode::number)
    }

    fun languages(root: Any?): List<AniDbAppProvider.Language> {
        val array = (root as? JsonObject)?.get("languages") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val language = element as? JsonObject ?: return@mapNotNull null
            fun string(name: String): String? =
                (language[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            AniDbAppProvider.Language(
                code = string("code") ?: return@mapNotNull null,
                name = string("name") ?: return@mapNotNull null,
                embedUrl = string("embed_url") ?: return@mapNotNull null,
            )
        }.distinctBy(AniDbAppProvider.Language::code)
    }

    fun languageForAudio(
        languages: List<AniDbAppProvider.Language>,
        audio: String,
    ): AniDbAppProvider.Language? {
        val preferred = if (audio == "dub") setOf("eng", "en", "english") else setOf("jpn", "ja", "japanese")
        return languages.firstOrNull { it.code.lowercase() in preferred }
            ?: languages.firstOrNull { it.name.lowercase() in preferred }
    }

    fun inferOffset(numbers: List<Int>, expectedCount: Int): Int {
        val valid = numbers.filter { it > 0 }
        if (valid.isEmpty() || expectedCount <= 0) return 0
        val min = valid.min()
        val max = valid.max()
        return if (min > expectedCount || (min > 1 && max - min + 1 >= expectedCount)) min - 1 else 0
    }

    fun hls(html: String): String? = NativeProviderParsers.hlsUrls(html).firstOrNull()

    fun isCloudflareChallenge(body: String): Boolean =
        body.contains("cf_chl", ignoreCase = true) ||
            body.contains("challenges.cloudflare.com", ignoreCase = true) ||
            body.contains("Just a moment", ignoreCase = true)
}
