package com.example.openaichat

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Local, explicit evidence checks; no network or model call, with lifecycle-owned work. */
class LocalEvidencePanel(context: Context, message: JSONObject,
    private val sources: () -> List<OpenRouterLocalAttachment>,
    private val onContentChanged: (() -> Unit)? = null) : LinearLayout(context) {
    companion object { private val workers = Executors.newFixedThreadPool(2) }
    private val main = Handler(Looper.getMainLooper())
    private val theme = AppThemePalette.byId(context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark"))
    private val jobs = mutableListOf<Future<*>>()
    private val dialogs = mutableListOf<AlertDialog>()
    @Volatile private var generation = 0
    private var loading = true
    private var available = emptyList<OpenRouterLocalAttachment>()
    fun isLoading(): Boolean = loading
    init {
        orientation = VERTICAL
        val token = generation
        jobs.add(workers.submit {
            val result = runCatching { sources() to LocalEvidenceVerifier.coverage(message) }
            main.post {
                if (token != generation) return@post
                loading = false; removeAllViews()
                result.onSuccess { (files, coverage) ->
                    available = files
                    for (entry in coverage.filter { it.partial }) {
                        val name = files.firstOrNull { it.id == entry.fileId }?.name ?: "Файл"
                        val amount = entry.percent?.let { " ($it%)" } ?: " (доля неизвестна)"
                        addView(label("⚠️ $name: по журналу прочитана только часть$amount. Точный подсчёт всей таблицы не подтверждён."))
                    }
                    if (files.isNotEmpty()) addView(action("🔍 Проверить цитаты по файлам") { quoteDialog() })
                }
                visibility = if (childCount == 0) GONE else VISIBLE
                requestLayout(); onContentChanged?.invoke()
            }
        })
    }
    private fun label(value: String) = TextView(context).apply {
        text = value; textSize = 12f; setTextColor(theme.textSecondary); setTextIsSelectable(true)
        setPadding(8, 6, 8, 6)
    }
    private fun action(value: String, work: () -> Unit) = Button(context).apply {
        text = value; textSize = 12f; isAllCaps = false
        setTextColor(theme.accent); setBackgroundColor(theme.buttonBg)
        setOnClickListener { work() }
    }
    private fun themed(dialog: AlertDialog) {
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show(); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.cardBg))
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL))
            dialog.getButton(which)?.setTextColor(theme.accent)
    }
    private fun quoteDialog() {
        val box = LinearLayout(context).apply { orientation = VERTICAL; setBackgroundColor(theme.cardBg) }
        box.addView(label("Точное совпадение текста в локальных файлах. Это не проверка достоверности выводов."))
        val input = EditText(context).apply {
            hint = "Вставьте точную цитату"; setTextColor(theme.textPrimary); setHintTextColor(theme.hintText)
            setBackgroundColor(theme.inputBg); filters = arrayOf(InputFilter.LengthFilter(4096)); minLines = 2
        }
        val results = LinearLayout(context).apply { orientation = VERTICAL }
        box.addView(input); box.addView(results)
        val dialog = AlertDialog.Builder(context).setCustomTitle(label("Проверка цитаты").apply { setTextColor(theme.textPrimary) }).setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton("Проверить", null).setNegativeButton("Закрыть", null).create()
        themed(dialog)
        var checkGeneration = 0
        var quoteJob: Future<*>? = null
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val query = input.text.toString(); if (query.isEmpty()) return@setOnClickListener
            val token = generation; val check = ++checkGeneration
            results.removeAllViews(); results.addView(label("Проверка…"))
            quoteJob?.cancel(true)
            quoteJob = workers.submit {
                val result = runCatching { LocalEvidenceVerifier.quotes(query, available) { token == generation && !Thread.currentThread().isInterrupted } }
                main.post {
                    if (token != generation || check != checkGeneration || !dialog.isShowing) return@post
                    results.removeAllViews()
                    result.onSuccess { report ->
                        results.addView(label(report.label))
                        if (report.incomplete && report.matches.isNotEmpty()) results.addView(label("Некоторые исходники недоступны; совпадения ниже подтверждены отдельно."))
                        report.matches.forEach { match -> results.addView(action("✓ ${match.source.name} · ${match.offset}") { showMatch(match) }) }
                    }.onFailure { results.addView(label("Проверка недоступна: исходник отсутствует, изменён или не читается как UTF-8.")) }
                }
            }
            quoteJob?.let(jobs::add)
        }
        dialog.setOnDismissListener { checkGeneration++; quoteJob?.cancel(true); dialogs.remove(dialog) }
    }
    private fun showMatch(match: LocalEvidenceVerifier.Match) {
        val token = generation
        val text = label("Открытие…").apply { typeface = Typeface.MONOSPACE }
        val dialog = AlertDialog.Builder(context).setCustomTitle(label(match.source.name).apply { setTextColor(theme.textPrimary) }).setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton("Далее", null).setNegativeButton("Закрыть", null).create()
        themed(dialog)
        val pages = LocalTextPageIndex(); var offset = match.offset; var reading = false
        var pageJob: Future<*>? = null
        dialog.setOnDismissListener { pageJob?.cancel(true); dialogs.remove(dialog) }
        fun load() {
            if (reading) return
            reading = true
            val start = offset
            pageJob = workers.submit {
                val page = runCatching {
                    OpenRouterAttachmentPreflight.verifySources(listOf(match.source))
                    pages.range(LocalEvidenceVerifier.textFile(match.source), start, 16000) { token == generation && !Thread.currentThread().isInterrupted }
                }
                main.post {
                    reading = false
                    if (token != generation || !dialog.isShowing) return@post
                    page.onSuccess {
                        text.text = "Смещение $start (UTF-16)\n\n" + it.optString("content")
                        offset = it.optLong("next_offset", start)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !it.optBoolean("eof", true)
                    }.onFailure { text.text = "Исходник недоступен или изменён."; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false }
                }
            }
            pageJob?.let(jobs::add)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { load() }; load()
    }
    override fun onDetachedFromWindow() {
        generation++; loading = false
        jobs.forEach { it.cancel(true) }; jobs.clear()
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear()
        super.onDetachedFromWindow()
    }
}
