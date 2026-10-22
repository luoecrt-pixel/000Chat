package com.example.openaichat

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowInteractiveSandboxWebViewFeature::class, ShadowInteractiveSandboxWebViewCompat::class])
class RichResponseSandboxIntegrationTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun onlyAnExplicitLaunchCreatesAnInteractiveRendererAndDetachClosesIt() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            for (language in listOf("html", "sandbox", "chart")) {
                val panel = RichResponsePanel(activity, {}, {})
                activity.setContentView(panel)
                panel.submit(ChatBubbleItem.create("sandbox-$language", "Model",
                    "```$language\n<button onclick=\"this.textContent='Готово'\">Нажать</button>\n```", false))
                assertTrue(descendants(panel).filterIsInstance<WebView>().isEmpty())
                val launch = descendants(panel).filterIsInstance<Button>().single { it.text.toString() == "▶ Запустить в песочнице" }
                launch.performClick()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
                assertTrue(dialog.isShowing)
                val web = descendants(dialog.window!!.decorView).filterIsInstance<WebView>().single()
                assertTrue(web.settings.javaScriptEnabled)
                assertTrue(web.settings.blockNetworkLoads)
                assertFalse(web.settings.allowFileAccess)
                assertFalse(web.settings.allowContentAccess)
                activity.setContentView(View(activity))
                assertFalse(dialog.isShowing)
                assertNull(web.parent)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unfinishedBlocksNeverOfferExecutionAndStaticHtmlPreviewRemainsSeparate() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val unfinished = RichResponsePanel(activity, {}, {})
            unfinished.submit(ChatBubbleItem.create("sandbox-partial", "Model", "```html\n<script>test()</script>", false))
            assertTrue(descendants(unfinished).filterIsInstance<Button>().none { it.text.toString() == "▶ Запустить в песочнице" })
            val complete = RichResponsePanel(activity, {}, {})
            activity.setContentView(complete)
            complete.submit(ChatBubbleItem.create("sandbox-static", "Model", "```html\n<script>test()</script>\n```", false))
            descendants(complete).filterIsInstance<Button>().single { it.text.toString() == "Код / Превью" }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
            assertFalse(descendants(dialog.window!!.decorView).filterIsInstance<WebView>().single().settings.javaScriptEnabled)
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }
}
