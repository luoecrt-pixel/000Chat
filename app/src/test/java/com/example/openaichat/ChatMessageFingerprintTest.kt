package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageFingerprintTest {
    @Test
    fun samePersistedContentHasSameFingerprint() {
        val a = JSONObject()
            .put("role", "assistant")
            .put("text", "hello")
            .put("request_id", "req-1")
            .put("tools", JSONArray().put("web"))
        val b = JSONObject()
            .put("role", "assistant")
            .put("text", "hello")
            .put("request_id", "req-1")
            .put("tools", JSONArray().put("web"))

        assertEquals(ChatMessageFingerprint.of(a), ChatMessageFingerprint.of(b))
    }

    @Test
    fun changingPersistedFieldChangesFingerprint() {
        val a = JSONObject().put("role", "assistant").put("text", "hello")
        val b = JSONObject().put("role", "assistant").put("text", "hello!")
        assertNotEquals(ChatMessageFingerprint.of(a), ChatMessageFingerprint.of(b))
    }

    @Test
    fun omittedOptionalFieldAndEmptyArrayAreDistinct() {
        val a = JSONObject().put("role", "assistant")
        val b = JSONObject().put("role", "assistant").put("tools", JSONArray())
        assertNotEquals(ChatMessageFingerprint.of(a), ChatMessageFingerprint.of(b))
    }
}
