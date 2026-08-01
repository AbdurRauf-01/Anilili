package com.anilili.data

import com.anilili.data.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A user reported picking English from the player's audio menu on a server the app calls
 * "sub only", and the app then switching servers whenever prefer-dub was on — deciding no dub
 * existed while an English track sat in the menu. These servers ship one file with several audio
 * tracks, so the catalog only ever lists it under sub.
 */
class MultiAudioDubTest {

    @Test
    fun `a dub request on a multi-audio server searches its sub catalog`() {
        assertEquals(Category.SUB, ProviderCatalog.dubCapableCategory("kaa", Category.DUB))
        assertEquals(Category.SUB, ProviderCatalog.dubCapableCategory("reanime", Category.DUB))
        // Case is provider-authored and has varied between catalogs.
        assertEquals(Category.SUB, ProviderCatalog.dubCapableCategory("KAA", Category.DUB))
    }

    @Test
    fun `a sub request is never redirected`() {
        assertEquals(Category.SUB, ProviderCatalog.dubCapableCategory("kaa", Category.SUB))
        assertEquals(Category.SUB, ProviderCatalog.dubCapableCategory("bonk", Category.SUB))
    }

    @Test
    fun `ordinary servers keep separate sub and dub catalogs`() {
        // Redirecting these would hand a viewer Japanese audio and call it a dub.
        assertEquals(Category.DUB, ProviderCatalog.dubCapableCategory("bonk", Category.DUB))
        assertEquals(Category.DUB, ProviderCatalog.dubCapableCategory("anibd", Category.DUB))
        assertEquals(Category.DUB, ProviderCatalog.dubCapableCategory("anikoto", Category.DUB))
    }
}
