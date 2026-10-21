package com.example.openaichat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ContextInspectorActivityIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var activity: MainActivity
    private val files = mutableListOf<File>()

    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putString("provider", "openai").putString("model", "gpt-6-astra")
            .putString("current_chat", "inspector-chat").putBoolean("response_notifications", false)
            .putBoolean("file_analysis_tools", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        try { androidx.work.WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
    }
    @After fun after() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        files.forEach { it.delete() }
        prefs.edit().clear().commit()
    }
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun start(filename: String, mime: String) {
        val file = File(context.filesDir, "inspector-$filename").apply { writeText("synthetic source") }
        files += file
        val user = JSONObject().put("role", "user").put("text", "saved")
            .put("file_paths", JSONArray().put(file.path)).put("file_names", JSONArray().put(filename))
            .put("file_mimes", JSONArray().put(mime))
        ChatDatabase(context).use { it.upsertCurrentChat("inspector-chat", "Inspector", null, null, listOf(user)) }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller!!.get()
    }
    private fun inspect(): String {
        ShadowDialog.getLatestDialog()?.dismiss()
        val shown = CountDownLatch(1)
        MainActivity::class.java.getDeclaredField("contextInspectionCompletedHook").apply { isAccessible = true }
            .set(activity, { shown.countDown() })
        activity.window.decorView.findViewWithTag<TextView>("context-status-badge").performClick()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (shown.count > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            shown.await(5, TimeUnit.MILLISECONDS)
        }
        assertEquals("Inspection callback must correspond to an installed dialog", 0L, shown.count)
        return ShadowDialog.getLatestDialog().findViewById<android.view.View>(android.R.id.content)
            .findViewWithTag<TextView>("context-inspector-details").text.toString()
    }

    @Test fun pdfOnlyHistoryDoesNotAdvertiseLocalTextToolsOrMaterializeNewFiles() {
        start("document.pdf", "application/pdf")
        val before = context.filesDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet()
        val details = inspect()
        assertTrue(details.contains("document.pdf"))
        assertFalse(details.contains("read_attached_file"))
        assertFalse(details.contains("list_attached_files"))
        assertEquals(before, context.filesDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet())
    }

    @Test fun savedTextUsesLocalToolsAndInspectionCapturesCurrentOptionsAndDraft() {
        start("data.txt", "text/plain")
        assertTrue(inspect().contains("read_attached_file"))
        prefs.edit().putBoolean("web", true).putBoolean("file_search", true).putString("vector_store", "vs-captured").commit()
        (field("input") as EditText).setText("new draft")
        val details = inspect()
        assertTrue(details.contains("web_search"))
        assertTrue(details.contains("file_search"))
        assertTrue(details.contains("read_attached_file"))
        assertTrue("Current draft must be part of the captured next request", details.contains("История: 2 сообщений"))
        assertEquals("new draft", (field("input") as EditText).text.toString())
        prefs.edit().putBoolean("web", false).putString("vector_store", "").commit()
        val next = inspect()
        assertFalse(next.contains("web_search"))
        assertFalse(next.lineSequence().first { it.startsWith("Инструменты:") }.contains("file_search"))
    }

    @Test fun delayedInspectorFromPreviousChatCannotOpenAfterSwitch() {
        start("data.txt", "text/plain")
        ShadowDialog.getLatestDialog()?.dismiss()
        ChatDatabase(context).use { it.upsertCurrentChat("other-chat", "Other", null, null,
            listOf(JSONObject().put("role", "user").put("text", "other conversation"))) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val shown = CountDownLatch(1)
        val preparation = MainActivity::class.java.getDeclaredField("contextInspectionPreparationHook").apply { isAccessible = true }
        preparation.set(activity, { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) })
        MainActivity::class.java.getDeclaredField("contextInspectionCompletedHook").apply { isAccessible = true }
            .set(activity, { shown.countDown() })
        try {
            activity.window.decorView.findViewWithTag<TextView>("context-status-badge").performClick()
            assertTrue("Old inspection must be blocked before switching", entered.await(5, TimeUnit.SECONDS))
            MainActivity::class.java.getDeclaredMethod("switchChat", String::class.java).apply { isAccessible = true }
                .invoke(activity, "other-chat")
            assertEquals("other-chat", prefs.getString("current_chat", null))
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (field("contextDialogPending") == true && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                shown.await(5, TimeUnit.MILLISECONDS)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Old request must release its pending state", false, field("contextDialogPending"))
            assertEquals("A stale result must not display an inspector", 1L, shown.count)
            assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
            assertEquals("other-chat", prefs.getString("current_chat", null))
        } finally {
            release.countDown()
            preparation.set(activity, null)
        }
    }
}
