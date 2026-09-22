package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseStreamSequenceCursorTest {
    @Test
    fun acceptsFirstAndNextSequenceNumbers() {
        val cursor = ResponseStreamSequenceCursor()

        assertEquals(ResponseStreamSequenceObservation.ACCEPTED, cursor.observe(0L))
        assertEquals(0L, cursor.lastSequenceNumber)
        assertEquals(ResponseStreamSequenceObservation.ACCEPTED, cursor.observe(1L))
        assertEquals(1L, cursor.lastSequenceNumber)
    }

    @Test
    fun ignoresDuplicateAndOutOfOrderWithoutMovingCursor() {
        val cursor = ResponseStreamSequenceCursor()
        cursor.observe(7L)

        assertEquals(ResponseStreamSequenceObservation.DUPLICATE, cursor.observe(7L))
        assertEquals(ResponseStreamSequenceObservation.DUPLICATE, cursor.observe(6L))
        assertEquals(7L, cursor.lastSequenceNumber)
    }

    @Test
    fun rejectsGapAndKeepsLastAcceptedSequence() {
        val cursor = ResponseStreamSequenceCursor()
        cursor.observe(12L)

        try {
            cursor.observe(14L)
            throw AssertionError("Expected ResponseStreamSequenceGapException")
        } catch (e: ResponseStreamSequenceGapException) {
            assertEquals(13L, e.expectedSequenceNumber)
            assertEquals(14L, e.receivedSequenceNumber)
        }

        assertEquals(12L, cursor.lastSequenceNumber)
    }

    @Test
    fun resumesFromExistingCursor() {
        val cursor = ResponseStreamSequenceCursor(42L)

        assertEquals(ResponseStreamSequenceObservation.ACCEPTED, cursor.observe(43L))
        assertEquals(43L, cursor.lastSequenceNumber)
    }
}
