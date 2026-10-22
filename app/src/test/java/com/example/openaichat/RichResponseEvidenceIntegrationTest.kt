package com.example.openaichat

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class RichResponseEvidenceIntegrationTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun item(status: String = "completed") = ChatBubbleItem.create("evidence-final", "OpenAI", "Ответ", false,
        message = JSONObject().put("role", "assistant").put("status", status).put("text", "Ответ"))
    private fun layout(panel: RichResponsePanel) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, 400, panel.measuredHeight.coerceAtLeast(1))
    }
    private fun await(panel: RichResponsePanel, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val tick = CountDownLatch(1)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle(); layout(panel)
            if (condition()) return
            tick.await(5, TimeUnit.MILLISECONDS)
        }
        fail("Evidence transition did not finish")
    }

    @Test fun blockedEligibleEvidenceNeverDelaysBaseReadinessAndNotifiesLateGeometry() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val offMain = AtomicBoolean(false); val commits = AtomicInteger()
        val panel = RichResponsePanel(controller.get(), {}, {}, onAsyncLayoutCommitted = { commits.incrementAndGet() },
            evidenceSources = { offMain.set(Looper.myLooper() != Looper.getMainLooper()); entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS)); emptyList() }, evidenceEligible = true)
        try {
            controller.get().setContentView(panel)
            panel.submit(item())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            layout(panel); shadowOf(Looper.getMainLooper()).idle(); layout(panel)
            assertTrue(offMain.get())
            assertTrue(panel.isEvidencePending())
            assertFalse("Base response and FOLLOW_LATEST may use measured text immediately", panel.isAsyncParsePending())
            val before = commits.get()
            release.countDown()
            await(panel) { !panel.isEvidencePending() && commits.get() > before }
            assertFalse(panel.isAsyncParsePending())
        } finally { release.countDown(); controller.pause().stop().destroy() }
    }

    @Test fun noFileEligibilityStartsNoDiscoveryEvenForCompletedResponse() {
        val calls = AtomicInteger()
        val panel = RichResponsePanel(RuntimeEnvironment.getApplication(), {}, {}, evidenceSources = {
            calls.incrementAndGet(); emptyList()
        })
        panel.submit(item())
        assertTrue(descendants(panel).filterIsInstance<LocalEvidencePanel>().isEmpty())
        assertEquals(0, calls.get())
        assertFalse(panel.isEvidencePending())
        assertFalse(panel.isAsyncParsePending())
    }

    @Test fun traceOnlyPartialReadStillShowsCoverageWithoutRemainingFileReferences() {
        val panel = RichResponsePanel(RuntimeEnvironment.getApplication(), {}, {}, evidenceSources = { emptyList() })
        val message = JSONObject().put("role", "assistant").put("status", "completed")
            .put("tool_trace", JSONArray().put(JSONObject().put("name", "file_read_range").put("ok", true)
                .put("evidence", JSONObject().put("file_id", "missing-source").put("offset", 0)
                    .put("next_offset", 10).put("total_chars", 100).put("eof", false))))
        panel.submit(ChatBubbleItem.create("trace-only", "OpenRouter", "Ответ", false, message = message))
        panel.setEvidenceEligible(true)
        panel.setEvidenceEligible(false)
        assertEquals(1, descendants(panel).filterIsInstance<LocalEvidencePanel>().size)
        assertFalse(panel.isAsyncParsePending())
        await(panel) { !panel.isEvidencePending() && descendants(panel).filterIsInstance<TextView>()
            .any { it.text.toString().contains("прочитана только часть (10%)") } }
    }

    @Test fun lateEligibilityAddsOnlyOptionalFooterAndPreservesNativeBodyIdentity() {
        val panel = RichResponsePanel(RuntimeEnvironment.getApplication(), {}, {}, evidenceSources = { emptyList() })
        panel.submit(item())
        val body = descendants(panel).filterIsInstance<TextView>().first { it.text.toString() == "Ответ" }
        panel.setEvidenceEligible(true)
        assertEquals(1, descendants(panel).filterIsInstance<LocalEvidencePanel>().size)
        assertSame(body, descendants(panel).filterIsInstance<TextView>().first { it.text.toString() == "Ответ" })
        assertFalse(panel.isAsyncParsePending())
        panel.setEvidenceEligible(false)
        assertTrue(descendants(panel).filterIsInstance<LocalEvidencePanel>().isEmpty())
        assertSame(body, descendants(panel).filterIsInstance<TextView>().first { it.text.toString() == "Ответ" })
        layout(panel); shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun unfinishedResponsesDoNotStartEvidenceWorkEvenWhenFilesExist() {
        val called = AtomicBoolean(false)
        val panel = RichResponsePanel(RuntimeEnvironment.getApplication(), {}, {}, evidenceSources = {
            called.set(true); emptyList()
        }, evidenceEligible = true)
        panel.submit(item("in_progress"))
        assertTrue(descendants(panel).filterIsInstance<LocalEvidencePanel>().isEmpty())
        assertFalse(called.get())
    }
}
