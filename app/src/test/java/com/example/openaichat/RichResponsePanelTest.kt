package com.example.openaichat

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
}
