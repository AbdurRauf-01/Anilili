package com.anilili.data.remote

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Raw pipe response as returned by the in-page fetch, before deobfuscation. */
data class RawPipeResponse(val ok: Boolean, val status: Int, val obf: String?, val body: String?, val error: String?)

/**
 * Tries the most recently healthy origin first, then wraps through every remaining mirror once.
 * An unknown persisted value falls back to the configured order.
 */
internal fun orderedPipeOrigins(origins: List<String>, lastWorkingOrigin: String): List<String> {
    require(origins.isNotEmpty()) { "origins must not be empty" }
    val startIndex = origins.indexOf(lastWorkingOrigin).takeIf { it >= 0 } ?: 0
    return List(origins.size) { offset -> origins[(startIndex + offset) % origins.size] }
}

/** Full cold-start budget: every mirror gets its watchdog window, then the winning page settles. */
internal fun pipePageReadyTimeoutMs(
    originCount: Int,
    mirrorTimeoutMs: Long,
    pageSettleMs: Long,
    schedulerGraceMs: Long,
    rememberedMirrorExtraMs: Long = 0L,
): Long {
    require(originCount > 0) { "originCount must be positive" }
    require(
        mirrorTimeoutMs >= 0 && pageSettleMs >= 0 && schedulerGraceMs >= 0 &&
            rememberedMirrorExtraMs >= 0,
    ) {
        "pipe timeout components must be non-negative"
    }
    return originCount.toLong() * mirrorTimeoutMs + rememberedMirrorExtraMs +
        pageSettleMs + schedulerGraceMs
}

/** Outer provider budget must not cancel the bridge while its bounded cold start is still active. */
internal fun pipeSourceAttemptTimeoutMs(
    pageReadyTimeoutMs: Long,
    responseTimeoutMs: Long,
    completionGraceMs: Long,
): Long = pageReadyTimeoutMs + responseTimeoutMs + completionGraceMs

/**
 * A mirror that already worked on this network gets a little longer to answer before failover.
 * This preserves the useful part of the old always-warm resolver without making every cold,
 * unknown mirror extend the full startup walk.
 */
internal fun pipeMirrorWatchdogTimeoutMs(
    origin: String,
    lastWorkingOrigin: String,
    standardTimeoutMs: Long,
    rememberedTimeoutMs: Long,
): Long = if (lastWorkingOrigin.isNotBlank() && origin == lastWorkingOrigin) {
    rememberedTimeoutMs
} else {
    standardTimeoutMs
}

internal const val PIPE_SOURCE_RESPONSE_TIMEOUT_MS = 8_000L

/**
 * Routes pipe requests through a real (hidden) WebView. Cloudflare fingerprints the HTTP client,
 * so a plain OkHttp call gets a 403 WAF block; a same-origin `fetch()` from inside a loaded
 * miruro.to page rides the browser's TLS fingerprint + `cf_clearance` cookie and is allowed.
 *
 * The WebView is created and attached to the window by [com.anilili.ui.PipeWebView]; this
 * object owns the JS bridge and the request/response plumbing.
 */
@SuppressLint("StaticFieldLeak")
object PipeBridge {
    private val demand = ResolverDemand("pipe")
    val resolverRequired = demand.required

    /**
     * Mirror domains, tried in order. ISPs block these piecemeal (user logs show .to timing out
     * on one network while others resolve), so a main-frame load failure rolls to the next
     * mirror instead of taking every pipe request down with it.
     */
    private val ORIGINS = listOf(
        "https://www.miruro.to",
        "https://www.miruro.tv",
        "https://www.miruro.bz",
        "https://www.miruro.ru",
    )

    private val main = Handler(Looper.getMainLooper())

