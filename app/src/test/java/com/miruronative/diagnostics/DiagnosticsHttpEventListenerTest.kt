package com.miruronative.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsHttpEventListenerTest {

    @Test
    fun `navigation cancellation is not classified as a network failure`() {
        assertEquals("http.call.canceled", httpCallOutcomeName(callCanceled = true, failureMessage = null))
        assertEquals("http.call.canceled", httpCallOutcomeName(callCanceled = false, failureMessage = "Canceled"))
    }

    @Test
    fun `genuine io error remains a network failure`() {
        assertEquals(
            "http.call.failed",
            httpCallOutcomeName(callCanceled = false, failureMessage = "Connection reset"),
        )
    }
}
