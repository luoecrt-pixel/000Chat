package com.example.openaichat

import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.json.JSONObject
import org.json.JSONArray
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ChatMessageAdapterCommitTest {
    @After fun resetExecutor() { ChatMessageAdapter.diffExecutorForTests = null }

    @Test fun committedOrderUsesTheLatestPayloadAndSupersededSubmissionDoesNotCommitEarly() {
        val tasks = java.util.ArrayDeque<Runnable>()
        ChatMessageAdapter.diffExecutorForTests = java.util.concurrent.Executor { tasks.addLast(it) }
        val context = RuntimeEnvironment.getApplication()
        val adapter = ChatMessageAdapter({ LinearLayout(context) }, { _, _ -> TextView(context) }, { it.text })
        val first = ChatBubbleItem.create("first", "Assistant", "Before", false)
        adapter.submitItems(listOf(first))
        assertTrue(adapter.updateText("first", "Latest payload"))
        assertFalse(adapter.isListCommitPending())
        assertEquals("Latest payload", adapter.committedSnapshot().single().text)
        assertEquals(adapter.snapshot().single().contentSignature, adapter.committedSnapshot().single().contentSignature)
        var staleCommits = 0
        var latestCommits = 0
        adapter.addItem(ChatBubbleItem.create("second", "User", "Question", true)) { staleCommits++ }
        adapter.addItem(ChatBubbleItem.create("third", "Assistant", "Answer", false)) { latestCommits++ }
        assertEquals(2, tasks.size)
        tasks.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(adapter.isListCommitPending())
        assertEquals(1, adapter.itemCount)
        assertEquals(0, staleCommits)
        tasks.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(adapter.isListCommitPending())
        assertEquals(3, adapter.itemCount)
        assertEquals(0, staleCommits)
        assertEquals(1, latestCommits)
        assertEquals("Latest payload", adapter.committedSnapshot().first().text)
    }

    @Test fun textDeltasDoNotSerializeProtocolAndKeepPreviousSnapshotsImmutable() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = ChatMessageAdapter({ LinearLayout(context) }, { _, _ -> TextView(context) }, { it.text })
        var serializations = 0
        val opaque = object {
            override fun toString(): String {
                serializations++
                return "signed-thinking-data"
            }
        }
        val message = JSONObject().put("role", "assistant").put("text", "start")
            .put("openrouter_protocol", JSONObject().put("tail", JSONArray().put(
                JSONObject().put("reasoning_details", JSONArray().put(JSONObject().put("data", opaque))))))
        val initial = ChatBubbleItem.create("answer", "Assistant", "start", false, message = message)
        adapter.submitItems(listOf(initial))
        val initialSerializations = serializations
        assertTrue("Full card creation must fingerprint protocol metadata", initialSerializations > 0)

        assertTrue(adapter.updateText("answer", "first delta"))
        val firstDelta = adapter.snapshot().single()
        assertEquals(initialSerializations, serializations)
        assertEquals("start", initial.message!!.getString("text"))
        assertEquals("first delta", firstDelta.message!!.getString("text"))
        assertEquals("first delta", adapter.committedSnapshot().single().text)
        assertTrue(adapter.updateText("answer", "second delta"))
        assertEquals(initialSerializations, serializations)
        assertEquals("first delta", firstDelta.message!!.getString("text"))
        assertTrue(adapter.updateText("answer", "first delta"))
        assertEquals("Same base and text must have the same identity", firstDelta.contentSignature,
            adapter.snapshot().single().contentSignature)
        val current = adapter.snapshot().single()
        assertTrue(adapter.updateText("answer", "first delta"))
        assertTrue("Repeated payload must keep the same snapshot", current === adapter.snapshot().single())

        val terminal = JsonProtocol.objectCopy(message).put("text", "done").put("status", "completed")
        val finalCard = ChatBubbleItem.create("answer", "Assistant", "done", false, message = terminal)
        assertTrue("Terminal card still fingerprints full protocol", serializations > initialSerializations)
        adapter.replaceItem("answer", finalCard)
        assertEquals(ChatMessageFingerprint.of(terminal), adapter.snapshot().single().contentSignature)
        assertEquals(null, adapter.snapshot().single().streamingBaseSignature)
    }

    @Test
    fun structuralCommitSeparatesQueuedModelFromDisplayedItems() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = ChatMessageAdapter(
            createCard = { LinearLayout(context) },
            bindCard = { _, _ -> TextView(context) },
            renderBody = { it.text }
        )
        val first = ChatBubbleItem.create("first", "User", "Question", true)
        val second = ChatBubbleItem.create("second", "Assistant", "Answer", false)
        adapter.submitItems(listOf(first))
        assertFalse(adapter.isListCommitPending())
        var committed = false
        adapter.addItem(second) { committed = true }
        assertEquals(listOf(first, second), adapter.snapshot())
        assertEquals(listOf(first), adapter.committedSnapshot())
        assertEquals(1, adapter.itemCount)
        assertTrue(adapter.isListCommitPending())
        assertFalse(committed)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!committed && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!committed) Thread.sleep(5)
        }
        assertTrue("Queued structural list must commit", committed)
        assertFalse(adapter.isListCommitPending())
        assertEquals(listOf(first, second), adapter.committedSnapshot())
        assertEquals(2, adapter.itemCount)
    }
}
