package com.anilili.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultQualityTest {
    @Test
    fun `auto never picks a height`() {
        assertNull(DefaultQuality.AUTO.pickHeight(listOf(1080, 720, 480)))
    }

    @Test
    fun `highest picks the top available height`() {
        assertEquals(1080, DefaultQuality.HIGHEST.pickHeight(listOf(480, 1080, 720)))
        assertNull(DefaultQuality.HIGHEST.pickHeight(emptyList()))
    }

    @Test
    fun `fixed target picks closest height without going over`() {
        assertEquals(720, DefaultQuality.P720.pickHeight(listOf(1080, 720, 480)))
        assertEquals(480, DefaultQuality.P720.pickHeight(listOf(1080, 480)))
    }

    @Test
    fun `fixed target falls back to lowest when everything is above it`() {
        assertEquals(720, DefaultQuality.P480.pickHeight(listOf(1080, 720)))
    }

    @Test
    fun `data saver picks 360p when available`() {
        assertEquals(360, DefaultQuality.P360.pickHeight(listOf(1080, 720, 480, 360)))
        assertEquals(360, DefaultQuality.P360.maxHeight)
    }

    @Test
    fun `data saver uses lowest server rendition when 360p is unavailable`() {
        assertEquals(480, DefaultQuality.P360.pickHeight(listOf(1080, 720, 480)))
    }

    @Test
    fun `stored values round-trip and unknown falls back to highest`() {
        DefaultQuality.entries.forEach { quality ->
            assertEquals(quality, DefaultQuality.fromStored(quality.storedValue))
        }
        assertEquals(DefaultQuality.HIGHEST, DefaultQuality.fromStored(null))
        assertEquals(DefaultQuality.HIGHEST, DefaultQuality.fromStored("bogus"))
    }

    @Test
    fun `download qualities expose their resolution caps and round-trip`() {
        assertNull(DownloadQuality.BEST.maxHeight)
        assertEquals(1080, DownloadQuality.P1080.maxHeight)
        assertEquals(360, DownloadQuality.P360.maxHeight)
        DownloadQuality.entries.forEach { quality ->
            assertEquals(quality, DownloadQuality.fromStored(quality.storedValue))
        }
        assertEquals(DownloadQuality.BEST, DownloadQuality.fromStored("bogus"))
    }

    @Test
    fun `download destinations expose included copies and round-trip`() {
        assertEquals(true, DownloadDestination.APP_ONLY.includesApp)
        assertEquals(false, DownloadDestination.APP_ONLY.includesDevice)
        assertEquals(false, DownloadDestination.DEVICE_ONLY.includesApp)
        assertEquals(true, DownloadDestination.DEVICE_ONLY.includesDevice)
        assertEquals(true, DownloadDestination.BOTH.includesApp)
        assertEquals(true, DownloadDestination.BOTH.includesDevice)
        DownloadDestination.entries.forEach { destination ->
            assertEquals(destination, DownloadDestination.fromStored(destination.storedValue))
        }
        // Unset means "give me an MP4 in Downloads": cache-only segments are useless outside the app.
        assertEquals(DownloadDestination.DEVICE_ONLY, DownloadDestination.fromStored(null))
    }
}
