package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * RareAnimes (rareanimes.mov, "RareToonsIndia") — Hindi/Tamil/Telugu dub+sub source. The site is
 * plain WordPress; every episode's WatchMultiQuality link chains through codedew.com
 * (`/zipper/?url=…` → 302 → `/multiquality/?url=<fid>`) into an `argon.*` JW Player embed whose
 * `_juicycodes` blob decodes client-side to a single HLS master playlist (see [JuicyCodesDecoder]).
 *
 * All three languages are exposed through the pipe's single `dub` channel with the language name
 * on [StreamItem.audio], until the sub|dub category model grows real per-language rows.
 *
 * Spike limitation: AniList's single-entry series (e.g. Naruto Shippuden = 500 episodes) are split
 * into per-season posts on this site with episode numbering restarting each season. We aggregate
 * only the best-scoring post, so episodes map 1:1 only within that post's season.
 */
internal class RareAnimesProvider(private val client: OkHttpClient) {
    private data class Catalog(val postUrl: String, val episodes: List<RareAnimesParser.Episode>)

    private val catalogs = ConcurrentHashMap<Int, Catalog>()

    fun episodeAvailability(media: Media): EpisodeAvailability {
        val episodes = catalog(media).episodes
        if (episodes.isEmpty()) error("RareAnimes has no episodes for this title")
        // Every track is a dub (Hindi/Tamil/Telugu); there is no Japanese-audio "sub" offering.
        return EpisodeAvailability(emptySet(), episodes.map { it.number }.toSet())
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val entry = catalog(media).episodes.firstOrNull { it.number == episode }
            ?: error("RareAnimes episode $episode is not in the catalog")
        val streams = entry.languages.mapNotNull { (language, links) ->
            val link = RareAnimesParser.preferred(links) ?: return@mapNotNull null
            runCatching { resolveStream(link.url, language) }
                .onFailure {
                    com.miruronative.diagnostics.DiagnosticsLog.throwable(
                        "RareAnimes resolve failed ep=$episode lang=$language",
                        it,
                    )
                }
                .getOrNull()
        }
        if (streams.isEmpty()) error("RareAnimes episode $episode has no playable streams")
        return SourcesResult(streams.distinctBy(StreamItem::url), emptyList(), null, null)
    }

    private fun catalog(media: Media): Catalog = catalogs[media.id] ?: run {
        val post = resolvePost(media)
        val episodes = RareAnimesParser.parseEpisodes(get(post.url, referer = "$BASE/"))
        if (episodes.isNotEmpty()) catalogs[media.id] = Catalog(post.url, episodes)
        Catalog(post.url, episodes)
    }

    /** Best-scoring search hit across the AniList titles, mirroring AnivexaClient.resolveSlug. */
    private fun resolvePost(media: Media): RareAnimesParser.SearchResult {
        val titles = listOfNotNull(media.title.english, media.title.romaji, media.title.native)
            .filter { it.isNotBlank() }.distinct()
        val candidates = titles.flatMap { title ->
            runCatching {
                RareAnimesParser.parseSearch(get("$BASE/?s=${enc(title)}", referer = "$BASE/"))
            }.getOrDefault(emptyList())
        }.distinctBy { it.url }
        val scored = candidates.maxByOrNull { candidate ->
            titles.maxOf { NativeProviderParsers.titleSelectionScore(it, candidate.title) }
        } ?: error("RareAnimes match not found")
        val score = titles.maxOf { NativeProviderParsers.titleSelectionScore(it, scored.title) }
        if (score < 0.28) error("RareAnimes title match was too weak (${scored.title})")
        return scored
    }

    /**
     * codedew zipper → (302) multiquality → argon embed → `_juicycodes` decode → HLS master.
     * OkHttp follows the zipper's redirect itself, so [get] on the zipper URL returns the
     * multiquality page directly.
     */
    private fun resolveStream(zipperUrl: String, language: String): StreamItem {
        val multiquality = get(zipperUrl, referer = "$BASE/")
        val embedUrl = RareAnimesParser.embedUrl(multiquality)
            ?: error("codedew page has no argon embed")
        val embedOrigin = runCatching {
            val uri = URI(embedUrl)
            "${uri.scheme}://${uri.host}/"
        }.getOrDefault("https://argon.razorshell.space/")
        val embed = get(embedUrl, referer = "https://codedew.com/")
        val blob = JuicyCodesDecoder.extractBlob(embed) ?: error("argon embed has no juicycodes blob")
        val config = JuicyCodesDecoder.config(JuicyCodesDecoder.decode(blob))
        val label = language.replaceFirstChar { it.uppercase() }
        return StreamItem(
            url = config.hlsUrl,
            type = "hls",
            quality = "MultiQuality",
            audio = label,
            referer = embedOrigin,
            isActive = language == "hindi",
            width = null,
            height = null,
        )
    }

