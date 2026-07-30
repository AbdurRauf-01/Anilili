package com.miruronative.data.remote

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.miruronative.data.AppGraph
import com.miruronative.data.auth.AccountService
import com.miruronative.data.library.LibraryStore
import com.miruronative.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Persisted MAL progress delivery.
 *
 * Playback only has to enqueue the target episode. WorkManager retains it across navigation,
 * process death, and temporary loss of connectivity. Updates for one title form a serial chain;
 * MAL's non-regression policy then makes out-of-order or repeated playback harmless.
 */
class MalProgressSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val anilistId = inputData.getInt(KEY_ANILIST_ID, 0)
        val progress = inputData.getInt(KEY_PROGRESS, 0)
        val storedTotal = inputData.getInt(KEY_TOTAL_EPISODES, UNKNOWN_TOTAL)
        val totalEpisodes = storedTotal.takeIf { it > 0 }
        if (anilistId <= 0 || progress <= 0) {
            DiagnosticsLog.event("MAL progress work rejected invalid id=$anilistId progress=$progress")
            return Result.failure()
        }
        if (AccountService.active != AccountService.MAL) {
            DiagnosticsLog.event(
                "MAL progress work abandoned id=$anilistId episode=$progress: MAL is not active",
            )
            return Result.success()
        }

        return try {
            when (val sync = AppGraph.repository.saveMalProgress(anilistId, progress, totalEpisodes)) {
                is MalProgressSyncResult.Updated -> {
                    sync.status?.let { LibraryStore.updateRemoteStatus(anilistId, it) }
                    DiagnosticsLog.event(
                        "MAL progress sync confirmed id=$anilistId malId=${sync.malId} " +
                            "target=$progress previous=${sync.previousProgress ?: "none"} " +
                            "confirmed=${sync.confirmedProgress} status=${sync.confirmedStatus ?: "unchanged"}",
                    )
                }
                is MalProgressSyncResult.Skipped -> DiagnosticsLog.event(
                    "MAL progress sync skipped id=$anilistId malId=${sync.malId ?: "none"} " +
                        "target=$progress remote=${sync.remoteProgress ?: "none"} " +
                        "reason=${sync.reason.name.lowercase()}",
                )
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DiagnosticsLog.throwable(
                "MAL progress sync attempt ${runAttemptCount + 1}/$MAX_ATTEMPTS failed " +
                    "id=$anilistId episode=$progress",
                error,
            )
            if (AccountService.active == AccountService.MAL && runAttemptCount + 1 < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                // Treat the exhausted item as consumed so a later episode appended to the unique
                // chain is still allowed to run. Its larger progress can repair the remote state.
                DiagnosticsLog.event("MAL progress sync exhausted id=$anilistId episode=$progress")
                Result.success()
            }
        }
    }

    companion object {
        private const val KEY_ANILIST_ID = "anilist_id"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_TOTAL_EPISODES = "total_episodes"
        private const val UNKNOWN_TOTAL = -1
        private const val MAX_ATTEMPTS = 6

        private fun workName(anilistId: Int) = "mal-progress:$anilistId"

        fun enqueue(context: Context, anilistId: Int, progress: Int, totalEpisodes: Int?) {
            val request = OneTimeWorkRequestBuilder<MalProgressSyncWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ANILIST_ID to anilistId,
                        KEY_PROGRESS to progress,
                        KEY_TOTAL_EPISODES to (totalEpisodes ?: UNKNOWN_TOTAL),
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(anilistId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
            DiagnosticsLog.event(
                "MAL progress queued id=$anilistId episode=$progress total=${totalEpisodes ?: "unknown"}",
            )
        }

        private const val TAG = "mal-progress"
    }
}
