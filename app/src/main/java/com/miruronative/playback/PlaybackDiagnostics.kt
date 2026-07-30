package com.miruronative.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.miruronative.diagnostics.DiagnosticsLog
import java.io.IOException

/** Native Media3 diagnostics with compact session summaries and actionable decoder/load events. */
@OptIn(UnstableApi::class)
object PlaybackDiagnostics {
    fun attach(player: ExoPlayer) {
        player.addAnalyticsListener(
            PlaybackStatsListener(false) { _, stats ->
                DiagnosticsLog.event(
                    category = "playback",
                    name = "session.summary",
                    attributes = mapOf(
                        "playMs" to stats.totalPlayTimeMs,
                        "waitMs" to stats.totalWaitTimeMs,
                        "joinMs" to stats.totalJoinTimeMs,
                        "rebufferCount" to stats.totalRebufferCount,
                        "rebufferMs" to stats.totalRebufferTimeMs,
                        "seekCount" to stats.totalSeekCount,
                        "droppedFrames" to stats.totalDroppedFrames,
                        "audioUnderruns" to stats.totalAudioUnderruns,
                        "networkBytes" to stats.totalBandwidthBytes,
                        "networkTimeMs" to stats.totalBandwidthTimeMs,
                        "meanVideoHeight" to stats.meanVideoFormatHeight,
                        "meanVideoBitrate" to stats.meanVideoFormatBitrate,
                        "fatalErrors" to stats.fatalErrorCount,
                        "nonFatalErrors" to stats.nonFatalErrorCount,
                    ),
                )
            },
        )
        player.addAnalyticsListener(DetailListener())
        DiagnosticsLog.event("playback", "analytics.attached")
    }

    private class DetailListener : AnalyticsListener {
        private var lastVideoFormat: String? = null
        private var lastAudioFormat: String? = null

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            DiagnosticsLog.event(
                "playback",
                "video.decoder_initialized",
                mapOf("decoder" to decoderName, "durationMs" to initializationDurationMs),
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            DiagnosticsLog.event(
                "playback",
                "audio.decoder_initialized",
                mapOf("decoder" to decoderName, "durationMs" to initializationDurationMs),
            )
        }

        override fun onDownstreamFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            mediaLoadData: MediaLoadData,
        ) {
            val format = mediaLoadData.trackFormat ?: return
            val fingerprint = formatFingerprint(format)
            when (mediaLoadData.trackType) {
                C.TRACK_TYPE_VIDEO -> if (lastVideoFormat != fingerprint) {
                    lastVideoFormat = fingerprint
                    logFormat("video.format_changed", format)
                }
                C.TRACK_TYPE_AUDIO -> if (lastAudioFormat != fingerprint) {
                    lastAudioFormat = fingerprint
                    logFormat("audio.format_changed", format)
                }
            }
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            if (droppedFrames <= 0) return
            DiagnosticsLog.event(
                "playback",
                "video.frames_dropped",
                mapOf("count" to droppedFrames, "windowMs" to elapsedMs),
            )
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
            DiagnosticsLog.event(
                "playback",
                "video.size_changed",
                mapOf(
                    "width" to videoSize.width,
                    "height" to videoSize.height,
                    "pixelRatio" to videoSize.pixelWidthHeightRatio,
                ),
            )
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            DiagnosticsLog.event(
                category = "playback",
                name = "media.load_error",
                attributes = mapOf(
                    "host" to (loadEventInfo.uri.host ?: "unknown"),
                    "dataType" to mediaLoadData.dataType,
                    "trackType" to mediaLoadData.trackType,
                    "bytesLoaded" to loadEventInfo.bytesLoaded,
                    "loadDurationMs" to loadEventInfo.loadDurationMs,
                    "canceled" to wasCanceled,
                    "failureType" to error.javaClass.simpleName,
                    "failureMessage" to (error.message ?: "none"),
                ),
            )
        }

        private fun logFormat(name: String, format: Format) {
            DiagnosticsLog.event(
                category = "playback",
                name = name,
                attributes = mapOf(
                    "sampleMimeType" to (format.sampleMimeType ?: "unknown"),
                    "codecs" to (format.codecs ?: "unknown"),
                    "bitrate" to format.bitrate,
                    "width" to format.width,
                    "height" to format.height,
                    "frameRate" to format.frameRate,
                    "channels" to format.channelCount,
                    "sampleRate" to format.sampleRate,
                    "language" to (format.language ?: "unknown"),
                ),
            )
        }

        private fun formatFingerprint(format: Format): String = listOf(
            format.sampleMimeType,
            format.codecs,
            format.bitrate,
            format.width,
            format.height,
            format.channelCount,
            format.sampleRate,
            format.language,
        ).joinToString("|")
    }
}
