package com.miruronative.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.WebView
import androidx.core.content.FileProvider
import com.miruronative.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small rolling diagnostic log for user-reported "black screen" and startup hangs where no crash
 * is thrown. Keep this local-only: users explicitly share a snapshot from Settings.
 */
object DiagnosticsLog {
    private const val LOG_DIR = "diagnostics"
    private const val LOG_FILE = "diagnostics.txt"
    private const val SHARE_FILE = "Anilili-diagnostics.txt"
    private const val MAX_BYTES = 900_000L
    private const val TRIM_TO_BYTES = 650_000

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var file: File? = null
    @Volatile private var lifecycleCallbacksInstalled = false
    @Volatile private var watchdogStarted = false
    @Volatile private var lastMainBlockLogAt = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(context.filesDir, LOG_DIR).resolve(LOG_FILE)
        event(
            "process start app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "${BuildConfig.BUILD_TYPE}; device=${Build.MANUFACTURER} ${Build.MODEL}; " +
                "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}",
        )
    }

    fun installLifecycleCallbacks(application: Application) {
        if (lifecycleCallbacksInstalled) return
        lifecycleCallbacksInstalled = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                event("lifecycle ${activity.javaClass.simpleName}.created saved=${savedInstanceState != null}")
            }

            override fun onActivityStarted(activity: Activity) {
                event("lifecycle ${activity.javaClass.simpleName}.started")
            }

            override fun onActivityResumed(activity: Activity) {
                event("lifecycle ${activity.javaClass.simpleName}.resumed")
            }

            override fun onActivityPaused(activity: Activity) {
                event("lifecycle ${activity.javaClass.simpleName}.paused")
            }

            override fun onActivityStopped(activity: Activity) {
                event("lifecycle ${activity.javaClass.simpleName}.stopped")
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                event("lifecycle ${activity.javaClass.simpleName}.saveInstanceState")
            }

            override fun onActivityDestroyed(activity: Activity) {
                event("lifecycle ${activity.javaClass.simpleName}.destroyed finishing=${activity.isFinishing}")
            }
        })
    }

    fun startMainThreadWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        val main = Handler(Looper.getMainLooper())
        Thread {
            while (true) {
                val postedAt = SystemClock.elapsedRealtime()
                val responded = AtomicBoolean(false)
                main.post {
                    responded.set(true)
                    val delayMs = SystemClock.elapsedRealtime() - postedAt
                    if (delayMs > 5_000) {
                        event("main thread recovered after ${delayMs}ms")
                    }
                }
                runCatching { Thread.sleep(6_000) }
                if (!responded.get()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastMainBlockLogAt > 15_000) {
                        lastMainBlockLogAt = now
                        append(
                            buildString {
                                append(timestamp())
                                append("  MAIN THREAD BLOCKED >6000ms\n")
                                append(stackTrace(Looper.getMainLooper().thread))
                                append('\n')
                            },
                        )
                    }
                }
                runCatching { Thread.sleep(2_000) }
            }
        }.apply {
            name = "anilili-diagnostics-watchdog"
            isDaemon = true
            start()
        }
        event("main thread watchdog started")
    }

    fun snapshot(context: Context, label: String) {
        val app = context.applicationContext
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { info ->
            runCatching { activityManager?.getMemoryInfo(info) }
        }
        val runtime = Runtime.getRuntime()
        val configuration = app.resources.configuration
        event(
            "$label snapshot " +
                "orientation=${orientation(configuration)} uiMode=${uiMode(configuration)} " +
                "fontScale=${configuration.fontScale} " +
                "screenDp=${configuration.screenWidthDp}x${configuration.screenHeightDp} " +
                "smallestDp=${configuration.smallestScreenWidthDp} " +
                "memAvailMb=${memoryInfo.availMem / 1024 / 1024} " +
                "memLow=${memoryInfo.lowMemory} " +
                "heapUsedMb=${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024} " +
                "heapMaxMb=${runtime.maxMemory() / 1024 / 1024}",
        )
    }

    fun webViewPackage(label: String) {
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        } else {
            null
        }
        event(
            "$label webviewPackage=" +
                if (pkg == null) "none" else "${pkg.packageName}/${pkg.versionName} (${pkg.longVersionCodeCompat()})",
        )
    }

    fun watchFirstDraw(view: View, label: String, timeoutMs: Long = 5_000) {
        val startedAt = SystemClock.elapsedRealtime()
        val drawn = AtomicBoolean(false)
        view.post {
            event(
                "$label decor posted attached=${view.isAttachedToWindow} " +
                    "shown=${view.isShown} size=${view.width}x${view.height} visibility=${view.visibility}",
            )
        }
        Choreographer.getInstance().postFrameCallback {
            event("$label first choreographer frame after ${SystemClock.elapsedRealtime() - startedAt}ms")
        }
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (drawn.compareAndSet(false, true)) {
                    if (view.viewTreeObserver.isAlive) {
                        view.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    event(
                        "$label first pre-draw after ${SystemClock.elapsedRealtime() - startedAt}ms " +
                            "attached=${view.isAttachedToWindow} shown=${view.isShown} size=${view.width}x${view.height}",
                    )
                }
                return true
            }
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        view.postDelayed({
            if (!drawn.get()) {
                event(
                    "$label NO pre-draw after ${timeoutMs}ms " +
                        "attached=${view.isAttachedToWindow} shown=${view.isShown} size=${view.width}x${view.height} " +
                        "visibility=${view.visibility} windowFocus=${view.hasWindowFocus()}",
                )
            }
        }, timeoutMs)
    }

    /**
     * One line that identifies the machine a report came from.
     *
     * Most reports arrive as "it closes on my Fire Stick", and the sticks are not one device: a
     * 1st-gen stick is a 1 GB, API 22, Fire OS 5 box with an ancient Amazon WebView, while a 4K Max
     * is 2 GB and API 32. Those fail in completely different ways, and without this the report
     * cannot be mapped to either. `isLowRamDevice` is the single most useful bit here — it is what
     * the platform itself uses to decide how aggressively to kill us.
     */
    fun deviceProfile(context: Context) {
        runCatching {
            val app = context.applicationContext
            val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val fireOs = listOf("ro.build.version.fireos", "ro.build.version.fireos.sdk")
                .firstNotNullOfOrNull { systemProperty(it)?.takeIf(String::isNotBlank) }
            val amazonDevice = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
            event(
                "device profile manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                    "device=${Build.DEVICE} product=${Build.PRODUCT} " +
                    "sdk=${Build.VERSION.SDK_INT} fireOs=${fireOs ?: if (amazonDevice) "amazon-unknown" else "no"} " +
                    "lowRam=${activityManager?.isLowRamDevice} " +
                    "memoryClassMb=${activityManager?.memoryClass} " +
                    "largeMemoryClassMb=${activityManager?.largeMemoryClass} " +
                    "abis=${Build.SUPPORTED_ABIS.joinToString("/")} " +
                    "network=${networkSummary(app)}",
            )
        }.onFailure { throwable("device profile unavailable", it) }
    }

    /** Wi-Fi versus Ethernet matters on TV: the sticks throttle Wi-Fi hard under memory pressure. */
    private fun networkSummary(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return "unknown"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "legacy"
        val network = manager.activeNetwork ?: return "offline"
        val caps = manager.getNetworkCapabilities(network) ?: return "unknown"
        val transport = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        "$transport,downKbps=${caps.linkDownstreamBandwidthKbps},upKbps=${caps.linkUpstreamBandwidthKbps}"
    }.getOrDefault("unknown")

    private fun systemProperty(key: String): String? = runCatching {
        @Suppress("PrivateApi")
        val systemProperties = Class.forName("android.os.SystemProperties")
        systemProperties.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()

    /**
     * Why the process died last time, straight from the system.
     *
     * A TV that "just exits" is the least diagnosable failure this app has: when the kill comes
     * from outside the process — the low-memory killer, an ANR, the system reclaiming the task —
     * nothing lands in the crash log, and the user can only report that it closed. The platform
     * has kept the real reason since API 30; reading it back on the next launch turns "it exits on
     * One Piece" into a specific cause without needing a reproduction.
     */
    fun logPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            event("previous exit reasons unavailable on API ${Build.VERSION.SDK_INT}")
            return
        }
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (exits.isEmpty()) {
                event("no previous process exits recorded")
                return
            }
            exits.forEach { info ->
                event(
                    "previous exit reason=${exitReasonName(info.reason)} status=${info.status} " +
                        "importance=${info.importance} pssKb=${info.pss} rssKb=${info.rss} " +
                        "at=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))} " +
                        "description=${info.description ?: "none"}",
                )
            }
        }.onFailure { throwable("previous exit reasons unavailable", it) }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCES"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        // Routine on every install; naming them keeps the genuinely interesting reasons legible.
        android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "UNKNOWN($reason)"
    }

    fun event(message: String) {
        append("${timestamp()} +${SystemClock.elapsedRealtime()}ms  $message\n")
    }

    fun throwable(message: String, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        append(
            buildString {
                append(timestamp())
                append(" +")
                append(SystemClock.elapsedRealtime())
                append("ms")
                append("  ")
                append(message)
                append(": ")
                append(throwable.javaClass.name)
                throwable.message?.let { append(": ").append(it) }
                append('\n')
                append(trace)
                append('\n')
            },
        )
    }

    fun share(context: Context): Result<Unit> = runCatching {
        event("diagnostics share requested")
        val snapshot = createShareSnapshot(context.applicationContext)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            snapshot,
        )
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Anilili diagnostics")
            .putExtra(Intent.EXTRA_TEXT, "Anilili diagnostics are attached.")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, "Anilili diagnostics", uri)
        val chooser = Intent.createChooser(send, "Share diagnostics")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onFailure { throwable("diagnostics share failed", it) }

    // Writes happen on a dedicated thread: appends used to run synchronously on whatever thread
    // logged (usually main), and on a memory-starved Fire TV a single flash write inside
    // onTrimMemory blocked the main thread for 17+ seconds — during playback, at the worst
    // possible moment. The single thread preserves log ordering.
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "anilili-diagnostics-log").apply { isDaemon = true }
    }

    private fun append(text: String) {
        writeExecutor.execute {
            val target = file ?: appContext?.let {
                File(it.filesDir, LOG_DIR).resolve(LOG_FILE).also { resolved -> file = resolved }
            } ?: return@execute
            runCatching {
                synchronized(lock) {
                    target.parentFile?.mkdirs()
                    trimIfNeeded(target)
                    target.appendText(text)
                }
            }
        }
    }

    fun threadStack(message: String, thread: Thread) {
        append(
            buildString {
                append(timestamp())
                append(" +")
                append(SystemClock.elapsedRealtime())
                append("ms  ")
                append(message)
                append('\n')
                append(stackTrace(thread))
                append('\n')
            },
        )
    }

    /**
     * TV path C: copies the snapshot into the device's public Downloads so it can also be pulled
     * with a file manager or over USB. Timestamped so repeated shares don't shadow each other.
     */
    fun saveToDownloads(context: Context, snapshot: File): Result<String> = runCatching {
        val name = "Anilili-diagnostics-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create a Downloads entry")
            resolver.openOutputStream(uri)?.use { out ->
                snapshot.inputStream().use { it.copyTo(out) }
            } ?: error("Couldn't write to Downloads")
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS,
            )
            dir.mkdirs()
            snapshot.copyTo(File(dir, name), overwrite = true)
        }
        event("diagnostics saved to Downloads/$name")
        "Downloads/$name"
    }.onFailure { throwable("diagnostics downloads save failed", it) }

    fun createShareSnapshot(context: Context): File {
        val dir = File(context.cacheDir, LOG_DIR).apply { mkdirs() }
        val snapshot = File(dir, SHARE_FILE)
        synchronized(lock) {
            snapshot.writeText(
                buildString {
                    appendLine("Anilili diagnostics")
                    appendLine("generated: ${timestamp()}")
                    appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
                    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine()
                    appendLine("== rolling log ==")
                    append(activeFile()?.takeIf { it.exists() }?.readText().orEmpty())
                    appendLine()
                    appendLine("== last crash dialog report ==")
                    append(CrashReporter.pendingReport().orEmpty())
                },
            )
        }
        return snapshot
    }

    private fun trimIfNeeded(target: File) {
        if (!target.exists() || target.length() <= MAX_BYTES) return
        val bytes = target.readBytes()
        val start = (bytes.size - TRIM_TO_BYTES).coerceAtLeast(0)
        target.writeBytes(bytes.copyOfRange(start, bytes.size))
        target.appendText("\n${timestamp()}  log trimmed to last ${TRIM_TO_BYTES / 1000}KB\n")
    }

    private fun activeFile(): File? = file ?: appContext?.let {
        File(it.filesDir, LOG_DIR).resolve(LOG_FILE).also { resolved -> file = resolved }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun stackTrace(thread: Thread): String =
        thread.stackTrace.joinToString("\n") { "    at $it" }

    private fun orientation(configuration: Configuration): String = when (configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        else -> "undefined"
    }

    private fun uiMode(configuration: Configuration): String = when (
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    ) {
        Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
        Configuration.UI_MODE_TYPE_CAR -> "car"
        Configuration.UI_MODE_TYPE_WATCH -> "watch"
        Configuration.UI_MODE_TYPE_NORMAL -> "normal"
        else -> "unknown"
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
