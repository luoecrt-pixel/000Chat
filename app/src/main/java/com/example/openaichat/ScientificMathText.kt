package com.example.openaichat

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/** Explicit inline typesetting: one disposable, isolated renderer only while the user views it. */
open class ScientificMathText(context: Context, private val original: TextView, autoPreview: Boolean = false, private val document: () -> String) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var formatted = false
    private val theme = AppThemePalette.byId(
        context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark")
    )
    private val notice = TextView(context).apply { textSize = 12f; setTextColor(theme.textSecondary) }
    private val toggle = Button(context).apply {
        text = "Формулы в тексте"; textSize = 12f
        setOnClickListener { if (formatted) source() else typeset() }
    }
    private val timeout = Runnable { source("Форматирование не завершено. Исходник сохранён; можно повторить.") }
    init { orientation = VERTICAL; setBackgroundColor(theme.assistantBubbleBg); addView(toggle); addView(notice); addView(original); if (autoPreview) typeset() }
    private fun destroyRenderer() {
        handler.removeCallbacks(timeout)
        val old = web ?: return
        web = null; removeView(old); old.stopLoading(); old.destroy()
    }

    override fun onDetachedFromWindow() {
        destroyRenderer()
        original.visibility = View.VISIBLE
        formatted = false
        toggle.text = "Превью"
        super.onDetachedFromWindow()
    }
    private fun source(message: String = "") {
        formatted = false; destroyRenderer(); original.visibility = View.VISIBLE
        toggle.text = "Превью"; notice.text = message
    }
    private fun typeset() {
        destroyRenderer()
        val html = try { document() } catch (_: Exception) { source("Блок велик или несовместим с оформлением. Исходник доступен полностью."); return }
        formatted = true; notice.text = "Готовлю формулы…"; toggle.text = "Исходный код"
        val renderer = ScientificRendererPreview.webView(context, html, { view, title ->
            if (web === view && formatted) when (title) {
                "renderer:failed" -> source("Не удалось оформить блок. Показан исходник.")
                "renderer:ready" -> {
                    // Owned numeric expression; no bridge, model code, credentials or file access.
                    view.evaluateJavascript("JSON.stringify([Math.ceil(document.body.scrollHeight),window.innerWidth])") { result ->
                        if (web === view && formatted) try {
                            val decoded = org.json.JSONTokener(result).nextValue() as? String ?: throw IllegalArgumentException("Invalid dimensions")
                            val dimensions = JSONArray(decoded)
                            val height = dimensions.getDouble(0); val width = dimensions.getDouble(1)
                            require(height.isFinite() && width.isFinite() && height > 0 && width > 0 && view.width > 0)
                            val pixels = height * view.width / width + 8 * resources.displayMetrics.density
                            val limit = resources.displayMetrics.heightPixels.coerceAtLeast(1) * 3L
                            view.layoutParams = LayoutParams(-1, pixels.coerceIn(1.0, limit.toDouble()).toInt())
                            view.visibility = View.VISIBLE; original.visibility = View.GONE
                            notice.text = if (pixels > limit) "Большой блок: прокрутка внутри. Полный исходник сохранён." else "Формулы оформлены · исходник сохранён"
                            handler.removeCallbacks(timeout)
                        } catch (_: Exception) { source("Не удалось определить размер блока. Показан исходник.") }
                    }
                }
            }
        }, { view -> if (web === view && formatted) source("Превью остановлено. Исходник доступен полностью.") })
        web = renderer; renderer.visibility = View.INVISIBLE
        addView(renderer, LayoutParams(-1, (48 * resources.displayMetrics.density).toInt()))
        handler.postDelayed(timeout, 12_000)
        renderer.loadUrl(ScientificRendererDocument.DOCUMENT_URL)
    }
}
