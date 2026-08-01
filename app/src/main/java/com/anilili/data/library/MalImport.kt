package com.anilili.data.library

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One `<anime>` entry from a MyAnimeList XML export. */
data class MalImportEntry(
    val malId: Int,
    val title: String,
    val watchedEpisodes: Int,
    val status: String,
)

/** What an import run did, for the settings screen's result line. */
data class MalImportSummary(
    val totalEntries: Int,
    val added: Int,
    val alreadySaved: Int,
    val unmatched: Int,
)

object MalImport {
    const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    const val MAX_EXPANDED_XML_BYTES = 32 * 1024 * 1024

    /**
     * Reads a picker-provided export without allowing a malformed document provider to make the
     * app allocate an unbounded byte array. The URI and file name are deliberately not retained.
     */
    fun readSource(input: InputStream): ByteArray =
        input.readLimited(
            maxBytes = MAX_SOURCE_BYTES,
            tooLargeMessage = "The MAL export is larger than the 16 MB safety limit.",
        )

    /**
     * Parses a MyAnimeList XML export. MAL serves exports gzipped (`.xml.gz`), so both raw XML
     * and gzip are accepted. Throws on files that aren't a MAL export at all; entries without a
     * usable MAL id are skipped.
     */
    fun parse(bytes: ByteArray): List<MalImportEntry> {
        require(bytes.size <= MAX_SOURCE_BYTES) {
            "The MAL export is larger than the 16 MB safety limit."
        }
        val xmlBytes = if (isGzip(bytes)) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
                stream.readLimited(
                    maxBytes = MAX_EXPANDED_XML_BYTES,
                    tooLargeMessage = "The expanded MAL export is larger than the 32 MB safety limit.",
                )
            }
        } else {
            bytes
        }
        rejectUnsafeDeclarations(xmlBytes)
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The file comes from outside the app; never resolve external entities.
            // Android's bundled parser does not implement disallow-doctype-decl on every OS.
            // Declarations have already been rejected byte-for-byte above, so these platform
            // features are defense in depth and may safely remain best-effort.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
            isNamespaceAware = false
            isValidating = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        require(document.documentElement?.tagName == "myanimelist") { "Not a MyAnimeList export file" }
        val nodes = document.getElementsByTagName("anime")
        val seen = mutableSetOf<Int>()
        return buildList {
            for (i in 0 until nodes.length) {
                val anime = nodes.item(i) as? Element ?: continue
                val malId = anime.text("series_animedb_id")?.toIntOrNull()?.takeIf { it > 0 } ?: continue
                if (!seen.add(malId)) continue
                add(
                    MalImportEntry(
                        malId = malId,
                        title = anime.text("series_title").orEmpty(),
                        watchedEpisodes = anime.text("my_watched_episodes")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        status = normalizeStatus(anime.text("my_status")),
                    ),
                )
            }
        }
    }

    /** Old exports and some third-party tools write the numeric status codes. */
    internal fun normalizeStatus(raw: String?): String =
        when (raw?.trim()?.lowercase(Locale.US)?.replace(" ", "")) {
            "watching", "1" -> "Watching"
            "completed", "2" -> "Completed"
            "on-hold", "onhold", "3" -> "On-Hold"
            "dropped", "4" -> "Dropped"
            else -> "Plan to Watch"
        }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    private fun rejectUnsafeDeclarations(bytes: ByteArray) {
        // MAL exports are UTF-8. Reject NUL-containing encodings so a UTF-16 declaration cannot
        // hide its markup from this ASCII-compatible scan and then be interpreted by the parser.
        require(bytes.none { it == 0.toByte() }) { "Unsupported MyAnimeList XML encoding" }
        val markup = String(bytes, Charsets.ISO_8859_1).uppercase(Locale.US)
        require("<!DOCTYPE" !in markup && "<!ENTITY" !in markup) {
            "MyAnimeList exports with document type or entity declarations are not supported"
        }
    }

    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun InputStream.readLimited(maxBytes: Int, tooLargeMessage: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { tooLargeMessage }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
