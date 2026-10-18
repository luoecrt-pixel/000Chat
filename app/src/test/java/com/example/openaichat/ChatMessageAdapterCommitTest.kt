package com.example.openaichat

import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
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
