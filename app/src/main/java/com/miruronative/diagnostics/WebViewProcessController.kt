package com.miruronative.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.webkit.WebView
import java.util.IdentityHashMap

private const val CONSTRAINED_MEMORY_CLASS_MB = 256

/** Tracks real WebView consumers and reclaims Chromium after the last one leaves a constrained TV. */
object WebViewProcessController {
    private val activeViews = IdentityHashMap<WebView, String>()
    private var terminationRequests = 0L

    fun register(view: WebView, owner: String) {
        val active = synchronized(activeViews) {
            activeViews[view] = owner
            activeViews.size
        }
        DiagnosticsLog.event(
            "webview",
            "webview.registered",
            mapOf("owner" to owner, "activeViews" to active),
        )
    }

    /**
     * Unregisters [view] and, on a low-memory TV, asks Android to terminate its renderer only when
     * no resolver, embed player, or login WebView remains. This avoids killing visible web content
     * while reclaiming the renderer Android otherwise caches after WebView.destroy().
     */
    fun release(view: WebView, owner: String) {
        val context = view.context.applicationContext
        val remaining = synchronized(activeViews) {
            activeViews.remove(view)
            activeViews.size
        }
        val shouldTerminate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            remaining == 0 && isConstrainedTv(context)
        val terminated = if (shouldTerminate) {
            runCatching { view.webViewRenderProcess?.terminate() == true }.getOrDefault(false)
        } else {
            false
        }
        if (shouldTerminate) synchronized(activeViews) { terminationRequests++ }
        DiagnosticsLog.event(
            "webview",
            "webview.released",
            mapOf(
                "owner" to owner,
                "activeViews" to remaining,
                "terminationRequested" to shouldTerminate,
                "rendererAcceptedTermination" to terminated,
            ),
        )
    }

    fun snapshotAttributes(): Map<String, String> = synchronized(activeViews) {
        mapOf(
            "trackedWebViewCount" to activeViews.size.toString(),
            "trackedWebViewOwners" to activeViews.values.sorted().joinToString("/").ifBlank { "none" },
            "webViewRendererTerminationRequests" to terminationRequests.toString(),
        )
    }

    internal fun isConstrainedTv(context: Context): Boolean {
        val configuration = context.resources.configuration
        val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return shouldTerminateRenderer(
            isTv = isTv,
            isLowRamDevice = activityManager?.isLowRamDevice == true,
            memoryClassMb = activityManager?.memoryClass,
        )
    }
}

internal fun shouldTerminateRenderer(
    isTv: Boolean,
    isLowRamDevice: Boolean,
    memoryClassMb: Int?,
): Boolean = isTv && (
    isLowRamDevice || (memoryClassMb != null && memoryClassMb <= CONSTRAINED_MEMORY_CLASS_MB)
)
