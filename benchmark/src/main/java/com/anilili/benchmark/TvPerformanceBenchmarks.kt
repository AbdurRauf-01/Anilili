package com.anilili.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvPerformanceBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithBaselineProfile() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), APP_WAIT_MS))
        device.waitForIdle()
    }

    @Test
    fun tvHomeDpadFocus() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), APP_WAIT_MS))
            device.waitForIdle()
        },
    ) {
        repeat(12) { device.pressDPadRight() }
        repeat(12) { device.pressDPadLeft() }
        repeat(4) { device.pressDPadDown() }
        repeat(12) { device.pressDPadRight() }
        device.waitForIdle()
    }
}
