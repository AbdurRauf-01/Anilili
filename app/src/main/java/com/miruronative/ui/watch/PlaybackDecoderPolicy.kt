package com.miruronative.ui.watch

import androidx.media3.common.PlaybackException

/**
 * Lowering video resolution can recover a video decoder failure, but it cannot repair a malformed
 * audio track. Media3 exposes renderer details only on ExoPlaybackException, so the message remains
 * a defensive fallback for platform/vendor variants that omit the structured fields.
 */
internal fun shouldRetryDecoderAtLowerVideoResolution(
    errorCode: Int,
    rendererName: String?,
    sampleMimeType: String?,
    errorMessage: String?,
): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false
    val audioFailure = rendererName?.contains("audio", ignoreCase = true) == true ||
        sampleMimeType?.startsWith("audio/", ignoreCase = true) == true ||
        errorMessage?.contains("MediaCodecAudioRenderer", ignoreCase = true) == true
    return !audioFailure
}
