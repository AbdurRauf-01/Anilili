package com.miruronative.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateTvCriticalJourneys() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), APP_WAIT_MS)) {
            "Anilili root window did not appear"
        }
        device.waitForIdle()

        // Compile the Compose focus/navigation paths that every remote-driven TV session uses.
        repeat(5) { device.pressDPadRight() }
        repeat(5) { device.pressDPadLeft() }
        repeat(3) { device.pressDPadDown() }
        repeat(8) { device.pressDPadRight() }
        repeat(4) { device.pressDPadLeft() }
        device.waitForIdle()
    }
}

internal const val TARGET_PACKAGE = "com.miruronative"
internal const val APP_WAIT_MS = 15_000L
