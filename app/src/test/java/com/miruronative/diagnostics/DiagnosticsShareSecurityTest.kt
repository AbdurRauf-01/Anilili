package com.miruronative.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsShareSecurityTest {
    @Test
    fun `tokens are random and suitable for a URL path`() {
        val first = DiagnosticsShareSecurity.newToken()
        val second = DiagnosticsShareSecurity.newToken()

        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `only exact GET token path is authorized`() {
        val token = "0123456789abcdef0123456789abcdef"
        val path = DiagnosticsShareSecurity.path(token)

        assertTrue(DiagnosticsShareSecurity.isAuthorized("GET $path HTTP/1.1", token))
        assertFalse(DiagnosticsShareSecurity.isAuthorized("GET / HTTP/1.1", token))
        assertFalse(DiagnosticsShareSecurity.isAuthorized("POST $path HTTP/1.1", token))
        assertFalse(DiagnosticsShareSecurity.isAuthorized("GET $path?copy=1 HTTP/1.1", token))
    }
}
