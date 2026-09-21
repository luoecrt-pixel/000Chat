package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseEventParserTest {

    @Test
    fun parsesDataWithoutSpaceAndBlankRecordBoundary() {
        val parser = SseEventParser()
        assertNull(parser.acceptLine(": comment"))
        assertNull(parser.acceptLine("data:{"type":"response.created"}"))
        val event = parser.acceptLine("")
        assertEquals("{\"type\":\"response.created\"}", event?.data)
    }

    @Test
    fun joinsMultilineDataAndPreservesEventAndId() {
        val parser = SseEventParser()
        parser.acceptLine("id: 42")
        parser.acceptLine("event: message")
        parser.acceptLine("data: first")
        parser.acceptLine("data: second")
        val event = parser.acceptLine("")
        assertEquals("message", event?.event)
        assertEquals("first\nsecond", event?.data)
        assertEquals("42", event?.id)
    }

    @Test
    fun handlesCrLfAndResetsEventTypeButKeepsLastEventId() {
        val parser = SseEventParser()
        parser.acceptLine("event: first\r")
        parser.acceptLine("data: one\r")
        val first = parser.acceptLine("\r")
        parser.acceptLine("data: two\r")
        val second = parser.acceptLine("\r")
        assertEquals("first", first?.event)
        assertEquals("one", first?.data)
        assertEquals(null, second?.event)
        assertEquals("two", second?.data)
        assertEquals("42", second?.id)
    }

    @Test
    fun finishFlushesAnEventWithoutTrailingBlankLine() {
        val parser = SseEventParser()
        parser.acceptLine("data: [DONE]")
        val event = parser.finish()
        assertEquals("[DONE]", event?.data)
    }

    @Test
    fun ignoresCommentsUnknownFieldsAndRetryHints() {
        val parser = SseEventParser()
        parser.acceptLine(": keep-alive")
        parser.acceptLine("retry: 5000")
        parser.acceptLine("unknown: value")
        parser.acceptLine("data: payload")
        val event = parser.acceptLine("")
        assertEquals("payload", event?.data)
    }
}
