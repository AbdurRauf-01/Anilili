package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderAttemptOrderTest {
    private val providers = listOf(
        "bonk",
        "kiwi",
        "pewe",
        "bee",
        "ally",
        "anikoto",
        "allanime",
        "animekai",
    )

    @Test
    fun `default order leads with bonk then anibd`() {
        val all = listOf("senshi", "anibd", "bonk", "kiwi", "anikoto")
        assertEquals(
            listOf("bonk", "anibd", "senshi"),
            all.sortedBy { ProviderCatalog.sortKey(it) }.take(3),
        )
        // Fallback from the default keeps the pair first, then spreads across backends.
        assertEquals(
            listOf("bonk", "anibd", "kiwi", "senshi"),
            providerAttemptOrder("bonk", all).take(4),
        )
    }

    @Test
    fun `miruro preference reaches independent backend within attempt budget`() {
        assertEquals(
            listOf("bonk", "anikoto", "kiwi", "allanime", "pewe"),
            providerAttemptOrder("bonk", providers).take(5),
        )
    }

    @Test
    fun `anivexa preference reaches independent backend within attempt budget`() {
        assertEquals(
            listOf("anikoto", "bonk", "allanime", "kiwi", "animekai"),
            providerAttemptOrder("anikoto", providers).take(5),
        )
    }

    @Test
    fun `settings fallbacks are tried straight after the preferred server`() {
        assertEquals(
            listOf("bonk", "animekai", "ally"),
            providerAttemptOrder("bonk", providers, listOf("animekai", "ally")).take(3),
        )
    }

    @Test
    fun `empty, auto, duplicate and self-referencing fallback slots are ignored`() {
        val plain = providerAttemptOrder("bonk", providers)
        // "auto" is what an unset slot stores, so a half-filled pair behaves like none at all.
        assertEquals(plain, providerAttemptOrder("bonk", providers, listOf("auto", "auto")))
        assertEquals(plain, providerAttemptOrder("bonk", providers, listOf("", "  ")))
        // Naming the preferred server again must not push a real fallback out of its slot.
        assertEquals(
            listOf("bonk", "ally"),
            providerAttemptOrder("bonk", providers, listOf("bonk", "ally")).take(2),
        )
        assertEquals(
            listOf("bonk", "ally"),
            providerAttemptOrder("bonk", providers, listOf("ally", "ally")).take(2),
        )
    }

    @Test
    fun `every provider still appears exactly once when fallbacks are set`() {
        val order = providerAttemptOrder("bonk", providers, listOf("animekai", "ally"))
        assertEquals(providers.size, order.size)
        assertEquals(providers.toSet(), order.toSet())
        assertEquals(order.size, order.distinct().size)
    }
}
