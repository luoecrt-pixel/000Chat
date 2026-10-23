package com.example.openaichat

import android.os.Looper
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DeepSeekUiBridgeTest {
    @Before fun resetStaticBridge() = resetOpenRouterUiBridgeForTest()
    private class RecordingHost : OpenRouterUiBridge.Host {
        val events = mutableListOf<String>()
        override fun responseChanged(chatId: String, requestId: String) { events += "text:$chatId:$requestId" }
        override fun reasoningChanged(chatId: String, requestId: String, reasoning: String) {
            events += "reasoning:$chatId:$requestId:$reasoning"
        }
        override fun protocolWarning(chatId: String, requestId: String, warning: String) {}
        override fun confirmTool(chatId: String, requestId: String, name: String, arguments: JSONObject) = false
        override fun isViewing(chatId: String) = true
        override fun ownsClient(client: ChatCompletionTransport) = false
    }

    private val attached = mutableListOf<RecordingHost>()
    private fun attach() = RecordingHost().also { attached += it; OpenRouterUiBridge.attach(it) }

    @After fun cleanup() {
        listOf("request", "old", "new", "absent").forEach { OpenRouterUiBridge.cancelReasoning("chat", it) }
        OpenRouterUiBridge.cancelReasoning("chat|a", "b")
        OpenRouterUiBridge.cancelReasoning("chat", "a|b")
        attached.forEach(OpenRouterUiBridge::detach)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(240))
    }

    @Test fun textNotificationCannotConsumeTheFinalQueuedReasoning() {
        val host = attach()
        OpenRouterUiBridge.beginReasoning("chat", "request")
        OpenRouterUiBridge.reasoning("chat", "request", "last reasoning")
        OpenRouterUiBridge.changed("chat", "request")
        OpenRouterUiBridge.finishReasoning("chat", "request", "last reasoning")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(240))
        assertEquals(listOf("reasoning:chat:request:last reasoning", "text:chat:request", "text:chat:request"), host.events)
        OpenRouterUiBridge.reasoning("chat", "request", "late old delta")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(240))
        assertEquals(3, host.events.size)
    }

    @Test fun fiftyFastDeltasCoalesceAndRespectThe120MillisecondInterval() {
        val host = attach()
        OpenRouterUiBridge.beginReasoning("chat", "request")
        OpenRouterUiBridge.reasoning("chat", "request", "first")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("reasoning:chat:request:first"), host.events)
        repeat(50) { OpenRouterUiBridge.reasoning("chat", "request", "delta $it") }
        OpenRouterUiBridge.changed("chat", "request")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(119))
        assertEquals(1, host.events.count { it.startsWith("reasoning:") })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(listOf("reasoning:chat:request:first", "reasoning:chat:request:delta 49"),
            host.events.filter { it.startsWith("reasoning:") })
    }

    @Test fun cancelRejectsQueuedAndLateOldDeltasWithoutAffectingTheSuccessor() {
        val host = attach()
        OpenRouterUiBridge.beginReasoning("chat", "old")
        OpenRouterUiBridge.reasoning("chat", "old", "old")
        OpenRouterUiBridge.cancelReasoning("chat", "old")
        OpenRouterUiBridge.beginReasoning("chat", "new")
        OpenRouterUiBridge.reasoning("chat", "old", "late old")
        OpenRouterUiBridge.reasoning("chat", "new", "new")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(240))
        assertEquals(listOf("reasoning:chat:new:new"), host.events)
    }

    @Test fun detachedHostDoesNotLosePendingTerminalSnapshotAndCannotDetachItsReplacement() {
        val old = attach()
        OpenRouterUiBridge.beginReasoning("chat", "absent")
        OpenRouterUiBridge.reasoning("chat", "absent", "survives recreation")
        OpenRouterUiBridge.detach(old)
        OpenRouterUiBridge.finishReasoning("chat", "absent", "survives recreation")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(240))
        assertTrue(old.events.isEmpty())
        val replacement = attach()
        OpenRouterUiBridge.detach(old)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(240))
        assertEquals(listOf("reasoning:chat:absent:survives recreation", "text:chat:absent"), replacement.events)
    }

    @Test fun chatAndRequestAreSeparateIdentityFields() {
        val host = attach()
        OpenRouterUiBridge.beginReasoning("chat|a", "b")
        OpenRouterUiBridge.beginReasoning("chat", "a|b")
        OpenRouterUiBridge.reasoning("chat|a", "b", "one")
        OpenRouterUiBridge.reasoning("chat", "a|b", "two")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(240))
        assertEquals(setOf("reasoning:chat|a:b:one", "reasoning:chat:a|b:two"), host.events.toSet())
    }
}

/** Robolectric resets the main queue/clock, while application Kotlin singletons can survive. */
internal fun resetOpenRouterUiBridgeForTest() {
    fun field(name: String) = OpenRouterUiBridge::class.java.getDeclaredField(name).apply { isAccessible = true }
    val lock = field("reasoningLock").get(OpenRouterUiBridge)
    synchronized(lock) {
        val handler = field("handler").get(OpenRouterUiBridge) as android.os.Handler
        handler.removeCallbacks(field("flushReasoning").get(OpenRouterUiBridge) as Runnable)
        (field("reasoningStates").get(OpenRouterUiBridge) as MutableMap<*, *>).clear()
        field("reasoningFlushScheduled").setBoolean(OpenRouterUiBridge, false)
        field("lastReasoningFlushUptimeMs").setLong(OpenRouterUiBridge, -120L)
        field("host").set(OpenRouterUiBridge, java.lang.ref.WeakReference<OpenRouterUiBridge.Host>(null))
    }
}
