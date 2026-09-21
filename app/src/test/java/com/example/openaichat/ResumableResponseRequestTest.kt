package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableResponseRequestTest {
    @Test
    fun includesStreamAndCursorOnResume() {
        val url = ResumableResponseRequest.url("resp_123", 42L)
        assertEquals("https://api.openai.com/v1/responses/resp_123?stream=true&starting_after=42", url.toString())
    }

    @Test
    fun omitsCursorBeforeFirstSequence() {
        val url = ResumableResponseRequest.url("resp_123", -1L)
        assertEquals("https://api.openai.com/v1/responses/resp_123?stream=true", url.toString())
        assertFalse(url.queryParameterNames.contains("starting_after"))
        assertTrue(url.queryParameterNames.contains("stream"))
    }
}
