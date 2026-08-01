package com.anilili.data.remote

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anilili.MainActivity
import com.anilili.diagnostics.WebViewProcessController
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AllAnimeCaptchaUiTest {
    @Test
    fun visibleChallengeAttachesAndReleasesItsWebViewWhenCancelled() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                // A non-challenge path on the allowed host keeps the dialog open deterministically;
                // the live provider test separately verifies the exact Turnstile URL.
                val pending = executor.submit<AllAnimeCaptchaSolution?> {
                    runBlocking {
                        AllAnimeCaptchaCoordinator.request("https://api.mkissa.net/captcha/ui-lifecycle-test")
                    }
                }

                waitUntil {
                    AllAnimeCaptchaCoordinator.challenge.value != null &&
                        WebViewProcessController.snapshotAttributes()
                            .getValue("trackedWebViewOwners")
                            .contains("allanime-captcha")
                }
                val challenge = requireNotNull(AllAnimeCaptchaCoordinator.challenge.value)
                AllAnimeCaptchaCoordinator.cancel(challenge.id)
                assertNull(pending.get(10, TimeUnit.SECONDS))
                waitUntil {
                    !WebViewProcessController.snapshotAttributes()
                        .getValue("trackedWebViewOwners")
                        .contains("allanime-captcha")
                }
                assertNull(AllAnimeCaptchaCoordinator.challenge.value)
            }
        } finally {
            executor.shutdownNow()
            AllAnimeCaptchaCoordinator.resetForTest()
        }
    }

    private fun waitUntil(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50L)
        }
        assertTrue("Timed out waiting for the AllAnime challenge UI state", condition())
    }
}
