package com.miruronative.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewProcessControllerTest {
    @Test
    fun `only constrained TVs terminate an idle renderer`() {
        assertTrue(shouldTerminateRenderer(isTv = true, isLowRamDevice = true, memoryClassMb = 512))
        assertTrue(shouldTerminateRenderer(isTv = true, isLowRamDevice = false, memoryClassMb = 256))
        assertFalse(shouldTerminateRenderer(isTv = true, isLowRamDevice = false, memoryClassMb = 512))
        assertFalse(shouldTerminateRenderer(isTv = false, isLowRamDevice = true, memoryClassMb = 128))
    }
}
