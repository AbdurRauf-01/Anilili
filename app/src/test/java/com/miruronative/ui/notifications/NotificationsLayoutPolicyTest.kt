package com.miruronative.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationsLayoutPolicyTest {
    @Test
    fun `tv notifications use two columns`() {
        assertEquals(2, notificationColumnCount(isTv = true))
    }

    @Test
    fun `non tv notifications remain single column`() {
        assertEquals(1, notificationColumnCount(isTv = false))
    }
}
