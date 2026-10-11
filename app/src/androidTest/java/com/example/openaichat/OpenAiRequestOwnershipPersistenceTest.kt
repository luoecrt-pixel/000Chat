package com.example.openaichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Database regression for local request ownership vs independent provider HTTP request ID.
 * Activity Send wiring is separately guarded by verify_implementation.py.
 */
@RunWith(AndroidJUnit4::class)
class OpenAiRequestOwnershipPersistenceTest {
    @Test fun localRequestIdIsPersistedWhileHttpRequestIdRemainsDiagnostic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = "owner-regression-" + UUID.randomUUID()
        val localId = "local_" + UUID.randomUUID()
        val providerHttpId = "req_provider_not_local"
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Owner test")
                db.upsertCurrentChat(chatId, "Owner test", null, null, listOf(
                    JSONObject().put("role", "user").put("request_id", localId).put("text", "Hello")))
                db.setActiveResponse(chatId, null, localId, "", "in_progress", 1L,
                    "high", "high", false, 0, 0, false)
                val response = JSONObject().put("id", "resp_test").put("status", "completed")
                val wrong = AstraResponseModel.fromResponse(response, "visible output", providerHttpId).toChatJson()
                assertFalse("Provider HTTP ID must not consume the local active claim",
                    db.finalizeCurrentChatIfActiveRequest(chatId, "", "resp_test", "high", listOf(wrong), localId))
                assertTrue(db.isActiveRequest(chatId, localId))
                val assistant = AstraResponseModel.fromResponse(response, "visible output", localId).toChatJson()
                assertEquals(localId, assistant.getString("request_id"))
                assertNotEquals(providerHttpId, assistant.getString("request_id"))
                assertTrue(db.finalizeCurrentChatIfActiveRequest(chatId, "", "resp_test", "high", listOf(assistant), localId))
                assertFalse(db.isActiveRequest(chatId, localId))
                val saved = db.getChat(chatId)!!.getJSONArray("messages")
                assertEquals(2, saved.length())
                assertEquals("visible output", saved.getJSONObject(1).getString("text"))
                assertEquals(localId, saved.getJSONObject(1).getString("request_id"))
            }
            ChatDatabase(context).use { db ->
                val saved = db.getChat(chatId)!!.getJSONArray("messages")
                assertEquals("visible output", saved.getJSONObject(1).getString("text"))
                assertFalse(db.isActiveRequest(chatId, localId))
            }
        } finally { ChatDatabase(context).use { it.deleteChat(chatId) } }
    }

    @Test fun failedResponseHasEmptyCanonicalTextAndErrorOnlyInMetadata() {
        val failure = AstraResponseModel.fromResponse(JSONObject().put("status", "failed"), "",
            "local_failure", "failed", "HTTP 400: bad request").toChatJson()
        assertEquals("", failure.getString("text"))
        assertEquals("HTTP 400: bad request", failure.getString("error"))
        assertEquals("failed", failure.getString("status"))
        val nullable = AstraResponseModel.fromResponse(JSONObject()
            .put("id", JSONObject.NULL).put("model", JSONObject.NULL)
            .put("status", JSONObject.NULL)
            .put("error", JSONObject().put("message", JSONObject.NULL)), "", "local_null")
        assertEquals(null, nullable.id)
        assertEquals(null, nullable.model)
        assertEquals("completed", nullable.status)
        assertEquals(null, nullable.error)
        assertTrue(nullable.toChatJson().isNull("response_id"))
    }
}
