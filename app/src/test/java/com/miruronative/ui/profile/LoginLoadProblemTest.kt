package com.miruronative.ui.profile

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LoginLoadProblemTest {
    @Test
    fun missingWebViewExplainsTheDeviceFix() {
        val problem = LoginLoadProblem.WEBVIEW_UNAVAILABLE

        assertTrue(problem.title.contains("isn't available"))
        assertTrue(problem.guidance.contains("Android System WebView"))
    }

    @Test
    fun networkProblemMentionsDnsBecauseAniListCanBeRegionallyBlocked() {
        assertTrue(LoginLoadProblem.NETWORK.guidance.contains("private DNS"))
    }

    @Test
    fun pageProgressBecomesUsableBeforeSlowBackgroundResourcesFinish() {
        assertFalse(isLoginPageUsableProgress(84))
        assertTrue(isLoginPageUsableProgress(85))
    }
}
