package com.example.openaichat

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityNoToolRecoveryTest {
    private var controller: org.robolectric.android.controller.ActivityController<MainActivity>? = null

    @Before fun before() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("response_notifications", false).commit()
        app.deleteDatabase("astra_chats.db")
        try { androidx.work.WorkManager.getInstance(app) } catch (_: IllegalStateException) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
    }

    @After fun after() { controller?.destroy() }

    @Test fun resumedActivityFinalizerRetainsFlatPaidTextWhenTerminalOmitsTypedOutput() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().also { controller = it }.get()
        val db = MainActivity::class.java.getDeclaredField("chatDb").apply { isAccessible = true }.get(activity) as ChatDatabase
        val chatId = "no-tool-recovery"
        val requestId = "no-tool-request"
        val responseId = "resp_no_tool"
        val text = "Сохранённый оплаченный ответ 😀"
        val terminal = JSONObject().put("id", responseId).put("status", "completed")
            .put("usage", JSONObject().put("input_tokens", 3).put("output_tokens", 5))
        val state = ResponseEventState(responseId = responseId, text = text, unindexedText = text,
            terminalState = ResponseTerminalState.COMPLETED, terminalResponse = terminal)
        db.upsertCurrentChat(chatId, "No tools", null, null, listOf(JSONObject().put("role", "user")
            .put("text", "Question").put("provider", "openai").put("request_id", requestId)))
        db.setActiveResponse(chatId, responseId, requestId, "", "in_progress", System.currentTimeMillis(),
            "high", "high", false, 8, 0, true)
        db.updateActiveResponse(chatId, responseId, text, "completed", 1, requestId,
            ResponseIndexedCheckpoint.serialize(state))
        assertEquals(text, ResponseIndexedCheckpoint.restore(db.getActiveResponse(chatId)!!).text)
        assertNull(db.getOpenAiToolCheckpoint(chatId, requestId))
        MainActivity::class.java.getDeclaredMethod("finalizeBackgroundResponse", JSONObject::class.java,
            JSONObject::class.java, BackgroundResponseUiSnapshot::class.java).apply { isAccessible = true }
            .invoke(activity, db.getActiveResponse(chatId)!!, terminal, null)
        assertNull(db.getActiveResponse(chatId))
        ChatDatabase(activity).use { reopened ->
            val assistant = reopened.getChat(chatId)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals(text, assistant.getString("text"))
            assertEquals("completed", assistant.getString("status"))
        }
    }
}
