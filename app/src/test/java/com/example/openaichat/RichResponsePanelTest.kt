package com.example.openaichat

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RichResponsePanelTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun inlineFormulaFormattingIsExplicitAndPreparationFailureKeepsTheNativeAnswer() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val source = "Science ${'$'}x^2${'$'} and ${'$'}5; полный исходник 😀"
            val native = TextView(activity).apply { text = source }
            var preparations = 0
            val panel = ScientificMathText(activity, native) { preparations++; throw IllegalArgumentException("Unavailable assets") }
            activity.setContentView(panel)
            assertEquals(0, preparations)
            assertTrue(descendants(panel).none { it is WebView })
            descendants(panel).filterIsInstance<Button>().single().performClick()
            assertEquals(1, preparations)
            assertEquals(source, native.text.toString())
            assertEquals(View.VISIBLE, native.visibility)
            assertTrue(descendants(panel).none { it is WebView })
            assertTrue(descendants(panel).filterIsInstance<TextView>().any { it.text.toString().contains("доступен полностью") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun narrowDarkCardKeepsCodeAndTableScrollableWithoutCreatingWebView() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val panel = RichResponsePanel(activity, {}, {})
            val source = "# Заголовок\n\n- [x] Готово\n\n````python\n" + "long_code_".repeat(100) +
                "\n``` literal\n````\n\n| a | b | c |\n| -- | -- | -- |\n| 1 | 2<br>3 | 4 |\n"
            panel.submit(ChatBubbleItem.create("id", "Claude", source, false))
            activity.setContentView(panel)
            val width = (380 * activity.resources.displayMetrics.density).toInt()
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            panel.layout(0, 0, width, panel.measuredHeight)
            val all = descendants(panel)
            assertEquals(2, all.filterIsInstance<HorizontalScrollView>().size)
            assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().contains("``` literal") })
            assertTrue(all.filterIsInstance<Button>().any { it.text == "Копировать" })
            assertTrue(all.none { it is WebView })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun tableColumnsAdaptToContentAndUseTheSelectedThemeWithoutTruncatingCells() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        val oldTheme = prefs.getString("app_theme", null)
        try {
            val longText = "longword".repeat(20)
            for (themeId in AppThemePalette.themes) {
                prefs.edit().putString("app_theme", themeId).commit()
                val theme = AppThemePalette.byId(themeId)
                val panel = RichResponsePanel(activity, {}, {})
                panel.submit(ChatBubbleItem.create("table-$themeId", "Claude",
                    "| A | Long |\n| -- | -- |\n| 1 | $longText |\n", false))
                val cells = descendants(panel).filterIsInstance<TextView>()
                val first = cells.single { it.text.toString() == "A" }
                val second = cells.single { it.text.toString() == "Long" }
                val longCell = cells.single { it.text.toString() == longText }
                val density = activity.resources.displayMetrics.density
                assertEquals((88 * density).toInt(), first.layoutParams.width)
                assertEquals((280 * density).toInt(), second.layoutParams.width)
                assertEquals(second.layoutParams.width, longCell.layoutParams.width)
                assertEquals(theme.textPrimary, longCell.currentTextColor)
                assertEquals(theme.tableHeaderBg, ((first.parent as View).background as android.graphics.drawable.ColorDrawable).color)
                assertEquals(theme.tableRowBg, ((longCell.parent as View).background as android.graphics.drawable.ColorDrawable).color)
                assertTrue(descendants(panel).any { it is HorizontalScrollView })
            }
        } finally {
            prefs.edit().apply { if (oldTheme == null) remove("app_theme") else putString("app_theme", oldTheme) }.commit()
            controller.pause().stop().destroy()
        }
    }

    @Test fun scientificBlocksPreviewAutomaticallyInEveryThemeAndCanShowCompleteSource() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        try {
            for (id in AppThemePalette.themes) {
                prefs.edit().putString("app_theme", id).putBoolean("response_scientific_preview", true).commit()
                val theme = AppThemePalette.byId(id)
                val panel = RichResponsePanel(activity, {}, {})
                panel.submit(ChatBubbleItem.create("auto-$id", "Claude",
                    "```math\nx^2\n```\n\n```mermaid\nflowchart TD\n A-->B\n```\n\n```svg\n<svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"5\"/></svg>\n```", false))
                activity.setContentView(panel)
                val all = descendants(panel)
                assertEquals(3, all.filterIsInstance<WebView>().size)
                assertTrue(all.filterIsInstance<Button>().any { it.text == "Исходный код" })
                val svg = all.filterIsInstance<WebView>().single { !it.settings.javaScriptEnabled }
                assertFalse(svg.settings.allowFileAccess); assertTrue(svg.settings.blockNetworkLoads)
                all.filterIsInstance<Button>().filter { it.text == "Исходный код" }.forEach { it.performClick() }
                val native = descendants(panel).filterIsInstance<TextView>().single { it.text.toString() == "x^2\n" }
                assertEquals(theme.textPrimary, native.currentTextColor)
                assertEquals(View.VISIBLE, native.visibility)
            }
            prefs.edit().putBoolean("response_scientific_preview", false).commit()
            val disabled = RichResponsePanel(activity, {}, {})
            disabled.submit(ChatBubbleItem.create("disabled", "Claude", "```math\nx^2\n```\n\n```mermaid\nflowchart TD\nA-->B\n```", false))
            assertTrue(descendants(disabled).none { it is WebView })
        } finally { prefs.edit().remove("app_theme").remove("response_scientific_preview").commit(); controller.pause().stop().destroy() }
    }

    @Test fun fileCardSavesOnlyAClosedSafeTextBlockAndNothingAutoExecutes() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val saved = mutableListOf<ResponseBlockParser.Block.File>()
            val pending = RichResponsePanel(activity, { saved.add(it) }, {})
            pending.submit(ChatBubbleItem.create("pending", "OpenAI", "<file name=\"science.txt\">\nhello", false))
            assertTrue(descendants(pending).filterIsInstance<Button>().none { it.text == "Сохранить" })
            val complete = RichResponsePanel(activity, { saved.add(it) }, {})
            complete.submit(ChatBubbleItem.create("closed", "OpenAI", "<file name=\"science.txt\">\n😀hello\n</file>\n", false))
            assertTrue(saved.isEmpty())
            descendants(complete).filterIsInstance<Button>().single { it.text == "Сохранить" }.performClick()
            assertEquals("😀hello\n", saved.single().content)
            val malicious = RichResponsePanel(activity, {}, {})
            malicious.submit(ChatBubbleItem.create("xss", "Claude", "<script>alert(1)</script>\n\n![track](https://tracker.invalid/pixel)", false))
            assertTrue(descendants(malicious).none { it is WebView })
            assertTrue(descendants(malicious).filterIsInstance<TextView>().any { it.text.toString().contains("не загружено") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nativeDetailsAndIndicesRenderWithoutAWebView() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val panel = RichResponsePanel(controller.get(), {}, {})
            panel.submit(ChatBubbleItem.create("details", "Claude",
                "<details>\n<summary>Подробнее</summary>\nx<sup>2</sup> и H<sub>2</sub>O\n</details>\n", false))
            val toggle = descendants(panel).filterIsInstance<Button>().single { it.text.toString().contains("Подробнее") }
            assertTrue(descendants(panel).filterIsInstance<TextView>().none { it.text.toString().contains("H2O") })
            toggle.performClick()
            val formula = descendants(panel).filterIsInstance<TextView>().single { it.text.toString().contains("H2O") }
            assertFalse(formula.text.toString().contains("<sup>"))
            val spans = (formula.text as android.text.Spanned).getSpans(0, formula.text.length, Any::class.java)
            assertTrue(spans.any { it.javaClass.simpleName.contains("SuperScript") })
            assertTrue(spans.any { it.javaClass.simpleName.contains("SubScript") })
            toggle.performClick()
            assertTrue(toggle.text.toString().startsWith("▸"))
            assertTrue(descendants(panel).none { it is WebView })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun detachingPanelDestroysAutomaticScientificWebViews() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        try {
            prefs.edit().putString("app_theme", "gray").putBoolean("response_scientific_preview", true).commit()
            val panel = RichResponsePanel(activity, {}, {})
            panel.submit(ChatBubbleItem.create("detach-test", "Claude", "```math\nx^2\n```\n\n```mermaid\nflowchart TD\nA-->B\n```", false))
            activity.setContentView(panel)
            assertEquals(2, descendants(panel).filterIsInstance<WebView>().size)
            activity.setContentView(View(activity))
            assertEquals(0, descendants(panel).filterIsInstance<WebView>().size)
        } finally {
            prefs.edit().remove("app_theme").remove("response_scientific_preview").commit()
            controller.pause().stop().destroy()
        }
    }
    private fun withPreviewSetting(automatic: Boolean, action: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        val oldTheme = prefs.getString("app_theme", null)
        val oldAuto = if (prefs.contains("response_scientific_preview")) prefs.getBoolean("response_scientific_preview", true) else null
        try {
            prefs.edit().putString("app_theme", "light").putBoolean("response_scientific_preview", automatic).commit()
            action(activity)
        } finally {
            ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
            prefs.edit().apply {
                if (oldTheme == null) remove("app_theme") else putString("app_theme", oldTheme)
                if (oldAuto == null) remove("response_scientific_preview") else putBoolean("response_scientific_preview", oldAuto)
            }.commit()
            controller.pause().stop().destroy()
        }
    }

    private fun assertRendererSandbox(web: WebView, javascript: Boolean) {
        assertEquals(javascript, web.settings.javaScriptEnabled)
        assertFalse(web.settings.allowFileAccess)
        assertFalse(web.settings.allowContentAccess)
        assertFalse(web.settings.domStorageEnabled)
        assertTrue(web.settings.blockNetworkLoads)
        assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, web.settings.mixedContentMode)
    }

    private fun awaitButton(panel: RichResponsePanel, label: String): Button {
        val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            descendants(panel).filterIsInstance<Button>().firstOrNull { it.text.toString() == label }?.let { return it }
            Thread.sleep(10) // Allow the bounded parser executor to publish its prepared snapshot.
        }
        throw AssertionError("No button $label after parsing")
    }

    @Test fun disabledAutomaticPreviewKeepsInlineAndFencedActionsAndTheirSandboxedFallbacks() = withPreviewSetting(false) { activity ->
        val panel = RichResponsePanel(activity, {}, {})
        val math = "x^2 + y^2 = z^2\n"
        val graph = "flowchart TD\nA-->B\n"
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"5\"/></svg>\n"
        panel.submit(ChatBubbleItem.create("manual", "OpenAI", "Inline ${'$'}x^2${'$'}.\n\n" +
            "```math\n$math```\n\n```mermaid\n$graph```\n\n```svg\n$svg```", false))
        activity.setContentView(panel)
        assertTrue(descendants(panel).none { it is WebView })
        val inline = descendants(panel).filterIsInstance<ScientificMathText>().single()
        descendants(inline).filterIsInstance<Button>().single().performClick()
        val inlineWeb = descendants(inline).filterIsInstance<WebView>().single()
        assertRendererSandbox(inlineWeb, true)
        inlineWeb.webChromeClient!!.onReceivedTitle(inlineWeb, "renderer:failed")
        assertTrue(descendants(inline).none { it is WebView })
        assertTrue(descendants(inline).filterIsInstance<TextView>().any { it.visibility == View.VISIBLE && it.text.toString().contains("${'$'}x^2${'$'}") })

        listOf(Triple("Превью формулы", math, true), Triple("Превью диаграммы", graph, true), Triple("Превью SVG", svg, false)).forEach { (label, source, javascript) ->
            descendants(panel).filterIsInstance<Button>().single { it.text.toString() == label }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
            assertTrue(dialog.isShowing)
            val views = descendants(dialog.window!!.decorView)
            val web = views.filterIsInstance<WebView>().single()
            assertRendererSandbox(web, javascript)
            val original = views.filterIsInstance<TextView>().single { it.text.toString() == source }
            if (javascript) {
                web.webChromeClient!!.onReceivedTitle(web, "renderer:ready")
                assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Офлайн-превью · исходник сохранён" })
                web.webChromeClient!!.onReceivedTitle(web, "renderer:failed")
                assertTrue(descendants(dialog.window!!.decorView).none { it is WebView })
            } else views.filterIsInstance<Button>().single { it.text.toString() == "Код" }.performClick()
            assertEquals(View.VISIBLE, original.visibility)
            assertEquals(source, original.text.toString())
            dialog.dismiss()
        }
    }

    @Test fun manualLatexAndTexUseTheSameRendererAndKeepInvalidSourceOnFailure() = withPreviewSetting(false) { activity ->
        for (language in listOf("latex", "tex")) {
            val invalid = "\\unknownCommand{a}\n"
            val panel = RichResponsePanel(activity, {}, {})
            panel.submit(ChatBubbleItem.create(language, "Claude", "```$language\n$invalid```", false))
            activity.setContentView(panel)
            descendants(panel).filterIsInstance<Button>().single { it.text.toString() == "Превью формулы" }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
            val web = descendants(dialog.window!!.decorView).filterIsInstance<WebView>().single()
            web.webChromeClient!!.onReceivedTitle(web, "renderer:failed")
            val source = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == invalid }
            assertEquals(View.VISIBLE, source.visibility)
            assertTrue(descendants(dialog.window!!.decorView).none { it is WebView })
            dialog.dismiss()
        }
    }

    @Test fun manualPreviewsRejectUnsafeAndOversizedSourceWithoutLosingNativeText() = withPreviewSetting(false) { activity ->
        val cases = listOf(
            Triple("math", "x".repeat(ScientificRendererDocument.MAX_MATH_CHARACTERS + 1), "Превью формулы"),
            Triple("mermaid", "flowchart TD\nA-->B\nclick A \"https://example.invalid\"", "Превью диаграммы"),
            Triple("svg", "<svg>" + " ".repeat(200_001) + "</svg>", "Превью SVG")
        )
        cases.forEachIndexed { index, (language, source, label) ->
            val panel = RichResponsePanel(activity, {}, {})
            panel.submit(ChatBubbleItem.create("budget-$index", "Claude", "```$language\n$source\n```", false))
            activity.setContentView(panel)
            awaitButton(panel, label).performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
            assertTrue(dialog.isShowing)
            val views = descendants(dialog.window!!.decorView)
            assertTrue(views.none { it is WebView })
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("исходник", true) || it.text.toString().contains("Исходный код") || it.text.toString().contains("не разрешены") })
            if (source.length <= 100_000) assertTrue(descendants(panel).filterIsInstance<TextView>().any { it.text.toString() == source + "\n" })
            else {
                var saved: ResponseBlockParser.Block.File? = null
                val retainingPanel = RichResponsePanel(activity, { saved = it }, {})
                retainingPanel.submit(ChatBubbleItem.create("large-source", "Claude", "```svg\n$source\n```", false))
                activity.setContentView(retainingPanel)
                awaitButton(retainingPanel, "Сохранить всё").performClick()
                assertEquals(source + "\n", saved!!.content)
            }
            dialog.dismiss()
        }
    }

    @Test fun incompleteScientificBlocksHaveNoManualActionOrRenderer() = withPreviewSetting(false) { activity ->
        for (language in listOf("math", "latex", "tex", "mermaid", "svg")) {
            val panel = RichResponsePanel(activity, {}, {})
            panel.submit(ChatBubbleItem.create("incomplete-$language", "OpenAI", "Unfinished ${'$'}x\n\n```$language\nx^2", false))
            activity.setContentView(panel)
            assertTrue(descendants(panel).none { it is WebView })
            assertTrue(descendants(panel).filterIsInstance<Button>().none { it.text.toString().contains("Превью") || it.text.toString().contains("Формулы") })
            assertTrue(descendants(panel).filterIsInstance<TextView>().any { it.text.toString() == "x^2" })
        }
    }

    @Test fun pagedProseOffersManualFormulaPreviewWithAutomaticPreviewDisabled() = withPreviewSetting(false) { activity ->
        val panel = RichResponsePanel(activity, {}, {})
        val source = "Formula ${'$'}x^2${'$'} " + "prose ".repeat(18_000)
        panel.submit(ChatBubbleItem.create("paged-manual", "Claude", source, false))
        activity.setContentView(panel)
        awaitButton(panel, "Формулы фрагмента").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()!!
        val web = descendants(dialog.window!!.decorView).filterIsInstance<WebView>().single()
        assertRendererSandbox(web, true)
        web.webChromeClient!!.onReceivedTitle(web, "renderer:failed")
        assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.visibility == View.VISIBLE && it.text.toString() == "${'$'}x^2${'$'}" })
        dialog.dismiss()
        descendants(panel).filterIsInstance<Button>().single { it.text.toString() == "Конец" }.performClick()
        descendants(panel).filterIsInstance<Button>().single { it.text.toString() == "Формулы фрагмента" }.performClick()
        val empty = ShadowAlertDialog.getLatestAlertDialog()!!
        assertTrue(descendants(empty.window!!.decorView).none { it is WebView })
        assertTrue(descendants(empty.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("нет закрытых формул") })
    }

    @Test fun manualInlinePreviewTimeoutAndDetachKeepSourceAndAllowReopen() = withPreviewSetting(false) { activity ->
        val panel = RichResponsePanel(activity, {}, {})
        panel.submit(ChatBubbleItem.create("inline-reopen", "OpenAI", "Formula ${'$'}x^2${'$'}", false))
        activity.setContentView(panel)
        val inline = descendants(panel).filterIsInstance<ScientificMathText>().single()
        val toggle = descendants(inline).filterIsInstance<Button>().single()
        toggle.performClick()
        val first = descendants(inline).filterIsInstance<WebView>().single()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13))
        assertTrue(descendants(inline).none { it is WebView })
        assertTrue(descendants(inline).filterIsInstance<TextView>().any { it.visibility == View.VISIBLE && it.text.toString().contains("${'$'}x^2${'$'}") })
        toggle.performClick()
        val second = descendants(inline).filterIsInstance<WebView>().single()
        assertNotSame(first, second)
        activity.setContentView(View(activity))
        assertTrue(descendants(inline).none { it is WebView })
        activity.setContentView(panel)
        toggle.performClick()
        val third = descendants(inline).filterIsInstance<WebView>().single()
        assertNotSame(second, third)
        assertRendererSandbox(third, true)
        third.webChromeClient!!.onReceivedTitle(third, "renderer:failed")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13))
        assertTrue(descendants(inline).none { it is WebView })
        assertTrue(descendants(inline).filterIsInstance<TextView>().none { it.text.toString().contains("Форматирование не завершено") })
    }

    @Test fun automaticSvgUsesHorizontalTabsAndCanReopenAfterDetachWithoutAStaleWebView() = withPreviewSetting(true) { activity ->
        val source = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"5\"/></svg>\n"
        val panel = RichResponsePanel(activity, {}, {})
        panel.submit(ChatBubbleItem.create("svg-reopen", "OpenAI", "```svg\n$source```", false))
        activity.setContentView(panel)
        val first = descendants(panel).filterIsInstance<WebView>().single()
        val sourceButton = descendants(panel).filterIsInstance<Button>().single { it.text.toString() == "Исходный код" }
        val previewButton = descendants(panel).filterIsInstance<Button>().single { it.text.toString() == "Превью" }
        assertSame(sourceButton.parent, previewButton.parent)
        assertEquals(LinearLayout.HORIZONTAL, (sourceButton.parent as LinearLayout).orientation)
        sourceButton.performClick()
        assertEquals(View.GONE, first.visibility)
        val original = descendants(panel).filterIsInstance<TextView>().single { it.text.toString() == source }
        assertEquals(View.VISIBLE, original.visibility)
        previewButton.performClick()
        assertEquals(View.VISIBLE, first.visibility)
        activity.setContentView(View(activity))
        assertTrue(descendants(panel).none { it is WebView })
        assertEquals(View.VISIBLE, original.visibility)
        activity.setContentView(panel)
        previewButton.performClick()
        val reopened = descendants(panel).filterIsInstance<WebView>().single()
        assertNotSame(first, reopened)
        assertRendererSandbox(reopened, false)
    }

}
