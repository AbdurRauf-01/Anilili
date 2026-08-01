package com.anilili.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anilili.MainActivity
import com.anilili.R
import com.anilili.data.AppGraph
import com.anilili.data.auth.AuthManager
import com.anilili.data.library.LibraryStore
import com.anilili.data.model.Media
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.nav.Routes
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class ReleaseAlarm(
    val id: String,
    val mediaId: Int,
    val episode: Int,
    val title: String,
    val airingAt: Long,
)

/** Schedules one local notification at the release time of each tracked anime's next episode. */
object AutomaticReleaseManager {
    const val CHANNEL_ID = "automatic_episode_releases"
    private const val PREFS = "anilili_release_alerts"
    private const val KEY_ALARMS = "alarms"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var appContext: Context

    // Read/written from main (init, cancelAll, receivers) and WorkManager threads (sync).
    private val lock = Any()
    private var alarms: List<ReleaseAlarm> = emptyList()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New episode releases", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Alerts when a new episode airs for anime saved here or on AniList"
                },
            )
        }
        val oldestUseful = System.currentTimeMillis() / 1000L - 24 * 60 * 60
        synchronized(lock) {
            alarms = read().filter { it.airingAt >= oldestUseful }
            alarms.forEach(::schedule)
            persist(alarms)
        }
    }

    fun sync(media: Collection<Media>) {
        if (!SettingsStore.releaseNotifications.value) {
            cancelAll()
            return
        }
        val now = System.currentTimeMillis() / 1000L
        val desired = media.mapNotNull { item ->
            val next = item.nextAiringEpisode ?: return@mapNotNull null
            val episode = next.episode ?: return@mapNotNull null
            val airingAt = next.airingAt ?: return@mapNotNull null
            if (airingAt <= now) return@mapNotNull null
            ReleaseAlarm(
                id = "${item.id}:$episode:$airingAt",
                mediaId = item.id,
                episode = episode,
                title = item.title.preferred,
                airingAt = airingAt,
            )
        }.distinctBy { it.id }

        val desiredIds = desired.mapTo(hashSetOf()) { it.id }
        synchronized(lock) {
            alarms.filterNot { it.id in desiredIds }.forEach(::cancel)
            desired.forEach(::schedule)
            alarms = desired
            persist(alarms)
        }
    }

    fun cancelAll() {
        if (!::appContext.isInitialized) return
        synchronized(lock) {
            alarms.forEach(::cancel)
            alarms = emptyList()
            persist(alarms)
        }
    }

    fun markDelivered(id: String?) {
        if (id == null || !::appContext.isInitialized) return
        synchronized(lock) {
            alarms = alarms.filterNot { it.id == id }
            persist(alarms)
        }
    }

    private fun schedule(alarm: ReleaseAlarm) {
        val triggerAt = (alarm.airingAt * 1000L).coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val pending = pendingIntent(alarm, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val manager = alarmManager()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun cancel(alarm: ReleaseAlarm) {
        pendingIntent(alarm, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
            alarmManager().cancel(pending)
            pending.cancel()
        }
    }

    private fun pendingIntent(alarm: ReleaseAlarm, mode: Int): PendingIntent? = PendingIntent.getBroadcast(
        appContext,
        alarm.id.hashCode(),
        Intent(appContext, AutomaticReleaseReceiver::class.java)
            .putExtra("releaseId", alarm.id)
            .putExtra("mediaId", alarm.mediaId)
            .putExtra("episode", alarm.episode)
            .putExtra("title", alarm.title),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarmManager(): AlarmManager =
        appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun notificationManager(): NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun persist(value: List<ReleaseAlarm>) {
        val raw = json.encodeToString(ListSerializer(ReleaseAlarm.serializer()), value)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ALARMS, raw).apply()
    }

    private fun read(): List<ReleaseAlarm> {
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ALARMS, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ReleaseAlarm.serializer()), raw)
        }.getOrDefault(emptyList())
    }
}

class AutomaticReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutomaticReleaseManager.markDelivered(intent.getStringExtra("releaseId"))
        ReleaseSyncScheduler.runNow(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val mediaId = intent.getIntExtra("mediaId", 0)
        val episode = intent.getIntExtra("episode", 0)
        val title = intent.getStringExtra("title") ?: "A saved anime"
        val open = PendingIntent.getActivity(
            context,
            mediaId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(Routes.EXTRA_ROUTE, Routes.detail(mediaId))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, AutomaticReleaseManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New episode released")
            .setContentText("$title • Episode $episode")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Episode $episode of $title has just aired."))
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, "View anime", open)
            .setAutoCancel(true)
            .setGroup(AutomaticReleaseManager.CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(17 * mediaId + episode, notification)
        // Group summary so multiple release alerts collapse into one expandable entry.
        manager.notify(
            -1002,
            NotificationCompat.Builder(context, AutomaticReleaseManager.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("New episode releases")
                .setContentIntent(open)
                .setGroup(AutomaticReleaseManager.CHANNEL_ID)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .build(),
        )
    }
}

object ReleaseSyncScheduler {
    private const val PERIODIC_NAME = "automatic-release-sync"
    private const val IMMEDIATE_NAME = "automatic-release-sync-now"
    private const val SYNC_STATE_PREFERENCES = "automatic-release-sync-state"
    private const val LAST_SUCCESS_UTC_MS = "last-success-utc-ms"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReleaseSyncWorker>(3, TimeUnit.HOURS)
            .addTag("release-sync")
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun runNow(context: Context) {
        enqueueNow(context, ExistingWorkPolicy.REPLACE, "explicit")
    }

    /** Launch-only refresh: explicit user/auth/list actions continue to use [runNow]. */
    fun runAfterStartupIfStale(context: Context, nowUtcMs: Long = System.currentTimeMillis()) {
        if (!SettingsStore.releaseNotifications.value) return
        val app = context.applicationContext
        val lastSuccess = app.getSharedPreferences(SYNC_STATE_PREFERENCES, Context.MODE_PRIVATE)
            .getLong(LAST_SUCCESS_UTC_MS, 0L)
        if (!shouldRunStartupReleaseSync(nowUtcMs, lastSuccess)) {
            DiagnosticsLog.event(
                "release_sync",
                "startup.skipped_fresh",
                mapOf("ageMs" to (nowUtcMs - lastSuccess).coerceAtLeast(0)),
            )
            return
        }
        enqueueNow(app, ExistingWorkPolicy.KEEP, "startup_stale")
    }

    internal fun markSuccessful(context: Context, nowUtcMs: Long = System.currentTimeMillis()) {
        context.applicationContext.getSharedPreferences(SYNC_STATE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_SUCCESS_UTC_MS, nowUtcMs)
            .apply()
    }

    private fun enqueueNow(context: Context, policy: ExistingWorkPolicy, reason: String) {
        val request = OneTimeWorkRequestBuilder<ReleaseSyncWorker>()
            .addTag("release-sync")
            .setConstraints(network)
            .build()
        DiagnosticsLog.event("release_sync", "work.enqueued", mapOf("reason" to reason, "policy" to policy.name))
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_NAME,
            policy,
            request,
        )
    }
}

internal fun shouldRunStartupReleaseSync(nowUtcMs: Long, lastSuccessUtcMs: Long): Boolean =
    lastSuccessUtcMs <= 0L || nowUtcMs < lastSuccessUtcMs || nowUtcMs - lastSuccessUtcMs >= 60 * 60 * 1_000L

internal fun releaseSyncParallelism(isTv: Boolean): Int = if (isTv) 2 else 4

class ReleaseSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SettingsStore.releaseNotifications.value) {
            AutomaticReleaseManager.cancelAll()
            return Result.success()
        }
        return try {
            val repo = AppGraph.repository
            val parallelism = releaseSyncParallelism(AppGraph.isTv)
            val semaphore = Semaphore(parallelism)
            val localSaved = coroutineScope {
                LibraryStore.watchlist.value.map { saved ->
                    async {
                        semaphore.withPermit {
                            runCatching { repo.animeInfo(saved.anilistId) }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (AuthManager.isLoggedIn) {
                runCatching {
                    val (items, unread) = repo.notifications(markAllRead = false)
                    NotificationCenter.setUnread(unread)
                    AniListNotificationPushManager.notifyUnread(applicationContext, items)
                }

                val aniListTracked = anilistTrackedMedia(repo)
                val aniListIds = aniListTracked.mapTo(hashSetOf()) { it.id }
                // AniList notifications are now the canonical push source for logged-in users.
                // Keep local alarms only for device-only saves that AniList cannot notify about.
                AutomaticReleaseManager.sync(localSaved.filterNot { it.id in aniListIds })
            } else {
                AutomaticReleaseManager.sync(localSaved.distinctBy { it.id })
            }
            ReleaseSyncScheduler.markSuccessful(applicationContext)
            DiagnosticsLog.event(
                "release_sync",
                "work.succeeded",
                mapOf("savedItems" to localSaved.size, "parallelism" to parallelism),
            )
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun anilistTrackedMedia(repo: com.anilili.data.MiruroRepository): List<Media> {
        val viewerId = AuthManager.viewerId() ?: runCatching { repo.viewer() }.getOrNull()?.id ?: return emptyList()
        // AniList's airing pushes cover shows the user is actively watching/rewatching. Planning,
        // paused, and favourite-only titles still need the app's local release alarm.
        val activeStatuses = setOf("CURRENT", "REPEATING")
        val listMedia = runCatching { repo.userAnimeList(viewerId) }.getOrNull()
            ?.lists.orEmpty()
            .flatMap { it.entries }
            .distinctBy { it.id }
            .filter { it.status in activeStatuses }
            .mapNotNull { it.media }
        return listMedia.distinctBy { it.id }
    }
}
