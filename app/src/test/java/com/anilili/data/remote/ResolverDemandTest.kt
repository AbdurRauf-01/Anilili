package com.anilili.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverDemandTest {
    @Test
    fun `one lease keeps resolver required until it closes`() {
        val demand = ResolverDemand("test-single")

        val lease = demand.acquire()
        assertTrue(demand.required.value)

        lease.close()
        assertFalse(demand.required.value)
    }

    @Test
    fun `overlapping leases cannot release each other`() {
        val demand = ResolverDemand("test-overlap")

        val first = demand.acquire()
        val second = demand.acquire()
        first.close()
        assertTrue(demand.required.value)

        second.close()
        assertFalse(demand.required.value)
    }

    @Test
    fun `closing a lease twice is harmless`() {
        val demand = ResolverDemand("test-idempotent")
        val lease = demand.acquire()

        lease.close()
        lease.close()

        assertFalse(demand.required.value)
    }

    @Test
    fun `warm session survives the catalog request closing until source handoff completes`() {
        val demand = ResolverDemand("test-warm-handoff")
        val warmSession = demand.acquire()
        val catalogRequest = demand.acquire()

        catalogRequest.close()
        assertTrue(demand.required.value)

        val sourceRequest = demand.acquire()
        warmSession.close()
        assertTrue(demand.required.value)

        sourceRequest.close()
        assertFalse(demand.required.value)
    }
}
