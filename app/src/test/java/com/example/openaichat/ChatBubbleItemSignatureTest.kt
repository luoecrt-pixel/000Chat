package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBubbleItemSignatureTest {
    @Test
    fun transientSignatureTracksEveryRenderedFieldWhileIdentityStaysSeparate() {
        val annotations = JSONArray().put(JSONObject().put("url", "https://example.test/first"))
        val base = ChatBubbleItem.create("active", "GPT", "answer", false,
            annotations = annotations, reasoningSummary = "summary")
        val variants = listOf(
            ChatBubbleItem.create("active", "Claude", "answer", false, annotations = annotations, reasoningSummary = "summary"),
            ChatBubbleItem.create("active", "GPT", "answer tail", false, annotations = annotations, reasoningSummary = "summary"),
            ChatBubbleItem.create("active", "GPT", "answer", true, annotations = annotations, reasoningSummary = "summary"),
            ChatBubbleItem.create("active", "GPT", "answer", false,
                annotations = JSONArray().put(JSONObject().put("url", "https://example.test/changed")), reasoningSummary = "summary"),
            ChatBubbleItem.create("active", "GPT", "answer", false, annotations = annotations, reasoningSummary = "new summary")
        )
        assertTrue(base.contentSignature.matches(Regex("[0-9a-f]{64}")))
        variants.forEach { changed ->
            assertEquals(base.id, changed.id)
            assertNotEquals(base.contentSignature, changed.contentSignature)
        }
        val differentIdentity = ChatBubbleItem.create("different-id", "GPT", "answer", false,
            annotations = annotations, reasoningSummary = "summary", chatIndex = 100)
        assertEquals(base.contentSignature, differentIdentity.contentSignature)
    }

    @Test
    fun transientSignatureSeparatesFieldsEvenWhenSourceContainsOldDelimiter() {
        val first = ChatBubbleItem.create("active", "GPT\u001fpart", "answer", false)
        val second = ChatBubbleItem.create("active", "GPT", "part\u001fanswer", false)
        assertNotEquals(first.contentSignature, second.contentSignature)
    }

    @Test
    fun committedMessageSignatureKeepsExistingFingerprintContract() {
        val message = JSONObject().put("role", "assistant").put("text", "full answer")
            .put("request_id", "request-1").put("response_id", "response-1")
        val expected = ChatMessageFingerprint.of(message)
        val item = ChatBubbleItem.create("assistant", "GPT", "full answer", false, message = message)
        assertEquals(expected, item.contentSignature)
        assertEquals(expected, ChatBubbleItem.create("other-id", "Changed label", "display text", true,
            message = message, reasoningSummary = "display summary").contentSignature)
    }
}
