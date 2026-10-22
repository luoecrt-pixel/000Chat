package com.example.openaichat

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalEvidencePanelLifecycleTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(panel: RichResponsePanel, ready: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < end && !ready()) {
            shadowOf(Looper.getMainLooper()).idle()
            panel.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            panel.layout(0, 0, 400, panel.measuredHeight.coerceAtLeast(1))
            CountDownLatch(1).await(5, TimeUnit.MILLISECONDS)
        }
        assertTrue("Evidence transition did not complete", ready())
    }
    private fun message() = JSONObject().put("status", "completed").put("role", "assistant")
        .put("text", "Итоги").put("tool_trace", JSONArray().put(JSONObject().put("name", "file_read_range")
            .put("ok", true).put("evidence", JSONObject().put("file_id", "file")
                .put("offset", 0).put("next_offset", 10).put("total_chars", 100).put("eof", false))))

    @Test fun detachedPendingDiscoveryReloadsOnTheSameCardAndKeepsBodyReady() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val oldRelease = CountDownLatch(1); val oldEntered = CountDownLatch(1)
        val newRelease = CountDownLatch(1); val newEntered = CountDownLatch(1)
        val calls = AtomicInteger()
        val file = File(controller.get().cacheDir, "evidence-reload.txt").apply { writeText("text") }
        try {
            val activity = controller.get()
            val panel = RichResponsePanel(activity, {}, {}, evidenceSources = {
                val number = calls.incrementAndGet()
                if (number == 1) {
                    oldEntered.countDown()
                    // A delayed callback can outlive cancellation; it must not own the reattached card.
                    while (oldRelease.count > 0) try { oldRelease.await() } catch (_: InterruptedException) { }
                } else { newEntered.countDown(); newRelease.await() }
                listOf(OpenRouterLocalAttachment("file", if (number == 1) "stale.csv" else "current.csv",
                    "text", file.path, "text/plain", file.length(), ""))
            }, evidenceEligible = true)
            activity.setContentView(panel)
            panel.submit(ChatBubbleItem.create("same-card", "Model", "Итоги", false, message = message()))
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
            assertFalse("Footer discovery must not hold an already parsed reading line", panel.isBodyLayoutPending())
            assertFalse(panel.isAsyncParsePending())
            assertTrue(panel.isEvidencePending())
            assertEquals("Итоги".length, panel.bodyCharacterCount())
            activity.setContentView(View(activity))
            activity.setContentView(panel)
            assertTrue("Same cached card must restart canceled discovery", newEntered.await(5, TimeUnit.SECONDS))
            assertFalse(panel.isBodyLayoutPending())
            assertFalse(panel.isAsyncParsePending())
            assertTrue(panel.isEvidencePending())
            newRelease.countDown()
            await(panel) { !panel.isEvidencePending() && views(panel).filterIsInstance<TextView>().any { it.text.toString().contains("current.csv") } }
            assertEquals("Footer labels must not count as body characters", "Итоги".length, panel.bodyCharacterCount())
            oldRelease.countDown()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(views(panel).filterIsInstance<TextView>().none { it.text.toString().contains("stale.csv") })
            assertEquals(2, calls.get())
        } finally {
            oldRelease.countDown(); newRelease.countDown(); controller.pause().stop().destroy(); file.delete()
        }
    }

    @Test fun evidenceDialogUsesSelectedThemeForInputAndChrome() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("app", 0)
        val oldTheme = prefs.getString("app_theme", null)
        val file = File(activity.cacheDir, "evidence-colors.txt").apply { writeText("source") }
        try {
            for (id in AppThemePalette.themes) {
                prefs.edit().putString("app_theme", id).commit()
                val panel = RichResponsePanel(activity, {}, {}, evidenceSources = {
                    listOf(OpenRouterLocalAttachment("file", "source.txt", "text", file.path, "text/plain", file.length(), ""))
                }, evidenceEligible = true)
                activity.setContentView(panel)
                panel.submit(ChatBubbleItem.create("theme-$id", "Model", "Итоги", false, message = message()))
                await(panel) { !panel.isEvidencePending() && views(panel).filterIsInstance<Button>().any { it.text.toString().contains("Проверить цитаты") } }
                views(panel).filterIsInstance<Button>().single { it.text.toString().contains("Проверить цитаты") }.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
                val theme = AppThemePalette.byId(id)
                val input = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
                assertEquals(theme.textPrimary, input.currentTextColor)
                assertEquals(theme.hintText, input.currentHintTextColor)
                val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                assertEquals(theme.textPrimary, positive.currentTextColor)
                positive.isEnabled = false
                assertEquals(theme.textSecondary, positive.currentTextColor)
                dialog.dismiss(); shadowOf(Looper.getMainLooper()).idle()
            }
        } finally {
            ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
            prefs.edit().apply { if (oldTheme == null) remove("app_theme") else putString("app_theme", oldTheme) }.commit()
            controller.pause().stop().destroy(); file.delete()
        }
    }

    @Test fun newSourceVersionRefreshesOnlyFooterAndRejectsOldPendingResult() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val activity = controller.get()
        val source = File(activity.cacheDir, "evidence-source-version.txt").apply { writeText("source") }
        try {
            val panel = RichResponsePanel(activity, {}, {}, evidenceSources = {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                listOf(OpenRouterLocalAttachment("file", "stale.csv", "text", source.path, "text/plain", source.length(), ""))
            }, evidenceEligible = true)
            activity.setContentView(panel)
            panel.submit(ChatBubbleItem.create("source-version", "Model", "Итоги", false, message = message()))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val body = views(panel).filterIsInstance<TextView>().first { it.text.toString() == "Итоги" }
            panel.setEvidenceSources(1L) {
                listOf(OpenRouterLocalAttachment("file", "current.csv", "text", source.path, "text/plain", source.length(), ""))
            }
            assertFalse(panel.isAsyncParsePending())
            assertSame(body, views(panel).filterIsInstance<TextView>().first { it.text.toString() == "Итоги" })
            await(panel) { !panel.isEvidencePending() && views(panel).filterIsInstance<TextView>()
                .any { it.text.toString().contains("current.csv") } }
            release.countDown(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(views(panel).filterIsInstance<TextView>().none { it.text.toString().contains("stale.csv") })
            assertSame(body, views(panel).filterIsInstance<TextView>().first { it.text.toString() == "Итоги" })
        } finally { release.countDown(); controller.pause().stop().destroy(); source.delete() }
    }
}
