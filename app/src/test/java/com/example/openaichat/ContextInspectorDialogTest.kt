package com.example.openaichat

import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ContextInspectorDialogTest {
    @Test fun activityBadgeIsInTelemetryHeaderAndClickOpensInspector() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).edit().clear()
            .putString("provider", "openai").putString("model", "gpt-6-astra")
            .putBoolean("response_notifications", false).commit()
        try { androidx.work.WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val badge = activity.window.decorView.findViewWithTag<TextView>("context-status-badge")
            assertNotNull(badge)
            assertTrue(badge.text.contains("Контекст"))
            assertTrue(badge.performClick())
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            assertNotNull(dialog.findViewById<android.view.View>(android.R.id.content)
                .findViewWithTag<TextView>("context-inspector-details"))
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun dialogUsesCapturedSnapshotAndOpensOnlyOnExplicitAction() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val snapshot = ContextStatusSnapshot("captured-model", "OpenAI Responses", "локальный replay", "reason",
            2, 42L, listOf("данные.csv"), emptyList(), null, 0L, null)
        val dialog = ContextInspectorDialog.show(activity, AppThemePalette.byId("dark"), snapshot)
        assertTrue(dialog.isShowing)
        val text = dialog.findViewById<android.view.View>(android.R.id.content)
            .findViewWithTag<TextView>("context-inspector-details")
        assertTrue(text.text.contains("captured-model"))
        assertTrue(text.text.contains("неизвестно"))
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        activity.finish()
    }
}
