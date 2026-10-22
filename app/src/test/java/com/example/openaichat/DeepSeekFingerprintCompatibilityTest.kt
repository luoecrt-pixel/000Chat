package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekFingerprintCompatibilityTest {
    @Test fun addingOptionalProtocolDoesNotChangeHistoricV1Hash() {
        val message = JSONObject().put("role", "assistant").put("text", "unchanged message")
        // Captured from the base revision's field order and normalisation, before adding DeepSeek.
        assertEquals("14d25d28fae72a4aa04db7d0e9ef383ddcde0c62b91dd6a875ed59ce5cad30e8", ChatMessageFingerprint.of(message))
        val prior = ChatMessageFingerprint.of(message)
        message.put("deepseek_protocol", JSONObject().put("version", 1).put("replayable", false))
        assertNotEquals(prior, ChatMessageFingerprint.of(message))
        message.remove("deepseek_protocol")
        assertEquals(prior, ChatMessageFingerprint.of(message))
    }
}
