package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseCheckpointPolicyTest {
    @Test
    fun skipsNonTerminalCheckpointBeforeInterval() {
        val decision = ResponseCheckpointPolicy.decide(
            state = ResponseEventState(text = "partial"),
            responseId = "resp_1",
            lastSequenceNumber = 12L,
            nowElapsedMs = 1_499L,
            lastCheckpointElapsedMs = 1_000L,
            checkpointIntervalMs = 500L
        )

        assertFalse(decision.shouldPersist)
        assertEquals(ResponseCheckpointReason.NONE, decision.reason)
    }

    @Test
    fun checkpointsNonTerminalStateAtInterval() {
        val decision = ResponseCheckpointPolicy.decide(
            state = ResponseEventState(text = "partial"),
            responseId = "resp_1",
            lastSequenceNumber = 12L,
            nowElapsedMs = 1_500L,
            lastCheckpointElapsedMs = 1_000L,
            checkpointIntervalMs = 500L
        )

        assertTrue(decision.shouldPersist)
        assertEquals(ResponseCheckpointReason.INTERVAL, decision.reason)
        assertEquals("resp_1", decision.responseId)
        assertEquals("partial", decision.text)
        assertEquals("in_progress", decision.status)
        assertEquals(12L, decision.lastSequenceNumber)
    }

    @Test
    fun terminalStateCheckpointsBeforeInterval() {
        val decision = ResponseCheckpointPolicy.decide(
            state = ResponseEventState(
                responseId = "resp_1",
                text = "done",
                terminalState = ResponseTerminalState.COMPLETED
            ),
            responseId = "resp_1",
            lastSequenceNumber = 13L,
            nowElapsedMs = 1_100L,
            lastCheckpointElapsedMs = 1_000L,
            checkpointIntervalMs = 500L
        )

        assertTrue(decision.shouldPersist)
        assertEquals(ResponseCheckpointReason.TERMINAL, decision.reason)
        assertEquals("completed", decision.status)
        assertEquals(13L, decision.lastSequenceNumber)
    }

    @Test
    fun mapsAllTerminalStatuses() {
        val expected = mapOf(
            ResponseTerminalState.COMPLETED to "completed",
            ResponseTerminalState.FAILED to "failed",
            ResponseTerminalState.INCOMPLETE to "incomplete",
            ResponseTerminalState.CANCELLED to "cancelled"
        )

        expected.forEach { (terminalState, status) ->
            val decision = ResponseCheckpointPolicy.decide(
                state = ResponseEventState(terminalState = terminalState),
                responseId = "resp_1",
                lastSequenceNumber = 7L,
                nowElapsedMs = 100L,
                lastCheckpointElapsedMs = 0L,
                checkpointIntervalMs = 500L
            )

            assertEquals(ResponseCheckpointReason.TERMINAL, decision.reason)
            assertEquals(status, decision.status)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeInterval() {
        ResponseCheckpointPolicy.decide(
            state = ResponseEventState(),
            responseId = null,
            lastSequenceNumber = -1L,
            nowElapsedMs = 0L,
            lastCheckpointElapsedMs = 0L,
            checkpointIntervalMs = -1L
        )
    }
}
