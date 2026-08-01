package com.anilili.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipTimesTest {
    @Test
    fun emptyAndReversedProviderObjectsAreNotUsable() {
        assertFalse(SkipTimes(null, null, null, null).hasUsableWindow())
        assertFalse(SkipTimes(90.0, 20.0, 1400.0, 1300.0).hasUsableWindow())
    }

    @Test
    fun eitherValidIntroOrOutroMakesMetadataUsable() {
        assertTrue(SkipTimes(null, 90.0, null, null).hasUsableWindow())
        assertTrue(SkipTimes(null, null, 1300.0, 1390.0).hasUsableWindow())
    }
}
