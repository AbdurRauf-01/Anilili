package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class PipeBridgeMirrorOrderTest {

    private val origins = listOf(".to", ".tv", ".bz", ".ru")

    @Test
    fun `last working final mirror wraps through every configured origin`() {
        assertEquals(
            listOf(".ru", ".to", ".tv", ".bz"),
            orderedPipeOrigins(origins, ".ru"),
        )
    }

    @Test
    fun `persisted middle mirror is tried first without dropping earlier mirrors`() {
        assertEquals(
            listOf(".bz", ".ru", ".to", ".tv"),
            orderedPipeOrigins(origins, ".bz"),
        )
    }

    @Test
    fun `unknown persisted mirror uses configured order`() {
        assertEquals(origins, orderedPipeOrigins(origins, ".invalid"))
    }

    @Test
    fun `cold start budget covers every mirror plus page settling`() {
        assertEquals(
            31_000L,
            pipePageReadyTimeoutMs(
                originCount = 4,
                mirrorTimeoutMs = 7_000L,
                pageSettleMs = 2_000L,
                schedulerGraceMs = 1_000L,
            ),
        )
    }

    @Test
    fun `source attempt cannot cancel a complete cold start`() {
        assertEquals(
            40_000L,
            pipeSourceAttemptTimeoutMs(
                pageReadyTimeoutMs = 31_000L,
                responseTimeoutMs = 8_000L,
                completionGraceMs = 1_000L,
            ),
        )
    }

    @Test
    fun `remembered healthy mirror receives the longer watchdog`() {
        assertEquals(
            12_000L,
            pipeMirrorWatchdogTimeoutMs(
                origin = ".bz",
                lastWorkingOrigin = ".bz",
                standardTimeoutMs = 7_000L,
                rememberedTimeoutMs = 12_000L,
            ),
        )
        assertEquals(
            7_000L,
            pipeMirrorWatchdogTimeoutMs(
                origin = ".to",
                lastWorkingOrigin = ".bz",
                standardTimeoutMs = 7_000L,
                rememberedTimeoutMs = 12_000L,
            ),
        )
    }

    @Test
    fun `cold start budget includes remembered mirror extension`() {
        assertEquals(
            36_000L,
            pipePageReadyTimeoutMs(
                originCount = 4,
                mirrorTimeoutMs = 7_000L,
                pageSettleMs = 2_000L,
                schedulerGraceMs = 1_000L,
                rememberedMirrorExtraMs = 5_000L,
            ),
        )
    }
}
