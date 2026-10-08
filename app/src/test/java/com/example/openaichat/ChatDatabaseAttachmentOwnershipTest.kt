package com.example.openaichat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatDatabaseAttachmentOwnershipTest {
    private lateinit var context: Context
    private lateinit var database: ChatDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("astra_chats.db")
        database = ChatDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("astra_chats.db")
    }

    private fun manifest(wire: String) = JSONArray().put(
        JSONObject()
            .put("path", "/app/attachments/original.jpg")
            .put("wire_path", wire)
            .put("kind", "image")
    )

    private fun message(wire: String) = JSONObject()
        .put("role", "user")
        .put("text", "Describe attached photo")
        .put("attachments", manifest(wire))

    @Test
    fun storedMessageProtectsWirePathGloballyAndPerChatAcrossReopen() {
        database.upsertCurrentChat("ownership-a", "Photographs", null, null, listOf(message("/app/attachments/wire_A.jpg")))
        database.upsertCurrentChat("ownership-b", "Other", null, null, emptyList())
        assertTrue("/app/attachments/wire_A.jpg" in database.listAllLocalFilePaths())
        assertTrue("/app/attachments/wire_A.jpg" in database.listLocalFilePaths("ownership-a"))
        assertFalse("/app/attachments/wire_A.jpg" in database.listLocalFilePaths("ownership-b"))

        database.close()
        database = ChatDatabase(context)
        assertTrue("/app/attachments/wire_A.jpg" in database.listAllLocalFilePaths())
        val loaded = database.getChat("ownership-a")
        assertNotNull(loaded)
        assertEquals(
            "/app/attachments/wire_A.jpg",
            loaded!!.getJSONArray("messages").getJSONObject(0)
                .getJSONArray("attachments").getJSONObject(0).getString("wire_path")
        )
    }

    @Test
    fun replacingOnlyAttachmentManifestUpdatesSQLiteAndPathOwners() {
        val chatId = "attachment-fingerprint"
        database.upsertCurrentChat(chatId, "Attachments", null, null, listOf(message("/app/attachments/first-wire.jpg")))
        database.upsertCurrentChat(chatId, "Attachments", null, null, listOf(message("/app/attachments/second-wire.jpg")))
        database.close()
        database = ChatDatabase(context)

        val persisted = database.getChat(chatId)!!.getJSONArray("messages")
            .getJSONObject(0).getJSONArray("attachments").getJSONObject(0)
        assertEquals("/app/attachments/second-wire.jpg", persisted.getString("wire_path"))
        assertTrue("/app/attachments/second-wire.jpg" in database.listAllLocalFilePaths())
        assertFalse("/app/attachments/first-wire.jpg" in database.listAllLocalFilePaths())
    }
}
