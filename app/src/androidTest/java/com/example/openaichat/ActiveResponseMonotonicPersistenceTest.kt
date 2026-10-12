package com.example.openaichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * OA-2 regression against actual SQLite writes, not a pure policy mock.
 * Activity and WorkManager share an active request, so the DB predicate must
 * reject older snapshots atomically even when the local request ID matches.
 */
@RunWith(AndroidJUnit4::class)
class ActiveResponseMonotonicPersistenceTest {
    @Test fun staleAndEqualSequenceCannotRegressTextOrTerminalOrDifferentRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = "sequence-" + UUID.randomUUID()
        val firstRequest = "local_first"
        val secondRequest = "local_second"
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Sequence test")
                db.setActiveResponse(chatId, "resp_first", firstRequest, "", "in_progress",
                    1L, "high", "high", false, 0, 0, true)
                db.updateActiveResponse(chatId, text = "twenty", status = "in_progress",
                    lastSequenceNumber = 20L, expectedRequestId = firstRequest)
                db.updateActiveResponse(chatId, text = "ten", status = "in_progress",
                    lastSequenceNumber = 10L, expectedRequestId = firstRequest)
                db.updateActiveResponse(chatId, text = "stale equal", status = "in_progress",
                    lastSequenceNumber = 20L, expectedRequestId = firstRequest)
                db.updateActiveResponse(chatId, text = "stale terminal", status = "completed",
                    lastSequenceNumber = 10L, expectedRequestId = firstRequest)
                var active = db.getActiveResponse(chatId)
                assertNotNull(active)
                assertEquals("twenty", active!!.getString("text"))
                assertEquals(20L, active.getLong("last_sequence_number"))
                assertEquals("in_progress", active.getString("status"))

                // The same sequence can harmlessly repeat if it is idempotent.
                db.updateActiveResponse(chatId, text = "twenty", status = "in_progress",
                    lastSequenceNumber = 20L, expectedRequestId = firstRequest)
                // Terminal at that sequence is allowed to install canonical text.
                db.updateActiveResponse(chatId, text = "canonical", status = "completed",
                    lastSequenceNumber = 20L, expectedRequestId = firstRequest)
                db.updateActiveResponse(chatId, text = "late twenty-one", status = "in_progress",
                    lastSequenceNumber = 21L, expectedRequestId = firstRequest)
                active = db.getActiveResponse(chatId)
                assertEquals("canonical", active!!.getString("text"))
                assertEquals("completed", active.getString("status"))
                assertEquals(20L, active.getLong("last_sequence_number"))

                db.setActiveResponse(chatId, "resp_second", secondRequest, "", "starting",
                    2L, "high", "high", false, 0, 0, false)
                db.updateActiveResponse(chatId, text = "old owner", status = "in_progress",
                    lastSequenceNumber = 50L, expectedRequestId = firstRequest)
                active = db.getActiveResponse(chatId)
                assertEquals("", active!!.getString("text"))
                assertEquals("starting", active.getString("status"))
                assertEquals(secondRequest, active.getString("request_id"))
                assertEquals(-1L, active.getLong("last_sequence_number"))
            }
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
        }
    }
}
