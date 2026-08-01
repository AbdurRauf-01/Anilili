package com.anilili.ui.watch

import java.util.Locale

/**
 * Turns the free-text language labels providers emit into something a filter can group by.
 *
 * Every backend names tracks its own way: ISO codes ("en", "pt-BR"), English names ("Portuguese
 * (Brazil)"), endonyms ("Español"), or decorated labels ("English Dub", "Hindi Uncut"). Filtering
 * only works if "en", "English" and "English Dub" collapse to one bucket.
 *
 * Labels that merely restate the sub/dub category ("sub", "dub", "raw") carry no language at all
 * and are dropped — the category filter already covers that axis, and letting "sub" through would
 * put a bogus entry at the top of every language list.
 */

private val CATEGORY_LABELS = setOf("sub", "dub", "subbed", "dubbed", "raw", "default", "auto", "und")

/** Endonyms and common spellings that a plain [Locale] lookup will not resolve. */
private val ALIASES = mapOf(
    "espanol" to "Spanish",
    "espanol latino" to "Spanish",
    "latino" to "Spanish",
    "castellano" to "Spanish",
    "francais" to "French",
    "deutsch" to "German",
    "italiano" to "Italian",
    "portugues" to "Portuguese",
    "brazilian" to "Portuguese",
    "brasil" to "Portuguese",
    "nihongo" to "Japanese",
    "jpn" to "Japanese",
    "eng" to "English",
    "filipino" to "Tagalog",
    "bahasa" to "Indonesian",
    "bahasa indonesia" to "Indonesian",
    "farsi" to "Persian",
    "cn" to "Chinese",
    "zh" to "Chinese",
    "mandarin" to "Chinese",
)

/** Decorations providers bolt onto a language name that should not affect grouping. */
private val NOISE = Regex(
    """\b(dub(bed)?|sub(bed|titles?|s)?|audio|uncut|multi|hd|fhd|sd|soft|hard|cc|forced|full)\b""",
    RegexOption.IGNORE_CASE,
)

/** A bare language tag such as `pt-BR`, `es_419`, `zh-Hans`. */
private val LANGUAGE_TAG = Regex("""^([A-Za-z]{2,3})[-_][A-Za-z0-9]{2,4}$""")

/**
 * The display name for [raw], or null when it names no language.
 *
 * Returns a stable English name so the same language from two providers lands in one bucket.
 */
fun normalizeLanguage(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null

    // Resolve region-qualified tags on their base first. Stripping decorations would split
    // "pt_PT" on the underscore and leave two meaningless words behind.
    LANGUAGE_TAG.find(trimmed)?.let { match ->
        languageFor(match.groupValues[1].lowercase(Locale.US))?.let { return it }
    }

    // "English Dub" and "Hindi Uncut" are languages; "Dub" on its own is not.
    val cleaned = NOISE.replace(trimmed, " ")
        .replace(Regex("""[\[\](){}_/|]"""), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('-', '·', ',', '.')
        .trim()
    val key = cleaned.lowercase(Locale.US)
    if (key.isEmpty() || key in CATEGORY_LABELS) return null

    languageFor(key)?.let { return it }

    // Already an English language name ("Japanese", "Hindi"), or something we don't recognise —
    // show it rather than hiding the source behind an unmatched filter.
    return cleaned.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { it.titlecase(Locale.US) }
    }
}

private fun languageFor(key: String): String? {
    ALIASES[key]?.let { return it }
    if (key.length in 2..3) {
        val display = Locale.forLanguageTag(key).getDisplayLanguage(Locale.US)
        // forLanguageTag echoes the input back when it does not recognise the tag.
        if (display.isNotBlank() && !display.equals(key, ignoreCase = true)) return display
    }
    return null
}
