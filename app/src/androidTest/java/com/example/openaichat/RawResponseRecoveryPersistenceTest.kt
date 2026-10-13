package com.example.openaichat

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RawResponseRecoveryPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun undecodedPaidSourceSurvivesSQLiteReopenAndHasAnActualSaveCard() {
        val chatId = "raw-export-" + UUID.randomUUID()
        val requestId = "req_raw_only"
        val bytes = "data: exact received bytes, not a fabricated completed response\n\n".toByteArray()
        val prefs = context.getSharedPreferences("app", 0)
        val previousChat = prefs.getString("current_chat", null)
        var archive: OpenAiRawSseArchive? = null
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Raw source fixture")
                db.upsertCurrentChat(chatId, "Raw source fixture", null, "high", listOf(
                    JSONObject().put("role", "user").put("provider", "openai")
                        .put("text", "Synthetic prompt").put("request_id", requestId)))
                db.setActiveResponse(chatId, "resp_raw_only", requestId, "", "in_progress",
                    System.currentTimeMillis(), "high", "high", false, 16, 0, true)
            }
            archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(bytes), context.filesDir, chatId, requestId)
            archive.use { it.receiveRemaining() }
            ChatDatabase(context).use { db ->
                assertTrue(BackgroundRequestFailureRecorder.persist(db, chatId, requestId,
                    "resp_raw_only", "failed", "explicit decoder limit", context.filesDir))
                assertNull(db.getActiveResponse(chatId))
            }
            var filename = ""
            ChatDatabase(context).use { db ->
                val messages = db.getChat(chatId)!!.getJSONArray("messages")
                assertEquals("No visible text is required to retain a paid source", 2, messages.length())
                val assistant = messages.getJSONObject(1)
                assertTrue(assistant.getBoolean("incomplete"))
                assertFalse("Do not synthesize provider output", assistant.has("openai_protocol_output"))
                val card = assistant.getJSONArray("annotations").getJSONObject(0)
                // Android /data/user/0 and /data/data can alias the same file.
                assertEquals(archive.file.canonicalFile, File(card.getString("local_path")).canonicalFile)
                assertArrayEquals(bytes, File(card.getString("local_path")).readBytes())
                filename = card.getString("filename")
                assertFalse(BackgroundRequestFailureRecorder.persist(db, chatId, requestId,
                    "resp_raw_only", "failed", "duplicate", context.filesDir))
            }
            assertTrue(prefs.edit().putString("current_chat", chatId).commit())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val labels = mutableListOf<String>()
                    fun collect(view: View) {
                        if (view is TextView) labels += view.text.toString()
                        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
                    }
                    collect(activity.window.decorView)
                    assertTrue("Received source must be visible in the real answer bubble",
                        labels.any { it.contains(filename) })
                    assertTrue("Use the existing user-controlled file Save flow", labels.any { it == "Сохранить" })
                    assertTrue(labels.any { it.contains("Полученные исходные данные ответа") })
                }
            }
        } finally {
            prefs.edit().apply { if (previousChat == null) remove("current_chat") else putString("current_chat", previousChat) }.commit()
            ChatDatabase(context).use { it.deleteChat(chatId) }
            archive?.file?.delete()
        }
    }

    @Test fun stoppedOrReplacedOwnerCannotAttachOldSourceToAnotherResponse() {
        val chatId = "raw-owner-" + UUID.randomUUID()
        val oldRequest = "req_raw_old"
        val newRequest = "req_raw_new"
        var archive: OpenAiRawSseArchive? = null
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Raw owner fixture")
                db.setActiveResponse(chatId, null, oldRequest, "", "starting",
                    System.currentTimeMillis(), "high", "high", false, 16, 0, true)
                assertTrue(db.stopActiveRequestOnce(chatId, oldRequest))
                db.setActiveResponse(chatId, "resp_new", newRequest, "", "starting",
                    System.currentTimeMillis(), "high", "high", false, 16, 0, true)
            }
            archive = OpenAiRawSseArchive.begin(ByteArrayInputStream("old source".toByteArray()),
                context.filesDir, chatId, oldRequest)
            archive.use { it.receiveRemaining() }
            ChatDatabase(context).use { db ->
                assertFalse(BackgroundRequestFailureRecorder.persist(db, chatId, oldRequest,
                    "resp_old", "failed", "late failure", context.filesDir))
                assertEquals(newRequest, db.getActiveResponse(chatId)!!.getString("request_id"))
                assertEquals(0, db.getChat(chatId)!!.getJSONArray("messages").length())
            }
            val newMessage = JSONObject()
            OpenAiRawSseArchive.attachToMessage(newMessage, context.filesDir, chatId, newRequest)
            assertFalse(newMessage.has("annotations"))
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
            archive?.file?.delete()
        }
    }
}
