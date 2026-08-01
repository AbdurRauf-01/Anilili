package com.anilili.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class MalImportProgressTest {
    @Test
    fun exposesConcreteProgressForEveryImportStage() {
        assertEquals("Reading file", MalImportProgress(MalImportStage.READING).label)
        assertEquals("Parsing list", MalImportProgress(MalImportStage.PARSING).label)
        assertEquals(
            "Matching titles 2/5",
            MalImportProgress(MalImportStage.MATCHING, completed = 2, total = 5).label,
        )
        assertEquals("Saving watchlist", MalImportProgress(MalImportStage.SAVING).label)
    }

    @Test
    fun matchingTimeoutScalesForLargeListsButRemainsBounded() {
        assertEquals(185_000L, malImportMatchTimeoutMs(batchCount = 1))
        assertEquals(430_000L, malImportMatchTimeoutMs(batchCount = 50))
        assertEquals(600_000L, malImportMatchTimeoutMs(batchCount = 500))
    }
}
