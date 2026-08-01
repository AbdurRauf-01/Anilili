package com.anilili.diagnostics

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.WeakHashMap

/** API-22-compatible, bounded frame sample that does not keep waking the UI thread indefinitely. */
object DiagnosticsJankMonitor {
    private const val SAMPLE_DURATION_MS = 10_000L
    private const val SAMPLE_INTERVAL_MS = 60_000L
    private const val JANK_MULTIPLIER = 2.0
    private val monitors = WeakHashMap<Activity, Monitor>()

    fun install(activity: Activity) {
        val lifecycleOwner = activity as? LifecycleOwner ?: return
        synchronized(monitors) {
            if (monitors.containsKey(activity)) return
            val monitor = Monitor(activity)
            monitors[activity] = monitor
            lifecycleOwner.lifecycle.addObserver(monitor)
        }
    }

    private class Monitor(activity: Activity) : DefaultLifecycleObserver, Choreographer.FrameCallback {
        @Suppress("DEPRECATION")
        private val refreshRate = (activity.getSystemService(Activity.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay
            ?.refreshRate
            ?.takeIf { it >= 20f }
            ?: 60f
        private val expectedFrameNanos = (1_000_000_000.0 / refreshRate).toLong()
        private val handler = Handler(Looper.getMainLooper())
        private val finishSample = Runnable { stopSample("completed", scheduleNext = true) }
        private val nextSample = Runnable { if (resumed) startSample() }
        private var lastFrameNanos = 0L
        private var frameCount = 0L
        private var jankFrameCount = 0L
        private var maximumFrameNanos = 0L
        private var running = false
        private var resumed = false

        override fun onResume(owner: LifecycleOwner) {
            resumed = true
            startSample()
        }

        override fun onPause(owner: LifecycleOwner) {
            resumed = false
            handler.removeCallbacks(nextSample)
            stopSample("paused", scheduleNext = false)
        }

        private fun startSample() {
            if (running) return
            handler.removeCallbacks(nextSample)
            running = true
            lastFrameNanos = 0
            frameCount = 0
            jankFrameCount = 0
            maximumFrameNanos = 0
            Choreographer.getInstance().postFrameCallback(this)
            handler.postDelayed(finishSample, SAMPLE_DURATION_MS)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val previous = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            if (previous > 0) {
                frameCount++
                val duration = (frameTimeNanos - previous).coerceAtLeast(0)
                if (duration > (expectedFrameNanos * JANK_MULTIPLIER).toLong()) {
                    jankFrameCount++
                    maximumFrameNanos = maxOf(maximumFrameNanos, duration)
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun stopSample(reason: String, scheduleNext: Boolean) {
            if (!running) return
            running = false
            handler.removeCallbacks(finishSample)
            Choreographer.getInstance().removeFrameCallback(this)
            DiagnosticsLog.event(
                category = "render",
                name = "jank.sample_completed",
                attributes = mapOf(
                    "reason" to reason,
                    "sampleWindowMs" to SAMPLE_DURATION_MS,
                    "frames" to frameCount,
                    "jankFrames" to jankFrameCount,
                    "maxFrameMs" to (maximumFrameNanos / 1_000_000f),
                    "refreshRate" to refreshRate,
                ),
            )
            lastFrameNanos = 0
            if (scheduleNext && resumed) {
                handler.postDelayed(nextSample, SAMPLE_INTERVAL_MS - SAMPLE_DURATION_MS)
            }
        }
    }
}
