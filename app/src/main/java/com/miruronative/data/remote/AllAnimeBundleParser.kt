package com.miruronative.data.remote

/**
 * Recovers MKissa's rotating build ID and four public mask seeds from its obfuscated JS bundle.
 * Adapted under Apache-2.0 from yuzono/anime-extensions revision
 * d4d64d315f127b9cf2ae60e2f2d53754ce722c02:
 * https://github.com/yuzono/anime-extensions/tree/d4d64d315f127b9cf2ae60e2f2d53754ce722c02/src/en/mkissa
 */
internal object AllAnimeBundleParser {
    data class BuildInfo(val buildId: String, val seeds: List<String>)

    fun parse(js: String): BuildInfo? {
        val buildId = BUILD_ID_REGEX.find(js)?.groupValues?.get(1) ?: return null
        val seeds = extractSeeds(js) ?: return null
        return BuildInfo(buildId, seeds)
    }

    private data class Base(val table: String, val offset: Int)
    private data class Alias(val base: String, val argumentIndex: Int, val delta: Int)

    private fun extractSeeds(js: String): List<String>? {
        val tables = readTables(js)
        val bases = BASE_DECODER_REGEX.findAll(js).associate { match ->
            match.groupValues[1] to Base(match.groupValues[4], fold(match.groupValues[3]))
        }
        val aliases = buildMap {
            bases.keys.forEach { put(it, Alias(it, 0, 0)) }
            ALIAS_DECODER_REGEX.findAll(js).forEach { match ->
                val (name, firstParameter, _, callee, argument, delta) = match.destructured
                if (callee !in bases) return@forEach
                put(
                    name,
                    Alias(
                        base = callee,
                        argumentIndex = if (argument == firstParameter) 0 else 1,
                        delta = delta.takeIf(String::isNotEmpty)?.let(::fold) ?: 0,
                    ),
                )
            }
        }

        for (match in SEED_ARRAY_REGEX.findAll(js)) {
            val calls = CALL_REGEX.findAll(match.groupValues[1]).map(MatchResult::value).toList()
            if (calls.size != AllAnimeMkissaCrypto.SEED_COUNT * 2) continue
            val table = CALL_REGEX.find(calls.first())
                ?.let { aliases[it.groupValues[1]] }
                ?.let { tables[bases[it.base]?.table] }
                ?: continue
            val matches = table.indices.mapNotNull { rotation ->
                seedsAt(calls, rotation, tables, bases, aliases)
            }
            matches.singleOrNull()?.let { return it }
        }
        return null
    }

    private fun seedsAt(
        calls: List<String>,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): List<String>? {
        val seeds = calls.chunked(2).mapNotNull { pair ->
            val first = resolve(pair[0], rotation, tables, bases, aliases) ?: return@mapNotNull null
            val second = resolve(pair[1], rotation, tables, bases, aliases) ?: return@mapNotNull null
            (first + second).takeIf(SEED_REGEX::matches)
        }
        return seeds.takeIf { it.size == AllAnimeMkissaCrypto.SEED_COUNT }
    }

    private fun resolve(
        call: String,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): String? {
        val match = CALL_REGEX.matchEntire(call) ?: return null
        val alias = aliases[match.groupValues[1]] ?: return null
        val base = bases[alias.base] ?: return null
        val table = tables[base.table]?.takeIf(List<String>::isNotEmpty) ?: return null
        val arguments = listOfNotNull(
            match.groupValues[2].toIntOrNull(),
            match.groupValues[3].toIntOrNull(),
        )
        val argument = arguments.getOrNull(alias.argumentIndex) ?: return null
        val index = argument + alias.delta - base.offset + rotation
        return table[((index % table.size) + table.size) % table.size]
    }

    private fun readTables(js: String): Map<String, List<String>> = buildMap {
        TABLE_HEAD_REGEX.findAll(js).forEach { match ->
            readStringArray(js, match.range.last)?.let { put(match.groupValues[1], it) }
        }
    }

    /** Refuses partial arrays so a bundle-format change cannot silently derive the wrong key. */
    private fun readStringArray(js: String, open: Int): List<String>? {
        val items = mutableListOf<String>()
        var index = open + 1
        while (index < js.length) {
            when (val character = js[index]) {
                ']' -> return items
                ',', ' ' -> index++
                '"', '\'' -> {
                    val value = StringBuilder()
                    index++
                    while (index < js.length && js[index] != character) {
                        if (js[index] == '\\') {
                            if (index + 1 >= js.length) return null
                            value.append(js[index + 1])
                            index += 2
                        } else {
                            value.append(js[index++])
                        }
                    }
                    if (index >= js.length) return null
                    index++
                    items += value.toString()
                }
                else -> return null
            }
        }
        return null
    }

    private fun fold(expression: String): Int {
        var total = 0
        TERM_REGEX.findAll(expression.replace(" ", "")).map(MatchResult::value).forEach { term ->
            var sign = 1
            var body = term
            while (body.startsWith('+') || body.startsWith('-')) {
                if (body.startsWith('-')) sign = -sign
                body = body.substring(1)
            }
            var value = 1
            body.split('*').forEach { factor -> value *= factor.toIntOrNull() ?: return 0 }
            total += sign * value
        }
        return total
    }

    private val BUILD_ID_REGEX = Regex("""!==\s*["']string["']\s*\?\s*["'](\d+)["']\s*:\s*["']["']""")
    private val TABLE_HEAD_REGEX = Regex("""function (\w+)\(\)\s*\{\s*(?:const|let|var)\s+\w+\s*=\s*\[""")
    private val BASE_DECODER_REGEX =
        Regex("""function (\w+)\((\w+)(?:,\w+)*\)\{return \2=\2-\(?([-\d+*\s]+?)\)?,(\w+)\(\)\[\2\]\}""")
    private val ALIAS_DECODER_REGEX =
        Regex("""function (\w+)\((\w+),(\w+)\)\{return (\w+)\((\w+)((?:[-+][\d+*\s-]+)?)\)\}""")
    private const val CALL_PATTERN = """(\w+)\(\s*(-?\d+)\s*(?:,\s*(-?\d+)\s*)?\)"""
    private val CALL_REGEX = Regex(CALL_PATTERN)
    private val SEED_ARRAY_REGEX =
        Regex("""=\[((?:$CALL_PATTERN\+$CALL_PATTERN,){3}$CALL_PATTERN\+$CALL_PATTERN)]""")
    private val SEED_REGEX = Regex("""[A-Za-z0-9+/]{11}=""")
    private val TERM_REGEX = Regex("""[-+]*[^-+]+""")
}
