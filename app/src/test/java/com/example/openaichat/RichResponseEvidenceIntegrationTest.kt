package com.example.openaichat

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class RichResponseEvidenceIntegrationTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @Test fun completedEvidenceLoadsOffMainAndParticipatesInMeasuredLayoutReadiness() {
        val offMain = AtomicBoolean(false)
        val panel = RichResponsePanel(RuntimeEnvironment.getApplication(), {}, {}, evidenceSources = {
            offMain.set(Looper.myLooper() != Looper.getMainLooper()); emptyList()
        })
        val message = JSONObject().put("role", "assistant").put("status", "completed").put("content", "Ответ")
        panel.submit(ChatBubbleItem.create("evidence-final", "OpenAI", "Ответ", false, message = message))
        assertEquals(1, descendants(panel).filterIsInstance<LocalEvidencePanel>().size)
        assertTrue(panel.isAsyncParsePending())
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline && panel.isAsyncParsePending()) {
            shadowOf(Looper.getMainLooper()).idle()
            panel.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            panel.layout(0, 0, 400, panel.measuredHeight.coerceAtLeast(1))
            Thread.sleep(5)
        }
        assertTrue(offMain.get())
        assertFalse(panel.isAsyncParsePending())
    }
    @Test fun unfinishedResponsesDoNotStartEvidenceWork() {
        val called = AtomicBoolean(false)
        val panel = RichResponsePanel(RuntimeEnvironment.getApplication(), {}, {}, evidenceSources = {
            called.set(true); emptyList()
        })
        panel.submit(ChatBubbleItem.create("evidence-stream", "Claude", "Часть", false,
            message = JSONObject().put("role", "assistant").put("status", "in_progress")))
        assertTrue(descendants(panel).filterIsInstance<LocalEvidencePanel>().isEmpty())
        assertFalse(called.get())
    }
}
