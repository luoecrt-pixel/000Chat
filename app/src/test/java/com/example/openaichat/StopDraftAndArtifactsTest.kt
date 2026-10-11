package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StopDraftAndArtifactsTest {
    private lateinit var context: Context
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db")) }
    @After fun cleanup() { SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db")) }
    private fun user(id: String = "r") = JSONObject().put("role", "user").put("text", "Draft").put("request_id", id)
    private fun prepare(db: ChatDatabase) {
        db.ensureChat("chat", "Test"); db.upsertCurrentChat("chat", "Test", null, null, listOf(user()))
        db.setActiveResponse("chat", null, "r", "partial", "in_progress", 1, "max", "max", false, 5, 0, true)
        db.startRequestAccounting("chat", "r", "openrouter", "anthropic/claude-opus-5.5", "openrouter_api_key")
    }
    @Test fun stopReopenAndStaleUiSaveDoNotResurrectRestoredDraft() {
        ChatDatabase(context).use { db ->
            prepare(db); assertTrue(db.stopActiveRequestOnce("chat", "r", true))
            db.upsertUiSnapshotPreservingTail("chat", "Test", null, null, listOf(user()))
        }
        ChatDatabase(context).use { db ->
            assertEquals(0, db.getChat("chat")!!.getJSONArray("messages").length())
            db.upsertUiSnapshotPreservingTail("chat", "Test", null, null, listOf(user("new")))
            assertEquals(1, db.getChat("chat")!!.getJSONArray("messages").length())
        }
    }
    @Test fun stopAndCompletedWriterHaveExactlyOneWinner() {
        ChatDatabase(context).use { db ->
            prepare(db)
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            try {
                val stop = pool.submit<Boolean> { go.await(); db.stopActiveRequestOnce("chat", "r", true) }
                val finish = pool.submit<Boolean> { go.await(); db.finalizeCurrentChatIfActiveRequest("chat", "Test", null, null,
                    listOf(JSONObject().put("role", "assistant").put("request_id", "r").put("text", "Answer").put("status", "completed")), "r") }
                go.countDown()
                val stopped = stop.get(5, TimeUnit.SECONDS); val completed = finish.get(5, TimeUnit.SECONDS)
                assertTrue(stopped.xor(completed)); assertNull(db.getActiveResponse("chat"))
                val rows = db.getChat("chat")!!.getJSONArray("messages")
                assertEquals(if (completed) 2 else 0, rows.length())
            } finally { pool.shutdownNow() }
        }
    }
    @Test fun generatedFileSurvivesStopAndDisappearsWithDeletedChat() {
        val file = File(context.filesDir, "regression-generated.txt").apply { writeText("content") }
        try {
            ChatDatabase(context).use { db ->
                prepare(db)
                assertTrue(db.registerLocalArtifact("chat", "r", JSONObject().put("filename", "report.txt").put("path", file.path)
                    .put("mime_type", "text/plain").put("size_bytes", file.length())))
                db.stopActiveRequestOnce("chat", "r", true)
                assertEquals(file.path, db.listLocalFiles().single().getString("path"))
                assertTrue(db.listAllLocalFilePaths().contains(file.path))
                db.deleteChat("chat")
                assertTrue(db.listLocalFiles().isEmpty())
            }
        } finally { file.delete() }
    }
}
