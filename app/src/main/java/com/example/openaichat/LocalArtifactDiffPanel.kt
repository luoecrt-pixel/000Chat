package com.example.openaichat

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future

/** A bounded presentation of app-owned patch metadata; full file versions stay untouched. */
class LocalArtifactDiffPanel(context: Context, annotation: JSONObject) : LinearLayout(context) {
    internal class PreviewException(message: String) : IOException(message)
    internal data class DiffLine(val text: String, val added: Boolean?)
    internal data class Preview(val lines: List<DiffLine>, val limited: Boolean)
    companion object {
        private val readers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16))
        internal const val MAX_SIDECAR_BYTES = 256 * 1024
        internal fun readPreview(context: Context, path: String, expectedSha: String): Preview {
            if (!expectedSha.matches(Regex("[0-9a-f]{64}"))) throw PreviewException("Версия файла не подтверждена")
            val root = File(context.filesDir, "generated/patch-diffs").canonicalFile
            val owned = context.filesDir.canonicalPath + File.separator
            val file = File(path).canonicalFile
            if (!root.path.startsWith(owned) || file.parentFile != root || file.extension != "json" || !file.isFile)
                throw PreviewException("Изменения недоступны")
            if (file.length() > MAX_SIDECAR_BYTES) throw PreviewException("Diff больше лимита превью 256 КиБ. Полные версии файлов сохранены.")
            val bytes = ByteArrayOutputStream()
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw PreviewException("Просмотр отменён")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_SIDECAR_BYTES) throw PreviewException("Diff больше лимита превью 256 КиБ. Полные версии файлов сохранены.")
                    bytes.write(buffer, 0, count)
                }
            }
            val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            val metadata = JSONObject(raw)
            if (metadata.optInt("version") != 1 || JsonProtocol.string(metadata, "result_sha256") != expectedSha)
                throw PreviewException("Diff относится к другой версии файла")
            val hunks = metadata.getJSONArray("hunks")
            val lines = mutableListOf<DiffLine>()
            var remaining = 64_000
            var limited = false
            fun append(value: String, added: Boolean?) {
                if (remaining <= 0 || lines.size >= 600) { limited = true; return }
                val shown = value.take(remaining)
                if (shown.length < value.length) limited = true
                lines += DiffLine(shown, added); remaining -= shown.length
            }
            for (index in 0 until hunks.length()) {
                if (Thread.currentThread().isInterrupted) throw PreviewException("Просмотр отменён")
                if (remaining <= 0 || lines.size >= 600) { limited = true; break }
                val hunk = hunks.getJSONObject(index)
                append("@@ Исходная строка ${hunk.optInt("line", 1)} @@", null)
                for ((key, added) in listOf("search" to false, "replace" to true)) {
                    val value = hunk.getString(key)
                    if (value.isEmpty()) continue
                    for (line in value.lineSequence()) {
                        if (remaining <= 0 || lines.size >= 600) { limited = true; break }
                        append((if (added) "+ " else "- ") + line, added)
                    }
                }
            }
            return Preview(lines, limited)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val path = JsonProtocol.string(annotation, "patch_diff_path")
    private val expectedSha = JsonProtocol.string(annotation, "sha256")
    private val theme = AppThemePalette.byId(context.getSharedPreferences("app", 0).getString("app_theme", "dark"))
    private var expanded = false
    private var loaded = false
    private var detached = false
    private var revision = 0L
    private var job: Future<*>? = null
    private val body = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val toggle = Button(context).apply {
        text = "▸ Изменения"; textSize = 12f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        val density = resources.displayMetrics.density
        setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt()); backgroundTintList = null
        background = GradientDrawable().apply { setColor(theme.buttonBg); cornerRadius = 12 * density; setStroke(density.toInt().coerceAtLeast(1), theme.surfaceBorder) }
        setTextColor(theme.accent); tag = "local-artifact-diff-toggle"
        setOnClickListener {
            expanded = !expanded; text = if (expanded) "▾ Изменения" else "▸ Изменения"
            body.visibility = if (expanded) VISIBLE else GONE
            if (expanded && !loaded && job == null) load()
        }
    }
    init { orientation = VERTICAL; addView(toggle); addView(body) }
    private fun label(value: CharSequence) = TextView(context).apply {
        text = value; textSize = 12f; setTextColor(theme.textPrimary); setTextIsSelectable(true)
    }
    private fun load() {
        val token = ++revision
        body.removeAllViews(); body.addView(label("Читаю изменения…"))
        job = try { readers.submit {
            val result = runCatching { readPreview(context, path, expectedSha) }
            handler.post {
                if (detached || revision != token) return@post
                job = null; loaded = true; body.removeAllViews()
                result.fold(onSuccess = { preview ->
                    val styled = SpannableStringBuilder()
                    preview.lines.forEach { line ->
                        val start = styled.length; styled.append(line.text).append('\n')
                        line.added?.let { added ->
                            styled.setSpan(ForegroundColorSpan(if (added) theme.diffAddedText else theme.errorText), start, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            styled.setSpan(BackgroundColorSpan(if (added) theme.diffAddedBg else theme.errorBg), start, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    body.addView(HorizontalScrollView(context).apply {
                        addView(label(styled).apply { typeface = Typeface.MONOSPACE; setHorizontallyScrolling(true) })
                    })
                    if (preview.limited) body.addView(label("Показана часть изменений (лимит превью). Полные версии файлов сохранены."))
                }, onFailure = { error ->
                    body.addView(label(if (error is PreviewException) error.message ?: "Изменения недоступны" else "Не удалось прочитать изменения. Сам файл сохранён."))
                })
                requestLayout()
            }
        } } catch (_: RejectedExecutionException) {
            body.removeAllViews(); body.addView(label("Просмотр занят. Сверните и откройте изменения ещё раз."))
            null
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); detached = false
        if (expanded && !loaded && job == null) load()
    }
    override fun onDetachedFromWindow() {
        detached = true; revision++; job?.cancel(true); job = null
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
