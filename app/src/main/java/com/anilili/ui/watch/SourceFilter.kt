package com.anilili.ui.watch

import com.anilili.data.model.Category

/** Audio axis of the source picker's filter: everything, or just one category. */
enum class AudioFilter(val label: String, val category: Category?) {
    ANY("All", null),
    SUB("Sub", Category.SUB),
    DUB("Dub", Category.DUB),
}

/**
 * Narrows the server list to what the viewer actually wants.
 *
 * The two axes cost very different things to answer. Sub vs dub comes straight from the episode
 * catalog, so it filters instantly and exactly. Language only exists inside a *resolved* source,
 * which is why an option whose capabilities are not [SourceCapabilities.known] yet is kept in the
 * list rather than hidden: it may well be the French track being looked for, and dropping it would
 * turn "still checking" into a confident, wrong "not available".
 */
fun filterSourceOptions(
    options: List<WatchSourceOption>,
    capabilities: Map<Pair<String, Category>, SourceCapabilities>,
    audio: AudioFilter,
    language: String?,
): List<WatchSourceOption> = options.filter { option ->
    val matchesAudio = audio.category == null || option.category == audio.category
    if (!matchesAudio) return@filter false
    if (language == null) return@filter true
    val capability = capabilities[option.provider to option.category] ?: SourceCapabilities()
    // Unchecked options stay; only a source we have actually inspected can be ruled out.
    !capability.known || language in capability.languages
}

/**
 * Whether the language filter can be trusted yet. While options are still unchecked the row is
 * necessarily incomplete, and the picker should say so rather than imply the list is final.
 */
fun languageFilterIsComplete(
    options: List<WatchSourceOption>,
    capabilities: Map<Pair<String, Category>, SourceCapabilities>,
): Boolean = options.all { capabilities[it.provider to it.category]?.known == true }
