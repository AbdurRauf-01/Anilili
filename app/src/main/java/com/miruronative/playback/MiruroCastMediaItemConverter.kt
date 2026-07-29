package com.miruronative.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack

/**
 * Keeps Media3's standard Cast metadata/custom-data format while adding the external subtitle
 * tracks that its 1.8.x converter omits. Only WebVTT and TTML are sent: those are the external
 * subtitle formats supported by Google's Default Media Receiver (SRT/ASS remain local-only).
 */
@OptIn(UnstableApi::class)
internal class MiruroCastMediaItemConverter : MediaItemConverter {
    private val delegate = DefaultMediaItemConverter()

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val item = delegate.toMediaItem(mediaQueueItem)
        val media = mediaQueueItem.media ?: return item
        val activeTrackIds = mediaQueueItem.activeTrackIds?.toSet().orEmpty()
        val subtitles = media.mediaTracks.orEmpty()
            .filter { it.type == MediaTrack.TYPE_TEXT && it.contentId != null }
            .map { track ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.contentId!!))
                    .setMimeType(track.contentType ?: MimeTypes.TEXT_VTT)
                    .setLanguage(track.language)
                    .setLabel(track.name)
                    .apply {
                        if (track.id in activeTrackIds) setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    }
                    .build()
            }
        return if (subtitles.isEmpty()) item else item.buildUpon()
            .setSubtitleConfigurations(subtitles)
            .build()
    }

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        // The delegate preserves Media3's private custom-data schema, which CastPlayer needs to
        // reconstruct the MediaItem when the remote queue changes.
        val baseQueueItem = delegate.toMediaQueueItem(mediaItem)
        val baseMedia = checkNotNull(baseQueueItem.media)
        val supportedSubtitles = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
            .filter { it.isSupportedCastSubtitle() }
        val subtitleTracks = supportedSubtitles
            .mapIndexed { index, subtitle -> subtitle.toCastTrack(index + 1L) }
        if (subtitleTracks.isEmpty()) return baseQueueItem

        val castMedia = MediaInfo.Builder(baseMedia.contentId)
            .setStreamType(baseMedia.streamType)
            .setContentType(checkNotNull(baseMedia.contentType))
            .setContentUrl(baseMedia.contentUrl ?: baseMedia.contentId)
            .setMetadata(baseMedia.metadata)
            .setCustomData(baseMedia.customData)
            .setMediaTracks(subtitleTracks)
            .build()
        val queueBuilder = MediaQueueItem.Builder(castMedia)
        supportedSubtitles.indexOfFirst {
            it.selectionFlags.and(C.SELECTION_FLAG_DEFAULT) != 0
        }.takeIf { it >= 0 }?.let { defaultIndex ->
            queueBuilder.setActiveTrackIds(longArrayOf(subtitleTracks[defaultIndex].id))
        }
        return queueBuilder.build()
    }

    private fun MediaItem.SubtitleConfiguration.isSupportedCastSubtitle(): Boolean =
        uri.scheme in setOf("http", "https") && mimeType in CAST_SUBTITLE_MIME_TYPES

    private fun MediaItem.SubtitleConfiguration.toCastTrack(id: Long): MediaTrack =
        MediaTrack.Builder(id, MediaTrack.TYPE_TEXT)
            .setName(label ?: language ?: "Subtitles")
            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
            .setContentId(uri.toString())
            .setContentType(checkNotNull(mimeType))
            .setLanguage(language ?: "und")
            .build()

    private companion object {
        val CAST_SUBTITLE_MIME_TYPES = setOf(MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML)
    }
}
