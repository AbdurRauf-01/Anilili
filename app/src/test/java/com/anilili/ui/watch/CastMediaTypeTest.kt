package com.anilili.ui.watch

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class CastMediaTypeTest {
    @Test
    fun directStreamsAlwaysReceiveCastCompatibleMimeType() {
        assertEquals(MimeTypes.VIDEO_MP4, videoMimeType("direct", "https://cdn.example/video"))
        assertEquals(MimeTypes.VIDEO_MP4, videoMimeType("mp4", "https://cdn.example/video.mp4?token=1"))
        assertEquals(MimeTypes.VIDEO_WEBM, videoMimeType("direct", "https://cdn.example/video.webm"))
        assertEquals(MimeTypes.VIDEO_MATROSKA, videoMimeType("mkv", "https://cdn.example/video"))
        assertEquals(MimeTypes.VIDEO_MP2T, videoMimeType("direct", "https://cdn.example/video.ts#part"))
    }

    @Test
    fun hlsDetectionWorksFromTypeOrUrl() {
        assertEquals(MimeTypes.APPLICATION_M3U8, videoMimeType("hls", "https://cdn.example/master"))
        assertEquals(MimeTypes.APPLICATION_M3U8, videoMimeType("direct", "https://cdn.example/master.m3u8?x=1"))
    }
}