    private fun get(url: String, referer: String?): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .apply { referer?.let { header("Referer", it) } }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("RareAnimes HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    companion object {
        private const val BASE = "https://www.rareanimes.mov"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

/** Pure-HTML parsing for RareAnimes pages, kept separate so unit tests never touch the network. */
internal object RareAnimesParser {
    data class SearchResult(val url: String, val title: String)
    data class ServerLink(val url: String, val anchor: String)

    /** Episode number → per-language server links (`hindi`/`tamil`/`telugu`). */
    data class Episode(val number: Int, val languages: Map<String, List<ServerLink>>)

    private val ARTICLE = Regex(
        """<article\b[^>]*\bherald-lay-b\b[^>]*>([\s\S]*?)</article>""",
        RegexOption.IGNORE_CASE,
    )
    private val ARTICLE_LINK = Regex(
        """<a\b[^>]*?href="(https?://[^"]+)"[^>]*?title="([^"]+)"[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val EPISODE_HEAD = Regex(""">\s*Episode\s+0*(\d+)""", RegexOption.IGNORE_CASE)
    private val LANGUAGE = Regex("""\b(Hindi|Tamil|Telugu)\b""", RegexOption.IGNORE_CASE)
    private val ZIPPER_LINK = Regex(
        """<a\b[^>]*?href="(https?://codedew\.com/zipper/\?url=[^"]+)"[^>]*>([\s\S]*?)</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val EMBED = Regex("""https://[a-z0-9.-]+/embed/[A-Za-z0-9]+""")

    fun parseSearch(html: String): List<SearchResult> = ARTICLE.findAll(html).mapNotNull { article ->
        ARTICLE_LINK.find(article.groupValues[1])?.let { link ->
            SearchResult(link.groupValues[1], NativeProviderParsers.decodeEntities(link.groupValues[2]))
        }
    }.distinctBy { it.url }.toList()

    /**
     * Episode blocks run from one `>Episode NN` heading to the next; inside a block, each
     * language label ("Hindi Uncut", "Tamil", "Telugu" — plain text or `ra-l-*` spans) opens the
     * section holding that language's codedew links. Both markup generations the site has used
     * (`<p>Episode 01…` paragraphs and `<span class="ra-ep-title">` blocks) match EPISODE_HEAD.
     */
    fun parseEpisodes(html: String): List<Episode> {
        val heads = EPISODE_HEAD.findAll(html).toList()
        return heads.mapIndexed { index, head ->
            val end = heads.getOrNull(index + 1)?.range?.first ?: html.length
            val block = html.substring(head.range.first, end)
            val labels = LANGUAGE.findAll(block).toList()
            val languages = labels.mapIndexedNotNull { labelIndex, label ->
                val sectionEnd = labels.getOrNull(labelIndex + 1)?.range?.first ?: block.length
                val links = ZIPPER_LINK.findAll(block.substring(label.range.first, sectionEnd))
                    .map { match ->
                        ServerLink(
                            url = NativeProviderParsers.decodeEntities(match.groupValues[1]),
                            anchor = NativeProviderParsers.stripTags(match.groupValues[2]),
                        )
                    }
                    .toList()
                if (links.isEmpty()) null else label.value.lowercase() to links
            }.toMap()
            Episode(head.groupValues[1].toInt(), languages)
        }.filter { it.languages.isNotEmpty() }
    }

    /** WatchMultiQuality is the only server we can decode natively; keep the others as fallback order. */
    fun preferred(links: List<ServerLink>): ServerLink? =
        links.firstOrNull { it.anchor.contains("multiquality", ignoreCase = true) }
            ?: links.firstOrNull { it.anchor.contains("watch", ignoreCase = true) }
            ?: links.firstOrNull()

    /** The argon (or successor-domain) player iframe on a codedew multiquality page. */
    fun embedUrl(multiqualityHtml: String): String? = EMBED.find(multiqualityHtml)?.value
}
