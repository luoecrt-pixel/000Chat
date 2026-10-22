package com.example.openaichat

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.Call
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real Activity, application runtime, SQLite ownership and rendered reasoning panel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DeepSeekActivityIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private var controller: ActivityController<MainActivity>? = null
    private val clients = mutableListOf<HeldTransport>()

    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putBoolean("response_notifications", false).putString("provider", "deepseek").commit()
        ChatMessageAdapter.diffExecutorForTests = java.util.concurrent.Executor { it.run() }
        try { androidx.work.WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
    }

    @After fun after() {
        clients.forEach { it.release.countDown() }
        clients.forEach { assertTrue(it.closed.await(10, TimeUnit.SECONDS)) }
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
        ChatMessageAdapter.diffExecutorForTests = null
        prefs.edit().clear().commit()
    }

    private fun adapter(activity: MainActivity = controller!!.get()): ChatMessageAdapter =
        MainActivity::class.java.getDeclaredField("messageAdapter").apply { isAccessible = true }
            .get(activity) as ChatMessageAdapter

    private fun activeItem(activity: MainActivity = controller!!.get()) = adapter(activity).snapshot().last { !it.user }

    private fun start(requestId: String): Pair<String, HeldTransport> {
        val chatId = controller!!.get().currentChatIdForTesting()
        ChatDatabase(context).use { db ->
            db.startRequestAccounting(chatId, requestId, "deepseek", "deepseek-flash", "deepseek_api_key")
            db.setActiveResponse(chatId, null, requestId, "", "in_progress", System.currentTimeMillis(),
                "high", "high", false, 4, 0, true)
        }
        val client = HeldTransport().also(clients::add)
        OpenRouterResponseRuntime.start(context, OpenRouterRuntimeRequest(
            chatId = chatId, requestId = requestId, model = "deepseek-flash",
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", "test")),
            attachments = emptyList(), effort = "high", verbosity = "medium", maxTokens = null,
            github = false, githubRepo = "", githubBranches = emptySet(), webSearch = false, webFetch = false,
            parallel = false, cacheMode = "off", background = false, startedAt = SystemClock.elapsedRealtime(),
            taskClass = "test", effortPref = "high", autoSelected = false, promptChars = 4,
            apiKey = "inert-deepseek-test-key", metadataClient = OkHttpClient(), pdfEngine = "auto", provider = "deepseek"
        ), client)
        assertTrue("Runtime must reach the held transport", client.ready.await(10, TimeUnit.SECONDS))
        controller!!.get().responseChanged(chatId, requestId)
        shadowOf(Looper.getMainLooper()).idle()
        return chatId to client
    }

    @Test fun firstReasoningMountsPanelAndLatestSurvivesTextChangesAndRecreation() {
        val (chatId, client) = start("reasoning-request")
        assertNull(activeItem().reasoningSummary)
        client.reasoning("first")
        OpenRouterUiBridge.changed(chatId, "reasoning-request")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(121))
        val first = activeItem()
        assertEquals("first", first.reasoningSummary)
        val card = LinearLayout(controller!!.get())
        MainActivity::class.java.getDeclaredMethod("bindChatBubbleCard", LinearLayout::class.java, ChatBubbleItem::class.java)
            .apply { isAccessible = true }.invoke(controller!!.get(), card, first)
        assertEquals("first", card.findViewWithTag<TextView>("reasoning-summary-body").text.toString())

        repeat(50) { client.reasoning("reasoning-${it + 1}") }
        client.text("visible text")
        OpenRouterUiBridge.changed(chatId, "reasoning-request")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(121))
        assertEquals("reasoning-50", activeItem().reasoningSummary)

        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        assertEquals("Recreation must leave the runtime owner alive", 0, client.closeCount.get())
        controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("reasoning-50", activeItem().reasoningSummary)
        client.release.countDown()
        assertTrue(client.closed.await(10, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, client.closeCount.get())
        val saved = ChatDatabase(context).use { it.getChat(chatId)!!.getJSONArray("messages") }
        assertEquals("reasoning-50", saved.getJSONObject(saved.length() - 1).getString("reasoning_summary"))
    }

    @Test fun staleReasoningCannotUpdateAnotherRequestOrAnotherChat() {
        val (chatId, client) = start("current-request")
        client.reasoning("current reasoning")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(121))
        controller!!.get().reasoningChanged(chatId, "old-request", "stale")
        controller!!.get().reasoningChanged("another-chat", "current-request", "foreign")
        assertEquals("current reasoning", activeItem().reasoningSummary)
        val other = "another-chat"
        ChatDatabase(context).use { it.ensureChat(other, "Other") }
        prefs.edit().putString("current_chat", other).commit()
        controller!!.get().reasoningChanged(chatId, "current-request", "late after switch")
        assertEquals("current reasoning", activeItem().reasoningSummary)
        prefs.edit().putString("current_chat", chatId).commit()
    }

    @Test fun returningToSameActivityRestoresReasoningReceivedWhileHidden() {
        val (_, client) = start("background-reasoning")
        controller!!.pause().stop()
        client.reasoning("received in background without text")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(121))
        assertNull(activeItem().reasoningSummary)
        assertEquals(0, client.closeCount.get())
        controller!!.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("received in background without text", activeItem().reasoningSummary)
    }

    private class HeldTransport : ChatCompletionTransport {
        override val provider = "deepseek"
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger()
        @Volatile var reasoning: (String) -> Unit = {}
        @Volatile var text: (String) -> Unit = {}
        @Volatile var latest = ""
        override fun complete(apiKey: String, model: String, messages: JSONArray, sessionId: String?, tools: JSONArray?,
            maxTokens: Int?, reasoningEffort: String?, verbosity: String?, parallelToolCalls: Boolean,
            executeTool: (String, JSONObject) -> String, onCallCreated: (Call) -> Unit, shouldContinue: () -> Boolean,
            onRoundObserved: (Int, String?, JSONObject?) -> Unit, onTextDelta: (String) -> Unit,
            onPayloadObserved: (Int, Int) -> Unit, cacheMode: String, onProtocolWarning: (String) -> Unit,
            onProtocolCheckpoint: (JSONObject) -> Unit, pdfEngine: String?, responseFormat: JSONObject?,
            claudeRoute: OpenRouterClaudeRoute, onFileIoObserved: (Int, JSONObject) -> Unit,
            onReasoningDelta: (String) -> Unit): OpenRouterCompletionResult {
            reasoning = { latest = it; onReasoningDelta(it) }
            text = onTextDelta
            ready.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "Test did not release held inference" }
            val usage = JSONObject().put("prompt_tokens", 4).put("completion_tokens", 3).put("usage_complete", true)
            onRoundObserved(0, null, usage)
            return OpenRouterCompletionResult("answer", usage, model,
                JSONObject().put("role", "assistant").put("reasoning_summary", latest), emptyList(), "stop",
                protocol = JSONObject().put("version", 1).put("replayable", true).put("tail", JSONArray()
                    .put(JSONObject().put("role", "assistant").put("content", "answer").put("reasoning_content", latest))),
                roundsCompleted = 1, generationComplete = true, usageComplete = true, replayable = true)
        }
        override fun close() { closeCount.incrementAndGet(); closed.countDown() }
    }
}
