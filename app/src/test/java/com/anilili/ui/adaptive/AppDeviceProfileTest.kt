package com.anilili.ui.adaptive

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDeviceProfileTest {
    @Test
    fun `phone uses compact navigation`() {
        val profile = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_NORMAL, 412)
        assertEquals(AppFormFactor.PHONE, profile.formFactor)
        assertFalse(profile.useNavigationRail)
        assertEquals(5, profile.episodeColumns)
    }

    @Test
    fun `tablet uses navigation rail and wider episode grid`() {
        val profile = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_NORMAL, 800)
        assertEquals(AppFormFactor.TABLET, profile.formFactor)
        assertTrue(profile.useNavigationRail)
        assertEquals(6, profile.episodeColumns)
    }

    @Test
    fun `television wins regardless of reported dp width`() {
        val profile = resolveAppDeviceProfile(Configuration.UI_MODE_TYPE_TELEVISION, 540)
        assertEquals(AppFormFactor.TV, profile.formFactor)
        assertTrue(profile.useNavigationRail)
        assertEquals(8, profile.episodeColumns)
    }

    @Test
    fun `low ram television uses low cost focus effects`() {
        val profile = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_TELEVISION,
            960,
            isLowRamDevice = true,
            memoryClassMb = 512,
        )

        assertTrue(profile.useLowCostTvEffects)
    }

    @Test
    fun `small heap television uses low cost focus effects even without low ram flag`() {
        val profile = resolveAppDeviceProfile(
            Configuration.UI_MODE_TYPE_TELEVISION,
            960,
            isLowRamDevice = false,
            memoryClassMb = 192,
        )

        assertTrue(profile.useLowCostTvEffects)
    }

    @Test
    fun `high memory television and handhelds keep standard effects`() {
        assertFalse(
            resolveAppDeviceProfile(
                Configuration.UI_MODE_TYPE_TELEVISION,
                960,
                memoryClassMb = 512,
            ).useLowCostTvEffects,
        )
        assertFalse(
            resolveAppDeviceProfile(
                Configuration.UI_MODE_TYPE_NORMAL,
                412,
                isLowRamDevice = true,
                memoryClassMb = 128,
            ).useLowCostTvEffects,
        )
    }
}
