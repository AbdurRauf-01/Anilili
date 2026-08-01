package com.anilili.ui.profile

import com.anilili.data.model.MediaListCollection
import com.anilili.data.model.MediaListEntry
import com.anilili.data.model.MediaListGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileListPolicyTest {
    @Test
    fun `custom list duplicates are retained once and classified by entry status`() {
        val entry = MediaListEntry(id = 9, status = "CURRENT")
        val collection = MediaListCollection(
            lists = listOf(
                MediaListGroup(status = "CURRENT", entries = listOf(entry)),
                MediaListGroup(name = "Favorites", isCustomList = true, entries = listOf(entry)),
            ),
        )

        assertEquals(listOf(entry), collection.allEntries())
    }
}
