package com.example.openaichat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

enum class ScientificSurface { INLINE, MATH, DIAGRAM }

/** One disposable, isolated renderer; a failed preview always restores the original text. */
open class ScientificMathText(context: Context, private val original: TextView, private val autoPreview: Boolean = false,
    private val surface: ScientificSurface = ScientificSurface.INLINE, private val document: () -> String) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var formatted = false
    private var retryRequired = false
    private val theme = AppThemePalette.byId(
        context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark")
    )
    private val surfaceColor = when (surface) {
        ScientificSurface.MATH -> theme.mathBg
        ScientificSurface.DIAGRAM -> theme.diagramBg
        ScientificSurface.INLINE -> theme.assistantBubbleBg
    }
    private val ink = when (surface) {
        ScientificSurface.MATH -> theme.mathText
        ScientificSurface.DIAGRAM -> theme.diagramText
        ScientificSurface.INLINE -> theme.textPrimary
    }
    private val notice = TextView(context).apply { textSize = 12f; setTextColor(theme.textSecondary); visibility = View.GONE }
    private val toggle = Button(context).apply {
        text = "Формулы в тексте"; textSize = 12f; isAllCaps = false
        minWidth = 0; minimumWidth = 0
        minHeight = (40 * resources.displayMetrics.density).toInt(); minimumHeight = minHeight
        setTextColor(theme.accent); backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf((theme.accent and 0xFFFFFF) or 0x22000000),
            GradientDrawable().apply { setColor(theme.buttonBg); cornerRadius = 12 * resources.displayMetrics.density
                setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), theme.surfaceBorder) }, null)
        setOnClickListener { typeset() }
    }
    private val timeout = Runnable { source("Форматирование не завершено. Исходник сохранён; можно повторить.") }
    init {
        orientation = VERTICAL; setBackgroundColor(surfaceColor); original.setTextColor(ink)
        addView(toggle); addView(notice); addView(original)
        if (autoPreview) typeset()
    }
    private fun destroyRenderer() {
        handler.removeCallbacks(timeout)
        val old = web ?: return
        web = null; removeView(old); old.stopLoading(); old.destroy()
    }

    override fun onDetachedFromWindow() {
        destroyRenderer()
        original.visibility = View.VISIBLE
        formatted = false
        toggle.text = if (retryRequired) "Повторить превью" else "Превью"
        toggle.visibility = View.VISIBLE
        if (!retryRequired) { notice.text = ""; notice.visibility = View.GONE }
        super.onDetachedFromWindow()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (autoPreview && web == null && !formatted && !retryRequired) typeset()
    }
    private fun source(message: String = "") {
        formatted = false; destroyRenderer(); original.visibility = View.VISIBLE
        retryRequired = message.isNotEmpty()
        toggle.text = if (message.isEmpty()) "Превью" else "Повторить превью"
        toggle.visibility = View.VISIBLE; notice.text = message
        notice.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun typeset() {
        destroyRenderer()
        retryRequired = false
        val html = try { document() } catch (_: Exception) { source("Блок велик или несовместим с оформлением. Исходник доступен полностью."); return }
        formatted = true; notice.text = if (surface == ScientificSurface.DIAGRAM) "Готовлю диаграмму…" else "Готовлю формулы…"
        notice.visibility = View.VISIBLE; toggle.visibility = View.GONE
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
                            notice.text = if (pixels > limit) "Большой блок: прокрутка внутри. Полный исходник сохранён." else ""
                            notice.visibility = if (pixels > limit) View.VISIBLE else View.GONE
                            handler.removeCallbacks(timeout)
                        } catch (_: Exception) { source("Не удалось определить размер блока. Показан исходник.") }
                    }
                }
            }
        }, { view -> if (web === view && formatted) source("Превью остановлено. Исходник доступен полностью.") })
        web = renderer; renderer.visibility = View.INVISIBLE; renderer.setBackgroundColor(surfaceColor)
        addView(renderer, LayoutParams(-1, (48 * resources.displayMetrics.density).toInt()))
        handler.postDelayed(timeout, 12_000)
        renderer.loadUrl(ScientificRendererDocument.DOCUMENT_URL)
    }
}
