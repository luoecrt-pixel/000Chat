package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekArtifactIntentRecoveryTest {
    private lateinit var context: Context
    private val args get() = JSONObject().put("filename", "confirmed.txt").put("content", "confirmed side effect")
    private val key = "chat\u0000req\u0000local_artifact_0"
    private val callId = "local_artifact_0"
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        java.io.File(context.filesDir, "generated").deleteRecursively()
    }
    @After fun cleanup() { SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db")) }
    private fun begin(db: ChatDatabase) {
        db.ensureChat("chat")
        assertTrue(db.startRequestAccounting("chat", "req", "deepseek", "test", "deepseek_api_key"))
        db.setActiveResponse("chat", null, "req", "", "starting", 1L, "high", "high", false, 1, 0, false)
    }
    private fun intent() = LocalArtifactToolExecutor(context).prepareCreateTextFile(args, key)
        .put("tool_name", "create_local_text_file").put("tool_call_id", callId)
        .put("arguments_sha256", StreamingJsonRequestBody.sha256(args))

    @Test fun publishedFileSurvivesProcessDeathBeforeRegistrationAndStopArchiveRetainsOriginalTail() {
        val planned = intent()
        val tail = JSONArray().put(JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(
            JSONObject().put("id", "original-call-id").put("type", "function").put("function",
                JSONObject().put("name", "create_local_text_file").put("arguments", args.toString())))))
        ChatDatabase(context).use { db ->
            begin(db)
            assertTrue(db.saveDeepSeekCheckpoint("chat", "req", JSONObject().put("version", 1).put("replayable", false).put("tail", tail)))
            assertTrue(db.saveLocalArtifactIntentIfActive("chat", "req", callId, planned))
            LocalArtifactToolExecutor(context).createTextFile(args, key)
            // Deliberately omit SQLite result registration: exactly the crash boundary.
        }
        ChatDatabase(context).use { db ->
            assertEquals(planned.getString("path"), db.getLocalArtifactToolResult("chat", "req", callId)!!.getString("path"))
            assertTrue(db.stopActiveRequestOnce("chat", "req"))
            val archive = db.getRequestRecoveryArchive("chat", "req")!!
            assertEquals(tail.toString(), archive.getJSONObject("deepseek_protocol").getJSONArray("tail").toString())
            assertEquals(planned.getString("path"), archive.getJSONArray("confirmed_artifacts").getJSONObject(0).getString("path"))
            assertEquals("confirmed side effect", java.io.File(planned.getString("path")).readText())
            assertEquals(0, db.getChat("chat")!!.getJSONArray("messages").length())
        }
    }

    @Test fun missingOrDifferentBytesNeverBecomeConfirmedAndRecoveryNeverExecutesTool() {
        val planned = intent()
        ChatDatabase(context).use { db -> begin(db); assertTrue(db.saveLocalArtifactIntentIfActive("chat", "req", callId, planned)) }
        ChatDatabase(context).use { db -> assertNull(db.getLocalArtifactToolResult("chat", "req", callId)) }
        val file = java.io.File(planned.getString("path")).apply { parentFile!!.mkdirs(); writeText("partial") }
        ChatDatabase(context).use { db ->
            assertNull(db.getLocalArtifactToolResult("chat", "req", callId))
            assertEquals("partial", file.readText())
        }
    }

    @Test fun malformedIntentStaysUnconfirmedWithoutBlockingOtherChatHistory() {
        ChatDatabase(context).use { db ->
            begin(db)
            db.ensureChat("unaffected", "Must still open")
            db.writableDatabase.execSQL("INSERT INTO local_artifact_intents(chat_id,request_id,call_id,metadata,created_at) VALUES(?,?,?,?,?)",
                arrayOf("chat", "req", "malformed", "{broken-json", 1L))
        }
        ChatDatabase(context).use { db ->
            assertEquals("Must still open", db.getChat("unaffected")!!.getString("title"))
            assertNull(db.getLocalArtifactToolResult("chat", "req", "malformed"))
            db.readableDatabase.rawQuery("SELECT metadata FROM local_artifact_intents WHERE call_id='malformed'", null).use {
                assertTrue(it.moveToFirst()); assertEquals("{broken-json", it.getString(0))
            }
        }
    }

    @Test fun intentAfterStopIsRejectedAndDeletedChatCannotResurrectAnArtifact() {
        val planned = intent()
        ChatDatabase(context).use { db ->
            begin(db)
            assertTrue(db.saveLocalArtifactIntentIfActive("chat", "req", callId, planned))
            LocalArtifactToolExecutor(context).createTextFile(args, key)
            assertTrue(db.stopActiveRequestOnce("chat", "req"))
            assertFalse(db.saveLocalArtifactIntentIfActive("chat", "req", callId, planned))
            db.deleteChat("chat")
        }
        ChatDatabase(context).use { db ->
            assertNull(db.getChat("chat"))
            assertNull(db.getLocalArtifactToolResult("chat", "req", callId))
            assertNull(db.getRequestRecoveryArchive("chat", "req"))
        }
    }
}
