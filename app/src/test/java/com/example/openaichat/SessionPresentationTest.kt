package com.example.openaichat

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
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
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SessionPresentationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var activity: MainActivity
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun call(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun preview() = field("requestTelemetrySummary") as TextView
    private fun history() = field("requestTelemetryHistory") as ViewGroup
    private val chatId get() = prefs.getString("current_chat", null)!!
    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        try { androidx.work.WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        activity = Robolectric.buildActivity(MainActivity::class.java).create().also { controller = it }.get()
    }
    @After fun after() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.destroy()
        prefs.edit().clear().commit()
    }
    private fun event(db: ChatDatabase, request: String, state: String, cost: Double? = null) {
        db.recordRequestEvent(chatId, "request_$state", request, "openrouter", "anthropic/claude-sonnet-5.5",
            "anthropic/claude-sonnet-5.5", ModelCatalog.Match.EXACT, state,
            inputTokens = 123, outputTokens = 45, reportedCostUsd = cost,
            error = if (state == "failed") "provider rejected request" else null)
    }

    @Test fun activePreviewBecomesAccountingAndExpandedLogContainsLatestOnlyAtBottom() {
        ChatDatabase(context).use { db ->
            db.startRequestAccounting(chatId, "r", "openrouter", "anthropic/claude-sonnet-5.5", "test")
            db.setActiveResponse(chatId, null, "r", "", "in_progress", System.currentTimeMillis(),
                "high", "high", false, 0, 0, false)
            call("renderRequestTelemetry")
            assertTrue(preview().text.contains("Модель работает"))
            assertTrue(preview().text.contains("Claude Sonnet"))
            assertTrue(preview().text.contains("Расход неизвестен"))
            assertFalse(preview().text.contains("0.0000"))
            event(db, "old", "completed", .01)
            // A terminal event alone cannot manufacture a confirmed charge. Follow
            // the runtime round-ledger/finalizer path before asserting final cost.
            val usage = JSONObject().put("prompt_tokens", 123).put("completion_tokens", 45).put("cost", .02)
            assertTrue(db.observeOpenRouterRound(chatId, "r", 0, "gen-r", usage))
            assertTrue(db.finalizeCurrentChatIfActiveRequest(chatId, "", null, null,
                listOf(JSONObject().put("role", "assistant").put("provider", "openrouter")
                    .put("request_id", "r").put("status", "completed").put("text", "done").put("usage", usage)), "r"))
            event(db, "r", "completed", .02)
            call("renderRequestTelemetry")
            val terminalSummary = preview().text.toString().removeSuffix("  ▾")
            assertTrue(terminalSummary.contains("Расход $0.0200"))
            assertFalse(terminalSummary.contains("работает"))
            preview().performClick()
            assertTrue(preview().text.startsWith("Журнал запросов"))
            assertFalse(preview().text.contains("$0.0200"))
            val rows = views(history()).filter { it.tag == "request-log-event" }
            assertEquals(2, rows.size)
            val summaries = rows.map { (it as ViewGroup).getChildAt(0) as TextView }.map { it.text.toString() }
            assertTrue(summaries.first().contains("$0.0100"))
            assertEquals(terminalSummary, summaries.last())
            assertFalse(views(history()).filterIsInstance<TextView>().any { it.text == "Закрыть" })
            assertEquals(AppThemePalette.DARK.logText, preview().currentTextColor)
            assertTrue(preview().background is RippleDrawable)
            preview().performClick()
            assertEquals(View.GONE, history().visibility)
            assertEquals(terminalSummary + "  ▾", preview().text.toString())
        }
    }

    @Test fun terminalErrorRemainsBesideAccountingAndThemeSwitchDoesNotChangeRequestOrDraft() {
        ChatDatabase(context).use { db ->
            event(db, "failed", "failed", .004)
            (field("status") as TextView).text = "Ошибка OpenRouter — частичный ответ сохранён"
            (field("input") as EditText).setText("Черновик 😀")
            for (id in AppThemePalette.themes) {
                prefs.edit().putString("app_theme", id).commit()
                call("applyTheme")
                assertEquals("Ошибка OpenRouter — частичный ответ сохранён", (field("status") as TextView).text.toString())
                assertTrue(preview().text.contains("Ошибка"))
                assertTrue(preview().text.contains("$0.0040"))
                assertEquals(AppThemePalette.byId(id).logText, preview().currentTextColor)
                assertEquals("Черновик 😀", (field("input") as EditText).text.toString())
                val model = field("modelBadge") as TextView
                val settings = field("responseSettingsBtn") as Button
                assertEquals(model.layoutParams.height, settings.layoutParams.height)
                val theme = AppThemePalette.byId(id)
                val buttons = views(field("rootLayout") as View).filterIsInstance<Button>()
                    .filter { it !== field("send") && it !== field("stop") }
                assertTrue(buttons.isNotEmpty())
                buttons.forEach {
                    assertEquals(theme.textPrimary, it.currentTextColor)
                    assertEquals(theme.buttonBg, ((it.background as RippleDrawable).getDrawable(0) as GradientDrawable).color!!.defaultColor)
                }
            }
        }
    }

    @Test fun footerCopiesNewestRawAdapterSourceAndDialogPreservesSpinnerSelection() {
        controller!!.start().resume().visible()
        val id = activity.addBubble("Ассистент", "старый текст", false)
        shadowOf(Looper.getMainLooper()).idle()
        val adapter = field("messageAdapter") as ChatMessageAdapter
        val initial = adapter.itemAt(adapter.indexOfId(id))!!
        val card = LinearLayout(activity)
        MainActivity::class.java.getDeclaredMethod("bindChatBubbleCard", LinearLayout::class.java, ChatBubbleItem::class.java)
            .apply { isAccessible = true }.invoke(activity, card, initial)
        val raw = "Формула \\(x^2\\)\n```kotlin\nval x = 1\n```\nновый текст 😀"
        adapter.updateText(id, raw)
        views(card).filterIsInstance<TextView>().single { it.text == "Копировать" }.performClick()
        assertEquals(raw, (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip!!.getItemAt(0).text.toString())
        assertTrue(views(card).filterIsInstance<TextView>().any { it.text == "Сохранить TXT" })
        prefs.edit().putString("app_theme", "light").commit()
        call("showSettings")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
        val spinner = views(dialog.window!!.decorView).filterIsInstance<Spinner>().single()
        assertEquals(1, spinner.selectedItemPosition)
        assertEquals("Builder.show must synchronously theme the installed native controls",
            AppThemePalette.LIGHT.textPrimary, dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).currentTextColor)
        val dropdown = spinner.adapter.getDropDownView(1, null, spinner) as TextView
        assertEquals(AppThemePalette.LIGHT.textPrimary, dropdown.currentTextColor)
    }

    @Test fun backgroundIndicatorIsSilentAndHasNoThinkingOrPrivateChatText() {
        val notification = ResponseNotifications.running(context, "private-chat")
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
        assertNull(notification.sound)
        assertNull(notification.vibrate)
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("размыш"))
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun themeSwitchAlsoRethemesTheDrawerCloseControl() {
        val close = views(field("drawerPanelLayout") as View).filterIsInstance<Button>().single { it.text == "×" }
        for (id in listOf("light", "gray", "dark")) {
            prefs.edit().putString("app_theme", id).commit()
            call("applyTheme")
            val theme = AppThemePalette.byId(id)
            assertEquals(theme.textPrimary, close.currentTextColor)
            assertEquals(theme.buttonBg, ((close.background as RippleDrawable).getDrawable(0) as GradientDrawable).color!!.defaultColor)
        }
    }

    @Test fun emptyToolLabelDoesNotConsumeSpaceButWarningsRemainVisible() {
        val label = field("toolLabel") as TextView
        assertEquals(View.GONE, label.visibility)
        label.text = "Предупреждение инструмента"
        assertEquals(View.VISIBLE, label.visibility)
        label.text = ""
        assertEquals(View.GONE, label.visibility)
    }
}
