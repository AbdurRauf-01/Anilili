package com.miruronative.data.remote

/** Why a requested MAL progress update deliberately made no network mutation. */
enum class MalProgressSyncSkipReason {
    INVALID_PROGRESS,
    MISSING_MAL_ID,
    ALREADY_AT_OR_AHEAD,
    REMOTE_COMPLETED,
    NO_CHANGE,
}

/**
 * Explicit result of synchronizing one watched episode to MyAnimeList.
 *
 * A skipped result is intentionally distinct from success. This keeps missing mappings and
 * non-regression decisions visible to diagnostics instead of letting a nullable return value make
 * every no-op look like a successful update.
 */
sealed interface MalProgressSyncResult {
    val anilistId: Int
    val targetProgress: Int

    data class Updated(
        override val anilistId: Int,
        override val targetProgress: Int,
        val malId: Int,
        val previousProgress: Int?,
        val confirmedProgress: Int,
        /** AniList vocabulary, shared with the rest of the app. */
        val status: String?,
        /** MAL vocabulary returned by the server. */
        val confirmedStatus: String?,
    ) : MalProgressSyncResult

    data class Skipped(
        override val anilistId: Int,
        override val targetProgress: Int,
        val reason: MalProgressSyncSkipReason,
        val malId: Int? = null,
        val remoteProgress: Int? = null,
    ) : MalProgressSyncResult
}

internal fun malProgressSkipReason(
    currentStatus: String?,
    currentProgress: Int?,
    targetProgress: Int,
): MalProgressSyncSkipReason = when {
    targetProgress < 1 -> MalProgressSyncSkipReason.INVALID_PROGRESS
    currentStatus == "COMPLETED" -> MalProgressSyncSkipReason.REMOTE_COMPLETED
    currentProgress != null && targetProgress <= currentProgress ->
        MalProgressSyncSkipReason.ALREADY_AT_OR_AHEAD
    else -> MalProgressSyncSkipReason.NO_CHANGE
}
