package com.example.openaichat

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream

/** Separate from model HTML preview: JS has access only to pinned APK renderer assets. */
object ScientificRendererPreview {
    fun available(context: Context): Boolean = try {
        context.assets.open("response-renderer/katex/katex.min.js").use { }
        context.assets.open("response-renderer/mermaid/mermaid.esm.min.mjs").use { }
        true
    } catch (_: Exception) { false }

    internal fun webView(context: Context, document: String, state: (WebView, String?) -> Unit, failed: (WebView) -> Unit): WebView = WebView(context).apply {
        setBackgroundColor(AppThemePalette.byId(context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark")).assistantBubbleBg)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.domStorageEnabled = false; settings.blockNetworkLoads = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) { state(view, title) }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                // loadDataWithBaseURL also intercepts a data: main frame on some WebViews.
                // Serve only this dialog's owned document at an exact local HTTPS URL instead.
                if (request.method == "GET" && request.isForMainFrame && request.url.toString() == ScientificRendererDocument.DOCUMENT_URL) {
                    return WebResourceResponse("text/html", "UTF-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
                        ByteArrayInputStream(ScientificRendererDocument.themed(document, AppThemePalette.byId(context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark"))).toByteArray(Charsets.UTF_8)))
                }
                val path = if (request.method == "GET" && !request.isForMainFrame) ScientificRendererDocument.assetPath(request.url) else null
                if (path != null) try {
                    val extension = path.substringAfterLast('.')
                    val mime = when (extension) { "js", "mjs" -> "application/javascript"; "css" -> "text/css"; "woff" -> "font/woff"; "woff2" -> "font/woff2"; else -> "font/ttf" }
                    return WebResourceResponse(mime, if (extension in setOf("js", "mjs", "css")) "UTF-8" else null,
                        200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), context.assets.open(path))
                } catch (_: Exception) { }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) failed(view)
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { failed(view); return true }
        }
    }

    fun create(context: Context, document: String, source: String, closed: (AlertDialog) -> Unit): AlertDialog {
        val density = context.resources.displayMetrics.density
        val handler = Handler(Looper.getMainLooper())
        val theme = AppThemePalette.byId(context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark"))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(theme.assistantBubbleBg) }
        val notice = TextView(context).apply { text = "Готовлю офлайн-превью…"; setTextColor(theme.textPrimary) }
        val original = TextView(context).apply {
            text = source; typeface = Typeface.MONOSPACE; textSize = 14f
            setTextColor(theme.textPrimary); setTextIsSelectable(true); setHorizontallyScrolling(true)
        }
        val originalScroll = HorizontalScrollView(context).apply { addView(original); visibility = View.GONE }
        var destroyed = false
        var ready = false
        lateinit var web: WebView
        fun destroyWeb() {
            if (destroyed) return
            destroyed = true
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading(); web.destroy()
        }
        fun fallback(message: String) {
            notice.text = message
            originalScroll.visibility = View.VISIBLE
            destroyWeb()
        }
        val timeout = Runnable { if (!ready && !destroyed) fallback("Превью не готово. Исходник сохранён; можно повторить открытие.") }
        web = webView(context, document, { _, title ->
            if (!destroyed) when (title) {
                "renderer:ready" -> { ready = true; handler.removeCallbacks(timeout); notice.text = "Офлайн-превью · исходник сохранён" }
                "renderer:failed" -> { handler.removeCallbacks(timeout); fallback("Не удалось оформить блок. Показан исходник.") }
            }
        }, { view -> if (!destroyed && view === web) { handler.removeCallbacks(timeout); fallback("Процесс превью остановлен или недоступен. Исходник доступен полностью.") } })
        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(Button(context).apply { text = "Исходник"; setOnClickListener { if (!destroyed) web.visibility = View.GONE; originalScroll.visibility = View.VISIBLE } })
        tabs.addView(Button(context).apply { text = "Превью"; setOnClickListener { if (!destroyed) { web.visibility = View.VISIBLE; originalScroll.visibility = View.GONE } } })
        column.addView(notice); column.addView(tabs); column.addView(originalScroll)
        column.addView(web, LinearLayout.LayoutParams(-1, (360 * density).toInt()))
        val dialog = AlertDialog.Builder(context).setTitle("Формулы и диаграммы").setView(column).setPositiveButton("Закрыть", null).create()
        dialog.setOnDismissListener { handler.removeCallbacks(timeout); destroyWeb(); closed(dialog) }
        dialog.setOnShowListener {
            handler.postDelayed(timeout, 12_000)
            web.loadUrl(ScientificRendererDocument.DOCUMENT_URL)
        }
        return dialog
    }
}
