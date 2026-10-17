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
import java.io.File
import java.util.Base64

/** Paid Stop artifacts remain owned by their explicit recovery archive, without a visible bubble. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RequestRecoveryArchiveOwnershipTest {
    private lateinit var context: Context
    private val chat = "archive-owner"
    private val request = "local_archive_owner"
    private val retainedFiles = mutableListOf<File>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    @After fun cleanup() {
        retainedFiles.forEach(File::delete)
        retainedFiles.clear()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    /** Uses a complete valid PNG and a paid file citation in an actual finished-before-commit cursor. */
    private fun stopWithPaidFiles(db: ChatDatabase): Set<String> {
        val pngBytes = TestPngFixtures.png(3, 2, 0x225fc7)
        val artifact = File.createTempFile("paid-report-", ".txt", context.filesDir).apply {
            writeText("Paid report from the interrupted response")
            retainedFiles += this
        }
        db.ensureChat(chat, "Archive ownership")
        db.upsertCurrentChat(chat, "Archive ownership", null, null, listOf(
            JSONObject().put("role", "user").put("request_id", request).put("text", "Make a report and an image")))
        db.setActiveResponse(chat, "resp_paid", request, "", "in_progress", System.currentTimeMillis(),
            "high", "high", false, 30, 0, true)
        assertTrue(db.startRequestAccounting(chat, request, "openai", "gpt-test", "test-slot"))
        val finalResponse = JSONObject().put("id", "resp_paid").put("status", "completed").put("model", "gpt-test")
            .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5))
            .put("output", JSONArray()
                .put(JSONObject().put("type", "image_generation_call")
                    .put("result", Base64.getEncoder().encodeToString(pngBytes)))
                .put(JSONObject().put("type", "message").put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Paid report")
                        .put("annotations", JSONArray().put(JSONObject().put("type", "container_file_citation")
                            .put("file_id", "file-paid-report").put("filename", artifact.name)
                            .put("local_path", artifact.absolutePath).put("state", "READY")))))))
        assertTrue(db.saveOpenAiToolCheckpoint(chat, request, JSONObject().put("version", 1)
            .put("phase", "finished").put("round", 1).put("final_response", finalResponse)
            .put("usage", finalResponse.getJSONObject("usage"))))

        assertTrue(db.stopActiveRequestOnce(chat, request))
        assertNull(db.getOpenAiToolCheckpoint(chat, request))
        assertNull(db.getActiveResponse(chat))
        assertEquals("Stop must not recreate its removed assistant bubble", 1,
            db.getChat(chat)!!.getJSONArray("messages").length())
        val archived = db.getRequestRecoveryArchive(chat, request)!!
        assertEquals("stopped", archived.getString("status"))
        assertEquals("Paid report", archived.getString("text"))
        val savedImage = archived.getJSONArray("generated_images").getJSONObject(0)
        assertEquals("READY", savedImage.getString("state"))
        val generated = File(savedImage.getString("local_path")).also { retainedFiles += it }
        assertArrayEquals("Archived paid image is a complete original PNG", pngBytes, generated.readBytes())
        assertEquals(artifact.absolutePath, archived.getJSONArray("annotations").getJSONObject(0).getString("local_path"))
        return setOf(artifact.absolutePath, generated.absolutePath)
    }

    @Test fun stopArchiveOwnsCitationAndGeneratedImageAcrossReopenUntilChatCascadeDeletion() {
        val paths = ChatDatabase(context).use(::stopWithPaidFiles)
        ChatDatabase(context).use { db ->
            assertNotNull(db.getRequestRecoveryArchive(chat, request))
            assertTrue(db.listLocalFilePaths(chat).containsAll(paths))
            assertTrue(db.listAllLocalFilePaths().containsAll(paths))
            db.deleteChat(chat)
            assertNull(db.getRequestRecoveryArchive(chat, request))
            assertTrue(db.listLocalFilePaths(chat).none { it in paths })
            assertTrue(db.listAllLocalFilePaths().none { it in paths })
        }
        ChatDatabase(context).use { db ->
            assertNull(db.getRequestRecoveryArchive(chat, request))
            assertTrue(db.listAllLocalFilePaths().none { it in paths })
        }
    }

    @Test fun deletingArchiveOwnerDoesNotReleaseAPathStillReferencedByAnotherChat() {
        val paths = ChatDatabase(context).use { db ->
            val owned = stopWithPaidFiles(db)
            val sharedPath = owned.first { it.endsWith(".png") }
            db.ensureChat("shared-owner", "Other chat")
            db.upsertCurrentChat("shared-owner", "Other chat", null, null, listOf(JSONObject()
                .put("role", "assistant").put("text", "Shared image reference")
                .put("generated_images", JSONArray().put(JSONObject().put("state", "READY")
                    .put("local_path", sharedPath).put("filename", File(sharedPath).name)))))
            owned
        }
        ChatDatabase(context).use { db ->
            val sharedPath = paths.first { it.endsWith(".png") }
            assertTrue(db.listLocalFilePaths(chat).containsAll(paths))
            assertTrue(db.listLocalFilePaths("shared-owner").contains(sharedPath))
            db.deleteChat(chat)
            assertNull(db.getRequestRecoveryArchive(chat, request))
            assertTrue(db.listAllLocalFilePaths().contains(sharedPath))
            assertTrue(db.listLocalFilePaths("shared-owner").contains(sharedPath))
            assertTrue(File(sharedPath).isFile)
            db.deleteChat("shared-owner")
            assertTrue(db.listAllLocalFilePaths().none { it in paths })
        }
    }
}
