package com.example.openaichat

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections

/** Real APK assets and WebView are needed; no provider API or remote page is called. */
@RunWith(AndroidJUnit4::class)
class ScientificRendererDeviceTest {
    @Test fun richPanelAutomaticallyRendersMathAndMermaidWithThemeColors() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("app", 0)
        val oldTheme = prefs.getString("app_theme", null)
        val oldPreview = prefs.all["response_scientific_preview"] as? Boolean
        fun webViews(view: View): List<WebView> = if (view is WebView) listOf(view) else if (view is ViewGroup)
            (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) } else emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                for (id in AppThemePalette.themes) {
                    val ready = CountDownLatch(2)
                    val dialogRef = AtomicReference<AlertDialog>()
                    val previews = AtomicReference<List<WebView>>()
                    scenario.onActivity { activity ->
                        prefs.edit().putString("app_theme", id).putBoolean("response_scientific_preview", true).commit()
                        val panel = RichResponsePanel(activity, {}, {})
                        panel.submit(ChatBubbleItem.create("auto-$id", "Model",
                            "```math\nx^2\n```\n\n```mermaid\nflowchart TD\nA-->B\n```", false))
                        val webs = webViews(panel)
                        assertEquals(2, webs.size)
                        previews.set(webs)
                        webs.forEach { web ->
                            val chrome = web.webChromeClient
                            web.webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView, title: String?) {
                                    chrome?.onReceivedTitle(view, title)
                                    if (title == "renderer:ready") ready.countDown()
                                }
                            }
                        }
                        dialogRef.set(AlertDialog.Builder(activity).setView(panel).setPositiveButton("Close", null).show())
                    }
                    try {
                        assertTrue("Automatic previews did not render for $id", ready.await(25, TimeUnit.SECONDS))
                        val theme = AppThemePalette.byId(id)
                        val expected = "\"rgb(${android.graphics.Color.red(theme.assistantBubbleBg)}, ${android.graphics.Color.green(theme.assistantBubbleBg)}, ${android.graphics.Color.blue(theme.assistantBubbleBg)})\""
                        previews.get().forEach { web ->
                            assertEquals(expected, evaluate(scenario, web, "getComputedStyle(document.body).backgroundColor"))
                            assertEquals("true", evaluate(scenario, web, "!!document.querySelector('.katex, #result svg')"))
                        }
                    } finally { scenario.onActivity { dialogRef.get().dismiss() } }
                }
            } finally {
                prefs.edit().apply {
                    if (oldTheme == null) remove("app_theme") else putString("app_theme", oldTheme)
                    if (oldPreview == null) remove("response_scientific_preview") else putBoolean("response_scientific_preview", oldPreview)
                }.commit()
            }
        }
    }

    @Test fun richPanelManualInlineMathRendersWhenAutomaticPreviewIsDisabled() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("app", 0)
        val oldPreview = prefs.all["response_scientific_preview"] as? Boolean
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ready = CountDownLatch(1)
            val title = AtomicReference<String>()
            val panelRef = AtomicReference<RichResponsePanel>()
            val dialogRef = AtomicReference<AlertDialog>()
            val webRef = AtomicReference<WebView>()
            try {
                scenario.onActivity { activity ->
                    prefs.edit().putBoolean("response_scientific_preview", false).commit()
                    val panel = RichResponsePanel(activity, {}, {})
                    panel.submit(ChatBubbleItem.create("manual-inline-device", "Model", "**Science** ${'$'}x^2${'$'} and source.", false))
                    panelRef.set(panel)
                    dialogRef.set(AlertDialog.Builder(activity).setView(panel).setPositiveButton("Close", null).show())
                    assertNull("Automatic preview must be disabled", findWeb(panel))
                    descendants(panel).filterIsInstance<Button>().single { it.text.toString() == "Формулы в тексте" }.performClick()
                    val web = findWeb(panel) ?: throw AssertionError("Manual action did not open the renderer")
                    webRef.set(web)
                    val delegate = web.webChromeClient
                    web.webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView, value: String?) {
                            delegate?.onReceivedTitle(view, value)
                            if (value == "renderer:ready" || value == "renderer:failed") { title.set(value); ready.countDown() }
                        }
                    }
                    if (web.title == "renderer:ready" || web.title == "renderer:failed") { title.set(web.title); ready.countDown() }
                }
                assertTrue("Manual inline engine did not finish", ready.await(15, TimeUnit.SECONDS))
                assertEquals("renderer:ready", title.get())
                assertEquals("1", evaluate(scenario, webRef.get(), "document.querySelectorAll('.katex').length"))
                assertEquals("1", evaluate(scenario, webRef.get(), "document.querySelectorAll('#result b,#result strong').length"))
                assertEquals("\"undefined\"", evaluate(scenario, webRef.get(), "typeof window.Android"))
                scenario.onActivity {
                    val panel = panelRef.get()
                    descendants(panel).filterIsInstance<Button>().single { it.text.toString() == "Исходный код" }.performClick()
                    assertNull("Source action disposes its renderer", findWeb(panel))
                    assertTrue(descendants(panel).filterIsInstance<TextView>().any { it.visibility == View.VISIBLE && it.text.toString().contains("${'$'}x^2${'$'}") })
                }
            } finally {
                scenario.onActivity { dialogRef.get()?.dismiss() }
                prefs.edit().apply {
                    if (oldPreview == null) remove("response_scientific_preview") else putBoolean("response_scientific_preview", oldPreview)
                }.commit()
            }
        }
    }

    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun readyPreview(scenario: ActivityScenario<MainActivity>, document: String, source: String, phase: String): Pair<AlertDialog, WebView> {
        val ready = CountDownLatch(1)
        val title = AtomicReference<String>()
        val result = AtomicReference<Pair<AlertDialog, WebView>>()
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())
        scenario.onActivity { activity ->
            assertTrue("Pinned renderer assets must be packaged in this APK", ScientificRendererPreview.available(activity))
            val dialog = ScientificRendererPreview.create(activity, document, source) { }
            dialog.show()
            val web = findWeb(dialog.window!!.decorView) ?: throw AssertionError("Preview WebView missing")
            val delegate = web.webChromeClient
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    diagnostics.add("JS ${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()}: ${message.message()}")
                    return delegate?.onConsoleMessage(message) ?: false
                }
                override fun onReceivedTitle(view: WebView, value: String?) {
                    diagnostics.add("title=$value")
                    delegate?.onReceivedTitle(view, value)
                    if (value == "renderer:ready" || value == "renderer:failed") { title.set(value); ready.countDown() }
                }
            }
            val client = web.webViewClient
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = client.shouldOverrideUrlLoading(view, request)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val response = client.shouldInterceptRequest(view, request)
                    diagnostics.add("resource=${request.url} status=${response?.statusCode} mime=${response?.mimeType}")
                    return response
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    diagnostics.add("resource-error=${request.url} code=${error.errorCode}: ${error.description}")
                    client.onReceivedError(view, request, error)
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    diagnostics.add("renderer-process-gone: crashed=${detail.didCrash()}")
                    return client.onRenderProcessGone(view, detail)
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    client.onPageFinished(view, url)
                    view.evaluateJavascript("JSON.stringify({title:document.title,ready:document.readyState,fonts:document.fonts.status,origin:window.origin})") {
                        diagnostics.add("page=$it")
                    }
                }
            }
            assertTrue(web.settings.javaScriptEnabled)
            assertFalse(web.settings.allowFileAccess)
            assertFalse(web.settings.allowContentAccess)
            assertFalse(web.settings.domStorageEnabled)
            assertTrue(web.settings.blockNetworkLoads)
            result.set(dialog to web)
            if (web.title == "renderer:ready" || web.title == "renderer:failed") { title.set(web.title); ready.countDown() }
        }
        try {
            val finished = ready.await(15, TimeUnit.SECONDS)
            val detail = synchronized(diagnostics) { diagnostics.joinToString(" | ") }
            assertTrue("Offline engine did not finish [$phase]: $detail", finished)
            assertEquals("Offline engine failed [$phase]: $detail", "renderer:ready", title.get())
            return result.get()
        } catch (failure: Throwable) {
            scenario.onActivity { result.get()?.first?.dismiss() }
            throw failure
        }
    }
    private fun evaluate(scenario: ActivityScenario<MainActivity>, web: WebView, expression: String): String {
        val returned = CountDownLatch(1)
        val value = AtomicReference<String>()
        scenario.onActivity { web.evaluateJavascript(expression) { value.set(it); returned.countDown() } }
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        return value.get()
    }
    @Test fun packagedKatexAndMermaidRenderWithoutExposingAnAndroidBridge() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val valid = "\\frac{a^2+b^2}{c}"
            val hostile = "</script><script>window.attack=1</script>"
            // KaTeX accepts angle brackets as math characters; malicious markup is not invalid LaTeX.
            // Keep script-injection and unsupported-command fallback as separate assertions.
            val malformed = "\\unknownRendererCommand{$hostile}"
            val formulas = listOf(valid, hostile, malformed).map { MathDelimiterScanner.Formula(0, it.length, it, it, true) }
            val math = readyPreview(scenario, ScientificRendererDocument.math(formulas), formulas.joinToString("\n") { it.source }, "math")
            try {
                assertEquals("\"${ScientificRendererDocument.DOCUMENT_URL}\"", evaluate(scenario, math.second, "location.href"))
                assertEquals("\"${ScientificRendererDocument.ORIGIN.removeSuffix("/")}\"", evaluate(scenario, math.second, "location.origin"))
                assertEquals("\"undefined\"", evaluate(scenario, math.second, "typeof window.Android"))
                assertEquals("Valid formula and harmless markup must both render", "2", evaluate(scenario, math.second, "document.querySelectorAll('.katex').length"))
                assertEquals("Model markup must never execute", "\"undefined\"", evaluate(scenario, math.second, "typeof window.attack"))
                assertEquals("Model markup must never create script elements", "0", evaluate(scenario, math.second, "document.querySelectorAll('#result script').length"))
                assertEquals("Malformed LaTeX must preserve every source character", malformed,
                    org.json.JSONTokener(evaluate(scenario, math.second, "document.querySelector('.failed').textContent")).nextValue())
            } finally { scenario.onActivity { math.first.dismiss() } }
            val prose = "Bold ${'$'}x^2${'$'}; цена ${'$'}5 и ${'$'}10; \\[\\frac{a}{b}\\]."
            val prepared = MathTextTokens.prepare(prose, MathDelimiterScanner.scan(prose))
            val nativeHtml = "<p><b>Bold</b> " + prepared.items[0].first + "; цена ${'$'}5 и ${'$'}10; " + prepared.items[1].first + ".</p>"
            val inline = readyPreview(scenario, ScientificRendererDocument.mathInText(nativeHtml, prepared), prose, "math-text")
            try {
                assertEquals("Both inline formulas must render", "2", evaluate(scenario, inline.second, "document.querySelectorAll('.katex').length"))
                assertEquals("Native bold text must survive", "1", evaluate(scenario, inline.second, "document.querySelectorAll('#result b').length"))
                assertEquals("Only the display formula gets a separate block", "1", evaluate(scenario, inline.second, "document.querySelectorAll('#result .display-formula').length"))
                assertEquals("true", evaluate(scenario, inline.second, "document.getElementById('result').textContent.includes('цена ${'$'}5 и ${'$'}10')"))
                assertEquals("0", evaluate(scenario, inline.second, "document.querySelectorAll('#result [onclick],#result iframe,#result img').length"))
            } finally { scenario.onActivity { inline.first.dismiss() } }
            val graph = "flowchart TD\nA[Science] --> B[Result]"
            val diagram = readyPreview(scenario, ScientificRendererDocument.mermaid(graph), graph, "mermaid")
            try {
                assertEquals("Mermaid must produce one SVG", "1", evaluate(scenario, diagram.second, "document.querySelectorAll('#result svg').length"))
                assertEquals("0", evaluate(scenario, diagram.second, "document.querySelectorAll('#result script,#result foreignObject').length"))
            } finally { scenario.onActivity { diagram.first.dismiss() } }
        } finally { scenario.close() }
    }
}