    /**
     * The hidden tab hosts the full miruro SPA, whose scripts/animations otherwise run for the
     * whole session — on a Fire TV stick its compositor can exhaust Chromium's tile budget and
     * leave the visible episode black while audio continues. Idle (View.onPause) the WebView
     * shortly after the last pipe request completes; each fetch resumes it first. The CF
     * session/cookies survive onPause just fine.
     */
    private const val IDLE_AFTER_MS = 2_000L
    private const val PAGE_SETTLE_MS = 2_000L
    private const val READY_SCHEDULER_GRACE_MS = 1_000L
    private const val ATTEMPT_COMPLETION_GRACE_MS = 1_000L
    private const val FAILURE_COOLDOWN_MS = 15_000L
    private val idleRunnable = Runnable {
        webView?.onPause()
        DiagnosticsLog.event("PipeBridge webview idled")
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idleRunnable)
        main.postDelayed(idleRunnable, IDLE_AFTER_MS)
    }

    private fun scheduleIdleIfUnused() {
        main.post {
            if (pending.isEmpty()) scheduleIdle()
        }
    }

    /**
     * How long a mirror gets to finish loading before it is treated as unreachable.
     *
     * `onReceivedError` only fires once the network stack gives up, which on a blocking ISP took
     * 32 seconds per mirror in a user's log — 64 seconds of dead time before the third mirror was
     * even tried, and by then the catalog wait had long since expired and the app reported "no
     * sources" for a title with plenty. Rolling over on our own clock keeps that bounded.
     */
    private const val MIRROR_TIMEOUT_MS = 7_000L
    private const val REMEMBERED_MIRROR_TIMEOUT_MS = 12_000L
    private val PAGE_READY_TIMEOUT_MS = pipePageReadyTimeoutMs(
        originCount = ORIGINS.size,
        mirrorTimeoutMs = MIRROR_TIMEOUT_MS,
        pageSettleMs = PAGE_SETTLE_MS,
        schedulerGraceMs = READY_SCHEDULER_GRACE_MS,
        rememberedMirrorExtraMs = REMEMBERED_MIRROR_TIMEOUT_MS - MIRROR_TIMEOUT_MS,
    )

    /** Used by the repository's outer timeout so it cannot pre-empt an on-demand cold start. */
    internal fun sourceAttemptTimeoutMs(): Long = pipeSourceAttemptTimeoutMs(
        pageReadyTimeoutMs = PAGE_READY_TIMEOUT_MS,
        responseTimeoutMs = PIPE_SOURCE_RESPONSE_TIMEOUT_MS,
        completionGraceMs = ATTEMPT_COMPLETION_GRACE_MS,
    )

    @Volatile private var webView: WebView? = null
    @Volatile private var ready = CompletableDeferred<Boolean>()
    @Volatile private var originAttempts = ORIGINS
    @Volatile private var originIndex = 0
    @Volatile private var rememberedOrigin = ""
    @Volatile private var pageReadyState: Boolean? = null
    @Volatile private var unavailableUntilElapsedMs = 0L
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val counter = AtomicLong(0)

    private val activeOrigin: String get() = originAttempts[originIndex]

    private val mirrorWatchdog = Runnable {
        DiagnosticsLog.event(
            "PipeBridge mirror timed out after ${activeMirrorTimeoutMs()}ms origin=$activeOrigin",
        )
        advanceMirror()
    }

    private fun activeMirrorTimeoutMs(): Long = pipeMirrorWatchdogTimeoutMs(
        origin = activeOrigin,
        lastWorkingOrigin = rememberedOrigin,
        standardTimeoutMs = MIRROR_TIMEOUT_MS,
        rememberedTimeoutMs = REMEMBERED_MIRROR_TIMEOUT_MS,
    )

    private fun armMirrorWatchdog() {
        main.removeCallbacks(mirrorWatchdog)
        main.postDelayed(mirrorWatchdog, activeMirrorTimeoutMs())
    }

    /** Rolls to the next mirror, or gives up once they are exhausted. Main thread only. */
    private fun advanceMirror() {
        main.removeCallbacks(mirrorWatchdog)
        if (ready.isCompleted) return
        if (originIndex < originAttempts.lastIndex) {
            originIndex++
            DiagnosticsLog.event("PipeBridge trying mirror $activeOrigin")
            armMirrorWatchdog()
            webView?.loadUrl("$activeOrigin/")
        } else {
            DiagnosticsLog.event("PipeBridge all mirrors failed")
            unavailableUntilElapsedMs = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
            pageReadyState = false
            ready.complete(false)
        }
    }

    /**
     * Keeps the hidden browser mounted across a related sequence such as episode-catalog lookup
     * followed by the first source lookup. The caller must close the returned session. Individual
     * fetches still hold their own leases, so closing a session cannot interrupt an active request.
     */
    internal fun acquireWarmSession(reason: String): AutoCloseable {
        DiagnosticsLog.event("PipeBridge warm session acquired reason=$reason")
        val lease = demand.acquire()
        return AutoCloseable {
            DiagnosticsLog.event("PipeBridge warm session released reason=$reason")
            lease.close()
        }
    }

    /**
     * Allows one user/WatchViewModel-directed retry to bypass the passive failure cooldown. If a
     * failed page is still attached, restart its mirror walk; otherwise the next demand mounts a
     * fresh WebView and [attach] starts it normally.
     */
    internal suspend fun prepareForRetry() {
        unavailableUntilElapsedMs = 0L
        withContext(Dispatchers.Main.immediate) {
            val wv = webView ?: return@withContext
            if (pageReadyState != false) return@withContext
            main.removeCallbacks(idleRunnable)
            main.removeCallbacks(mirrorWatchdog)
            ready = CompletableDeferred()
            pageReadyState = null
            rememberedOrigin = SettingsStore.lastWorkingPipeOrigin.value
            originAttempts = orderedPipeOrigins(ORIGINS, rememberedOrigin)
            originIndex = 0
            DiagnosticsLog.event("PipeBridge explicit retry origin=$activeOrigin")
            wv.onResume()
            armMirrorWatchdog()
            wv.loadUrl("$activeOrigin/")
        }
    }

    /** Called from the hosting Composable on the main thread with a freshly created WebView. */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(wv: WebView) {
        DiagnosticsLog.event("PipeBridge.attach")
        webView = wv
        com.anilili.diagnostics.ResolverDiagnostics.viewChanged("pipe", true)
        if (ready.isCompleted) ready = CompletableDeferred()
        pageReadyState = null

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        wv.addJavascriptInterface(Bridge, "AndroidPipe")
        wv.webViewClient = object : WebViewClient() {
            // Pin the tab to Miruro's mirrors — block ad popunders / intent: redirects that
            // would navigate away and kill the same-origin pipe session.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                val host = url.host.orEmpty().lowercase()
                val allowed = url.scheme == "https" && ORIGINS.any { origin ->
                    val originHost = origin.removePrefix("https://www.")
                    host == originHost || host.endsWith(".$originHost")
                }
                if (!allowed) {
                    DiagnosticsLog.event("PipeBridge blocked nav: $url")
                    Log.d(TAG, "blocked nav: $url")
                }
                return !allowed
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                DiagnosticsLog.event("PipeBridge page started: ${url ?: "unknown"}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                DiagnosticsLog.event("PipeBridge page finished: ${url ?: "unknown"} title=${view?.title ?: "none"}")
                Log.d(TAG, "onPageFinished: $url  title=${view?.title}")
                // Give Cloudflare a moment to settle, then allow fetches.
                if (url != null && url.startsWith(activeOrigin)) {
                    main.removeCallbacks(mirrorWatchdog)
                    // Remember what worked. On a network that blocks the first mirrors, starting
                    // from scratch every launch means paying the whole failover walk every time.
                    SettingsStore.setLastWorkingPipeOrigin(activeOrigin)
                    main.postDelayed(
                        {
                            if (!ready.isCompleted) {
                                unavailableUntilElapsedMs = 0L
                                pageReadyState = true
                                ready.complete(true)
                            }
                            scheduleIdle()
                        },
                        PAGE_SETTLE_MS,
                    )
                }
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.M)
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                DiagnosticsLog.event(
                    "PipeBridge main-frame error code=${error?.errorCode} " +
                        "description=${error?.description} origin=$activeOrigin",
                )
                // This mirror is unreachable (ISP block, DNS, site down): roll to the next one.
                // Only once all mirrors fail do waiters unblock into the cache/error path.
                main.post { advanceMirror() }
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event(
                    "PipeBridge render process gone didCrash=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()}",
                )
                com.anilili.diagnostics.ResolverDiagnostics.rendererGone("pipe", detail?.didCrash())
                view?.let(::detach)
                return true
            }
        }
        // Start from whichever mirror last answered on this network, then wrap around the list so
        // a stale last-working value cannot make earlier mirrors unreachable.
        rememberedOrigin = SettingsStore.lastWorkingPipeOrigin.value
        originAttempts = orderedPipeOrigins(ORIGINS, rememberedOrigin)
        originIndex = 0
        DiagnosticsLog.event("PipeBridge load origin=$activeOrigin")
        armMirrorWatchdog()
        wv.loadUrl("$activeOrigin/")
    }

    /** Releases the attached browser and fails requests that can no longer complete. */
    fun detach(wv: WebView) {
        if (webView !== wv) return
        DiagnosticsLog.event("PipeBridge.detach")
        main.removeCallbacks(idleRunnable)
        main.removeCallbacks(mirrorWatchdog)
        webView = null
        com.anilili.diagnostics.ResolverDiagnostics.viewChanged("pipe", false)
        ready = CompletableDeferred()
        pageReadyState = null
        pending.entries.toList().forEach { (id, request) ->
            if (pending.remove(id, request)) {
                request.complete("""{"ok":false,"status":-1,"error":"webview released"}""")
            }
        }
        wv.removeJavascriptInterface("AndroidPipe")
    }

    object Bridge {
        @JavascriptInterface
        fun onResult(id: String, json: String) {
            pending.remove(id)?.complete(json)
        }
    }

    /** Runs `fetch('/api/secure/pipe?e=…')` inside the page and returns the raw JSON bridge payload. */
    /**
     * Whether a request would find the bridge already loaded and past Cloudflare.
     *
     * A cold bridge has to build a WebView, load a mirror and clear the challenge before it can
     * run a single fetch; a warm one runs the fetch immediately. Recording which of the two a
     * resolve started from is what separates "our resolver was asleep" from "this server is
     * genuinely slow upstream" — the two look identical in a wall-clock duration.
     */
    fun isWarm(): Boolean = webView != null && pageReadyState == true

    suspend fun fetch(e: String, timeoutMs: Long = 30_000): String {
        val fetchStartedMs = SystemClock.elapsedRealtime()
        val startedWarm = isWarm()
        var cooldownRemainingMs =
            (unavailableUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (cooldownRemainingMs > 0L || pageReadyState == false) {
            prepareForRetry()
            cooldownRemainingMs =
                (unavailableUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (cooldownRemainingMs > 0L && pageReadyState == false) {
                DiagnosticsLog.event(
                    "PipeBridge cold-start cooldown e.len=${e.length} remainingMs=$cooldownRemainingMs",
                )
                return """{"ok":false,"status":-1,"error":"resolver temporarily unavailable"}"""
            }
        }
        val lease = demand.acquire()
        var requestId: String? = null
        try {
            var pageReady = withTimeoutOrNull(PAGE_READY_TIMEOUT_MS) { ready.await() } == true
            if (!pageReady && pageReadyState == false) {
                prepareForRetry()
                pageReady = withTimeoutOrNull(PAGE_READY_TIMEOUT_MS) { ready.await() } == true
            }
            // How much of this request was spent getting the browser ready, as opposed to talking
            // to the provider. The two are indistinguishable in the resolve's total duration, and
            // the answer decides whether keeping the resolver resident is worth its memory.
            DiagnosticsLog.event(
                category = "pipe",
                name = "bridge.ready",
                attributes = mapOf(
                    "warm" to startedWarm,
                    "waitMs" to (SystemClock.elapsedRealtime() - fetchStartedMs),
                    "ready" to pageReady,
                ),
            )
            if (!pageReady) {
                unavailableUntilElapsedMs = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
                DiagnosticsLog.event(
                    "PipeBridge page unavailable e.len=${e.length} " +
                        "readyBudgetMs=$PAGE_READY_TIMEOUT_MS mirrors=${originAttempts.size}",
                )
                return """{"ok":false,"status":-1,"error":"webview page unavailable"}"""
            }

            val id = counter.incrementAndGet().toString()
            requestId = id
            val deferred = CompletableDeferred<String>()
            pending[id] = deferred

            val js = """
                (function(){
                  try {
                    fetch('/api/secure/pipe?e=$e', { headers: { 'Accept': '*/*' }, credentials: 'include' })
                      .then(function(r){
                        return r.text().then(function(b){
                          AndroidPipe.onResult('$id', JSON.stringify({
                            ok: r.ok, status: r.status,
                            obf: (r.headers.get('x-obfuscated') || ''), body: b
                          }));
                        });
                      })
                      .catch(function(err){
                        AndroidPipe.onResult('$id', JSON.stringify({ ok:false, status:-1, error:String(err) }));
                      });
                  } catch (err) {
                    AndroidPipe.onResult('$id', JSON.stringify({ ok:false, status:-1, error:String(err) }));
                  }
                })();
            """.trimIndent()

            main.post {
                val wv = webView
                if (wv == null) {
                    pending.remove(id)?.complete("""{"ok":false,"status":-1,"error":"webview not ready"}""")
                } else {
                    main.removeCallbacks(idleRunnable)
                    wv.onResume()
                    wv.evaluateJavascript(js, null)
                }
            }

            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: run {
                    pending.remove(id)
                    DiagnosticsLog.event("PipeBridge fetch timeout e.len=${e.length}")
                    """{"ok":false,"status":-1,"error":"timeout"}"""
                }
            scheduleIdleIfUnused()
            Log.d(TAG, "fetch(e.len=${e.length}) -> ${result.take(180)}")
            return result
        } finally {
            requestId?.let(pending::remove)
            lease.close()
        }
    }

    private const val TAG = "PipeBridge"
}
