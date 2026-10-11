package com.example.openaichat

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnswerNotificationOutboxTest {
    @Test fun interruptionAfterPostCanBeReplayedWithSameRequestAndThenDeduplicates() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = context.getSharedPreferences("outbox_test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        val record = JSONObject().put("chat_id", "c").put("completed", true)
        var posts = 0
        assertThrows(IllegalStateException::class.java) {
            AnswerNotificationOutbox.deliver(store, "r", record) { posts++; error("Simulated process interruption") }
        }
        val (request, pending) = AnswerNotificationOutbox.pending(store).single()
        assertEquals("r", request); assertEquals("c", pending.getString("chat_id"))
        AnswerNotificationOutbox.deliver(store, request, pending) { posts++ }
        AnswerNotificationOutbox.deliver(store, request, pending) { posts++ }
        assertEquals(2, posts); assertTrue(AnswerNotificationOutbox.pending(store).isEmpty())
    }
}
