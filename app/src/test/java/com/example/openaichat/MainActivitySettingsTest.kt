package com.example.openaichat

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
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
class MainActivitySettingsTest {
    private lateinit var prefs: SharedPreferences
    private var controller: ActivityController<MainActivity>? = null

    @Before fun before() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("astra_chats.db")
    }

    @After fun after() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.destroy()
        prefs.edit().clear().commit()
    }

    private fun launch(provider: String = "openai"): MainActivity {
        prefs.edit().putString("provider", provider)
            .putString("model", if (provider == "openai") "gpt-6-astra" else "anthropic/claude-haiku-5.5").commit()
        return Robolectric.buildActivity(MainActivity::class.java).create().also { controller = it }.get()
    }

    private fun openAppSettings(activity: MainActivity): AlertDialog {
        MainActivity::class.java.getDeclaredMethod("showSettings").apply { isAccessible = true }.invoke(activity)
        return ShadowAlertDialog.getLatestAlertDialog()!!
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun widgets(dialog: AlertDialog) = views(dialog.window!!.decorView)
    private fun labels(dialog: AlertDialog) = widgets(dialog).filterIsInstance<TextView>().map { it.text.toString() }
    private fun checkbox(dialog: AlertDialog, prefix: String) = widgets(dialog).filterIsInstance<CheckBox>()
        .single { it.text.startsWith(prefix) }
    private fun save(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun appSettingsContainKeysAppearanceAndDiagnosticsButNoModelControls() {
        val activity = launch()
        val dialog = openAppSettings(activity)
        val hints = widgets(dialog).filterIsInstance<EditText>().map { it.hint?.toString() }
        assertTrue(hints.containsAll(listOf("OpenAI API key", "OpenRouter API key", "GitHub fine-grained PAT")))
        assertTrue(labels(dialog).containsAll(listOf("Настройки приложения", "Тема оформления Integral LLM", "🧪 Диагностика")))
        assertTrue(checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked)
        assertEquals(1, widgets(dialog).filterIsInstance<Spinner>().size)
        assertFalse(labels(dialog).any { it.contains("Усилие мышления") || it.contains("Автосжатие") || it.contains("Web Search") })
        assertFalse(hints.contains("Системный промпт"))
        save(dialog)
        assertFalse(prefs.contains("openai_auto_compaction_user_explicit"))
    }

    @Test fun openAiResponseSettingsSaveOptInAndInstructionsWithoutChangingAppPreferences() {
        val activity = launch()
        activity.showResponseSettings()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
        val texts = labels(dialog)
        assertTrue(texts.containsAll(listOf("Настройки модели и ответа", "Инструменты и контекст OpenAI", "Усилие мышления")))
        assertFalse(texts.any { it.contains("API key") || it.contains("Тема оформления") || it.contains("GitHub fine-grained PAT") })
        assertFalse(texts.contains("Продолжать генерацию ответа в фоне"))
        assertFalse(texts.contains("OpenRouter: Web Search"))
        val compaction = checkbox(dialog, "Автосжатие контекста")
        assertFalse(compaction.isChecked)
        assertFalse(prefs.getBoolean("openai_auto_compaction", true))
        compaction.isChecked = true
        checkbox(dialog, "Web Search").isChecked = true
        widgets(dialog).filterIsInstance<EditText>().single { it.hint == "Системный промпт" }.setText("Keep all evidence")
        save(dialog)
        assertTrue(prefs.getBoolean("openai_auto_compaction", false))
        assertTrue(prefs.getBoolean("openai_auto_compaction_user_explicit", false))
        assertTrue(prefs.getBoolean("web", false))
        assertEquals("Keep all evidence", prefs.getString("system_prompt", null))
        assertFalse(prefs.contains("app_theme"))
        activity.showResponseSettings()
        val reopened = ShadowAlertDialog.getLatestAlertDialog()!!
        assertTrue(checkbox(reopened, "Автосжатие контекста").isChecked)
        checkbox(reopened, "Автосжатие контекста").isChecked = false
        save(reopened)
        assertFalse(prefs.getBoolean("openai_auto_compaction", true))
    }

    @Test fun openRouterResponseSettingsDoNotOverwriteHiddenOpenAiSettings() {
        prefs.edit().putBoolean("openai_auto_compaction", true).putBoolean("openai_auto_compaction_user_explicit", true)
            .putBoolean("web", true).putString("vector_store", "vs_keep").commit()
        val activity = launch("openrouter")
        activity.showResponseSettings()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
        assertTrue(labels(dialog).containsAll(listOf("OpenRouter: Web Search", "Разрешить GitHub-инструменты", "Маршрут Claude (по умолчанию Прямой Anthropic)")))
        assertFalse(labels(dialog).any { it.contains("Автосжатие") || it == "Web Search (OpenAI)" })
        checkbox(dialog, "OpenRouter: Web Search").isChecked = true
        save(dialog)
        assertTrue(prefs.getBoolean("openrouter_web_search", false))
        assertTrue(prefs.getBoolean("openai_auto_compaction", false))
        assertTrue(prefs.getBoolean("web", false))
        assertEquals("vs_keep", prefs.getString("vector_store", null))
        assertEquals("anthropic", prefs.getString("openrouter_claude_route", null))
    }

    @Test fun migrationDisablesImplicitLegacyCompactionAndPreservesBackgroundChoice() {
        prefs.edit().putBoolean("openai_auto_compaction", true).putBoolean("background_responses", false).commit()
        val activity = launch()
        assertFalse(prefs.getBoolean("openai_auto_compaction", true))
        assertFalse(prefs.getBoolean("background_responses", true))
        assertTrue(prefs.getBoolean("openai_auto_compaction_opt_in_migrated_v25", false))
        prefs.edit().putBoolean("openai_auto_compaction", true).putBoolean("background_responses", false).commit()
        MainActivity::class.java.getDeclaredMethod("migrateLegacySettingsPreferences").apply { isAccessible = true }.invoke(activity)
        assertTrue(prefs.getBoolean("openai_auto_compaction", false))
        assertFalse(prefs.getBoolean("background_responses", true))
    }

    @Test fun savedBackgroundResponsesFalseSurvivesMigrationRestartAndSettingsSave() {
        prefs.edit().putBoolean("background_responses", false)
            .remove("background_responses_migrated_v25").commit()
        var activity = launch()
        assertFalse(prefs.getBoolean("background_responses", true))
        var dialog = openAppSettings(activity)
        assertFalse(checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked)
        save(dialog)
        assertFalse(prefs.getBoolean("background_responses", true))

        controller!!.destroy()
        controller = null
        activity = launch()
        assertFalse(prefs.getBoolean("background_responses", true))
        dialog = openAppSettings(activity)
        assertFalse(checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked)
        checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked = true
        save(dialog)
        assertTrue(prefs.getBoolean("background_responses", false))

        dialog = openAppSettings(activity)
        assertTrue(checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked)
        checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked = false
        save(dialog)
        assertFalse(prefs.getBoolean("background_responses", true))

        controller!!.destroy()
        controller = null
        activity = launch()
        assertFalse(prefs.getBoolean("background_responses", true))
        dialog = openAppSettings(activity)
        assertFalse(checkbox(dialog, "Продолжать генерацию ответа в фоне").isChecked)
    }

    @Test fun missingBackgroundPreferenceDefaultsToTrueEvenWithLegacyMigrationMarker() {
        prefs.edit().remove("background_responses")
            .putBoolean("background_responses_migrated_v25", true).commit()
        val activity = launch()
        assertTrue(prefs.contains("background_responses"))
        assertTrue(prefs.getBoolean("background_responses", false))
        assertTrue(checkbox(openAppSettings(activity), "Продолжать генерацию ответа в фоне").isChecked)
    }

    @Test fun migrationPreservesAnExplicitCompactionOptIn() {
        prefs.edit().putBoolean("openai_auto_compaction", true).putBoolean("openai_auto_compaction_user_explicit", true).commit()
        launch()
        assertTrue(prefs.getBoolean("openai_auto_compaction", false))
    }

    @Test fun selectingEachThemeAppliesRootHeaderAndInputColorsWithoutRestart() {
        val activity = launch()
        val input = MainActivity::class.java.getDeclaredField("input").apply { isAccessible = true }.get(activity) as EditText
        input.setText("Неотправленный черновик 😀")
        val model = prefs.getString("model", null)
        val chat = prefs.getString("current_chat", null)
        for (id in AppThemePalette.themes) {
            val dialog = openAppSettings(activity)
            widgets(dialog).filterIsInstance<Spinner>().single().setSelection(AppThemePalette.themes.indexOf(id))
            save(dialog)
            val theme = AppThemePalette.byId(id)
            fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
            assertEquals(id, prefs.getString("app_theme", null))
            assertEquals(theme.rootBg, ((field("rootLayout") as View).background as ColorDrawable).color)
            assertEquals(theme.headerBg, ((field("headerLayout") as View).background as ColorDrawable).color)
            assertEquals(theme.textPrimary, (field("input") as EditText).currentTextColor)
            assertEquals(theme.hintText, (field("input") as EditText).currentHintTextColor)
            assertEquals("Неотправленный черновик 😀", input.text.toString())
            assertEquals(model, prefs.getString("model", null))
            assertEquals(chat, prefs.getString("current_chat", null))
        }
        controller!!.destroy()
        controller = null
        val reopened = launch()
        val root = MainActivity::class.java.getDeclaredField("rootLayout").apply { isAccessible = true }.get(reopened) as View
        assertEquals(AppThemePalette.GRAY.rootBg, (root.background as ColorDrawable).color)
        val restoredInput = MainActivity::class.java.getDeclaredField("input").apply { isAccessible = true }.get(reopened) as EditText
        assertEquals("Неотправленный черновик 😀", restoredInput.text.toString())
    }
}
