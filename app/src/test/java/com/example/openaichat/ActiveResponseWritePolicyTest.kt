package com.example.openaichat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveResponseWritePolicyTest {
    @Test
    fun commitsOnlyWhenActiveRequestStillMatchesExpectedRequest() {
        assertTrue(ActiveResponseWritePolicy.shouldCommit("req-a", "req-a"))
        assertFalse(ActiveResponseWritePolicy.shouldCommit("req-b", "req-a"))
        assertFalse(ActiveResponseWritePolicy.shouldCommit(null, "req-a"))
        assertFalse(ActiveResponseWritePolicy.shouldCommit("req-a", ""))
    }

    @Test
    fun newerRequestOwnsTheSlotAfterOldWorkerLosesOwnership() {
        val staleWorkerRequest = "req-old"
        val currentRequest = "req-new"

        assertFalse(ActiveResponseWritePolicy.shouldCommit(currentRequest, staleWorkerRequest))
        assertTrue(ActiveResponseWritePolicy.shouldCommit(currentRequest, currentRequest))
    }
}
