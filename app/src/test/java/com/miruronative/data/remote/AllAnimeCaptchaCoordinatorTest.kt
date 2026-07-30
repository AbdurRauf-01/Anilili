package com.miruronative.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllAnimeCaptchaCoordinatorTest {
    @After
    fun cleanUp() {
        AllAnimeCaptchaCoordinator.resetForTest()
    }

    @Test
    fun solutionIsDeliveredOnceAndChallengeIsCleared() = runBlocking {
        val pending = async { AllAnimeCaptchaCoordinator.request("https://api.mkissa.net/captcha/turnstile") }
        while (AllAnimeCaptchaCoordinator.challenge.value == null) yield()
        val challenge = AllAnimeCaptchaCoordinator.challenge.value!!

        AllAnimeCaptchaCoordinator.submit(challenge.id, AllAnimeCaptchaSolution("token", "turnstile"))

        assertEquals(AllAnimeCaptchaSolution("token", "turnstile"), pending.await())
        assertNull(AllAnimeCaptchaCoordinator.challenge.value)
    }

    @Test
    fun cancellationReturnsWithoutRetainingChallenge() = runBlocking {
        val pending = async { AllAnimeCaptchaCoordinator.request("https://api.mkissa.net/captcha/turnstile") }
        while (AllAnimeCaptchaCoordinator.challenge.value == null) yield()
        val challenge = AllAnimeCaptchaCoordinator.challenge.value!!

        AllAnimeCaptchaCoordinator.cancel(challenge.id)

        assertNull(pending.await())
        assertNull(AllAnimeCaptchaCoordinator.challenge.value)
        assertTrue(challenge.id > 0)
    }
}
