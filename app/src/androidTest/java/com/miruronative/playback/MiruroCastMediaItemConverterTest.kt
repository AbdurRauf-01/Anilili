package com.miruronative.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.cast.MediaTrack
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MiruroCastMediaItemConverterTest {
    @Test
    fun mp4AndWebVttSurviveCastQueueConversion() {
        val item = MediaItem.Builder()
            .setMediaId("episode-1")
            .setUri("https://cdn.example/episode.mp4")
            .setMimeType(MimeTypes.VIDEO_MP4)
            .setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse("https://cdn.example/en.vtt"))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("en")
                        .setLabel("English")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                    // Google's Default Media Receiver cannot render SRT; it must remain local-only.
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse("https://cdn.example/es.srt"))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("es")
                        .setLabel("Spanish")
                        .build(),
                ),
            )
            .build()

        val converter = MiruroCastMediaItemConverter()
        val queueItem = converter.toMediaQueueItem(item)
        val castMedia = checkNotNull(queueItem.media)

        assertEquals(MimeTypes.VIDEO_MP4, castMedia.contentType)
        assertEquals(1, castMedia.mediaTracks?.size)
        assertEquals(MediaTrack.TYPE_TEXT, castMedia.mediaTracks?.single()?.type)
        assertEquals(MimeTypes.TEXT_VTT, castMedia.mediaTracks?.single()?.contentType)
        assertArrayEquals(longArrayOf(1L), queueItem.activeTrackIds)

        val roundTrip = converter.toMediaItem(queueItem)
        assertEquals(MimeTypes.VIDEO_MP4, roundTrip.localConfiguration?.mimeType)
        assertEquals(1, roundTrip.localConfiguration?.subtitleConfigurations?.size)
        assertEquals(
            MimeTypes.TEXT_VTT,
            roundTrip.localConfiguration?.subtitleConfigurations?.single()?.mimeType,
        )
    }
}
