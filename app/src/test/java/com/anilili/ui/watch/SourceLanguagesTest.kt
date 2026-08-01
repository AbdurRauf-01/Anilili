package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceLanguagesTest {
    @Test
    fun `iso codes, english names and endonyms land in the same bucket`() {
        val english = listOf("en", "EN", "eng", "English", "english")
        english.forEach { assertEquals(it, "English", normalizeLanguage(it)) }

        listOf("es", "Spanish", "Espanol", "Castellano", "Latino")
            .forEach { assertEquals(it, "Spanish", normalizeLanguage(it)) }

        listOf("ja", "jpn", "Japanese", "Nihongo")
            .forEach { assertEquals(it, "Japanese", normalizeLanguage(it)) }
    }

    @Test
    fun `regional variants collapse onto their base language`() {
        // One "Portuguese" bucket beats separate pt and pt-BR entries in a filter row.
        assertEquals("Portuguese", normalizeLanguage("pt"))
        assertEquals("Portuguese", normalizeLanguage("pt-BR"))
        assertEquals("Portuguese", normalizeLanguage("pt_PT"))
        assertEquals("Spanish", normalizeLanguage("es-419"))
    }

    @Test
    fun `decorated provider labels keep only the language`() {
        assertEquals("Hindi", normalizeLanguage("Hindi Uncut"))
        assertEquals("English", normalizeLanguage("English Dub"))
        assertEquals("English", normalizeLanguage("[English] Subtitles"))
        assertEquals("Tamil", normalizeLanguage("  Tamil  "))
    }

    @Test
    fun `labels that only restate the category are not languages`() {
        // These arrive as StreamItem.audio on providers that have no real language to report;
        // letting them through would put a "Sub" entry at the top of every language filter.
        listOf("sub", "SUB", "dub", "Dubbed", "raw", "default", "auto", "und", "", "   ")
            .forEach { assertNull("'$it' should not be a language", normalizeLanguage(it)) }
        assertNull(normalizeLanguage(null))
    }

    @Test
    fun `unknown names pass through title-cased rather than being dropped`() {
        // Better to show a provider's odd label than to hide the source entirely.
        assertEquals("Klingon", normalizeLanguage("klingon"))
        assertEquals("Cantonese", normalizeLanguage("cantonese"))
    }
}
