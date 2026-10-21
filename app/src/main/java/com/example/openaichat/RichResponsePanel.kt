package com.example.openaichat

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.html.tag.SubScriptHandler
import io.noties.markwon.html.tag.SuperScriptHandler
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Provider-neutral presentation, with raw source retained by the adapter and database. */
class RichResponsePanel(
    context: Context,
    private val saveFile: (ResponseBlockParser.Block.File) -> Unit,
    private val continueAnswer: () -> Unit,
    private val detailDepth: Int = 0,
    private val onAsyncLayoutCommitted: (() -> Unit)? = null,
    private val evidenceSources: (() -> List<OpenRouterLocalAttachment>)? = null
) : LinearLayout(context) {
    companion object {
        private val parsers = Executors.newFixedThreadPool(2)
        @Volatile internal var beforeAsyncParseForTests: (() -> Unit)? = null
    }
    private val handler = Handler(Looper.getMainLooper())
    private var latest: ChatBubbleItem? = null
    private var submissionRevision = 0L
    private var scheduled = false
    private val rendered = mutableListOf<Pair<ResponseBlockParser.Block, View>>()
    private var footer: View? = null
    private var evidencePanel: LocalEvidencePanel? = null
    private val dialogs = mutableListOf<AlertDialog>()
    private var parseJob: Future<*>? = null
    private var parseJobVersion: Long? = null
    private var asyncLayoutPending = false
    private val parseVersion = AtomicLong()
    private var parserSession = ResponseBlockParser.Session()
    private var detached = false
    private var renderId: String? = null
    private data class TableState(var shown: Int = 50, var scrollX: Int = 0)
    private val tableStates = mutableMapOf<Int, TableState>()
    private data class PageState(var page: Int = 0, var followTail: Boolean = false)
    private val pageStates = mutableMapOf<Int, PageState>()
    private data class DetailsState(var expanded: Boolean, var body: RichResponsePanel? = null)
    private val detailStates = mutableMapOf<Int, DetailsState>()
    private val formulaRanges = mutableMapOf<Int, List<MathDelimiterScanner.Formula>>()
    // Assets enable explicit actions even when automatic preview is disabled.
    private val scientificAssets = ScientificRendererPreview.available(context)
    private var autoScientificPreview = false
    private val refresh = Runnable { scheduled = false; latest?.let(::render) }
    private val markwon = Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(HtmlPlugin.create { plugin -> plugin.excludeDefaults(true).allowNonClosedTags(false)
            .addHandler(SubScriptHandler()).addHandler(SuperScriptHandler()) })
        .usePlugin(TaskListPlugin.create(currentTheme().accent, currentTheme().textSecondary, currentTheme().onAccent))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: io.noties.markwon.core.MarkwonTheme.Builder) {
                val theme = currentTheme()
                builder.codeTextColor(theme.codeText).codeBackgroundColor(theme.codeBg)
                    .blockQuoteColor(theme.mathBorder).linkColor(theme.accent)
            }
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver(LinkResolver { _, link -> openLink(link) })
            }
        }).build()

    init { orientation = VERTICAL; tag = "rich-response" }
    private fun currentTheme(): ThemeColors = AppThemePalette.byId(
        context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark")
    )
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun text(value: CharSequence = "") = TextView(context).apply {
        val theme = currentTheme()
        text = value; textSize = 16f; setTextColor(theme.textPrimary)
        setLinkTextColor(theme.accent); setPadding(0, dp(5), 0, dp(5))
        setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance()
    }
    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        val theme = currentTheme()
        text = label; textSize = 12f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = dp(40); minimumHeight = dp(40)
        setPadding(dp(10), dp(5), dp(10), dp(5)); setTextColor(theme.accent)
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf((theme.accent and 0xFFFFFF) or 0x22000000),
            shape(theme.buttonBg, theme.surfaceBorder), null)
        layoutParams = LayoutParams(-2, -2).apply { marginEnd = dp(5); topMargin = dp(3); bottomMargin = dp(3) }
        setOnClickListener { action() }
    }
    private fun shape(fill: Int, border: Int) = GradientDrawable().apply {
        setColor(fill); setStroke(dp(1).coerceAtLeast(1), border)
        cornerRadii = floatArrayOf(16f, 16f, 10f, 10f, 18f, 18f, 10f, 10f)
            .map { it * resources.displayMetrics.density }.toFloatArray()
    }
    private fun card(fill: Int, border: Int = currentTheme().surfaceBorder) = column().apply {
        background = shape(fill, border)
        setPadding(dp(10), dp(5), dp(10), dp(7))
        layoutParams = LayoutParams(-1, -2).apply { topMargin = dp(5); bottomMargin = dp(5) }
    }
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL }
    private fun copy(value: String) {
        try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Ответ", value))
        } catch (_: RuntimeException) { previewNotice("Android не принял текст в буфер обмена. Для большого ответа используйте сохранение в файл.") }
    }
    private fun openLink(link: String) {
        if (!ResponseRenderSafety.externalLink(link)) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } catch (_: Exception) { }
    }

    fun submit(item: ChatBubbleItem) {
        detached = false
        latest = item
        submissionRevision++
        if (renderId != item.id) {
            parseVersion.incrementAndGet()
            parseJob?.cancel(true); parseJob = null; parseJobVersion = null; asyncLayoutPending = false
            handler.removeCallbacks(refresh); scheduled = false
            parserSession = ResponseBlockParser.Session()
            renderId = item.id; rendered.clear(); tableStates.clear(); pageStates.clear(); detailStates.clear(); formulaRanges.clear(); removeAllViews(); footer = null
        }
        if (rendered.isEmpty() && parseJob == null) { render(item); return }
        // Throttle, rather than reset a debounce forever on a continuous token stream.
        if (!scheduled) { scheduled = true; handler.postDelayed(refresh, 120L) }
    }

    /** Parsing and committing children both precede the measurable layout used by scroll anchors. */
    fun isAsyncParsePending(): Boolean = parseJob != null || asyncLayoutPending || evidencePanel?.isLoading() == true ||
        (scheduled && (latest?.text?.length ?: 0) > 32_000)

    private fun render(item: ChatBubbleItem) {
        if (parseJob != null) return // Finish one snapshot while incoming snapshots are conflated.
        val autoPreview = scientificAssets && context.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("response_scientific_preview", true)
        if (autoPreview != autoScientificPreview) { rendered.clear(); formulaRanges.clear(); removeAllViews(); footer = null; asyncLayoutPending = false; autoScientificPreview = autoPreview }
        val version = parseVersion.incrementAndGet()
        val submittedRevision = submissionRevision
        val session = parserSession
        val previous = rendered.map { it.first }
        val previousFormulas = formulaRanges.toMap()
        fun cancelled() = detached || parseVersion.get() != version || Thread.currentThread().isInterrupted
        fun prepare(): Triple<List<ResponseBlockParser.Block>, Int, Map<Int, List<MathDelimiterScanner.Formula>>> {
            val blocks = session.update(item.text, ::cancelled)
            var prefix = 0
            while (prefix < blocks.size && prefix < previous.size) {
                if (cancelled()) throw CancellationException()
                if (previous[prefix] !== blocks[prefix] && previous[prefix] != blocks[prefix]) break
                prefix++
            }
            val formulas = mutableMapOf<Int, List<MathDelimiterScanner.Formula>>()
            if (scientificAssets) blocks.forEachIndexed { index, block ->
                if (cancelled()) throw CancellationException()
                if (block is ResponseBlockParser.Block.Prose && block.raw.length <= 100_000) {
                    formulas[index] = if (index < prefix) previousFormulas[index].orEmpty() else MathDelimiterScanner.scan(block.raw, ::cancelled)
                }
            }
            return Triple(blocks, prefix, formulas)
        }
        if (item.text.length > 32_000) {
            fun releaseOwnedJob(): Boolean {
                if (parseJobVersion != version) return false
                parseJob = null
                parseJobVersion = null
                return true
            }
            fun scheduleNewerSnapshot() {
                if (!detached && latest != null && submissionRevision != submittedRevision && !scheduled && parseJob == null) {
                    scheduled = true
                    handler.post(refresh)
                }
            }
            // Publish the job before starting it; a fast parser never races Future assignment.
            val job = FutureTask<Unit> {
                val prepared = try { beforeAsyncParseForTests?.invoke(); prepare() }
                catch (_: CancellationException) {
                    handler.post {
                        if (releaseOwnedJob()) {
                            asyncLayoutPending = false
                            scheduleNewerSnapshot()
                        }
                    }
                    return@FutureTask
                }
                catch (_: Exception) { Triple(listOf<ResponseBlockParser.Block>(ResponseBlockParser.Block.Prose(item.text)), 0, emptyMap()) }
                handler.post {
                    val owned = releaseOwnedJob()
                    if (owned && !detached && parseVersion.get() == version && latest?.id == item.id) {
                        asyncLayoutPending = true
                        applyBlocks(item, prepared.first, prepared.second, prepared.third)
                        scheduleAsyncLayoutCommitCheck(version)
                        if (submissionRevision != submittedRevision && !scheduled) { scheduled = true; handler.postDelayed(refresh, 120L) }
                    } else if (owned) {
                        scheduleNewerSnapshot()
                    }
                }
            }
            parseJobVersion = version
            parseJob = job
            parsers.execute(job)
        } else {
            asyncLayoutPending = false
            try { val prepared = prepare(); if (!cancelled()) applyBlocks(item, prepared.first, prepared.second, prepared.third) }
            catch (_: CancellationException) { /* A new snapshot owns this panel. */ }
            catch (_: Exception) { if (!cancelled()) applyBlocks(item, listOf(ResponseBlockParser.Block.Prose(item.text)), 0) }
        }
    }

    private fun scheduleAsyncLayoutCommitCheck(version: Long) {
        requestLayout()
        handler.post {
            if (detached || parseVersion.get() != version || parseJob != null || !asyncLayoutPending) return@post
            if (width > 0 && height > 0 && !isLayoutRequested) {
                asyncLayoutPending = false
                onAsyncLayoutCommitted?.invoke()
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (asyncLayoutPending && parseJob == null && width > 0 && height > 0) {
            asyncLayoutPending = false
            onAsyncLayoutCommitted?.invoke()
        }
    }

    private fun applyBlocks(item: ChatBubbleItem, blocks: List<ResponseBlockParser.Block>, prefix: Int,
        formulas: Map<Int, List<MathDelimiterScanner.Formula>> = emptyMap()) {
        formulaRanges.clear(); formulaRanges.putAll(formulas)
        evidencePanel = null
        footer?.let(::removeView); footer = null
        while (rendered.size > prefix) removeView(rendered.removeAt(rendered.lastIndex).second)
        for (index in prefix until blocks.size) {
            val block = blocks[index]
            val view = try { blockView(block, index) } catch (error: Exception) {
                column().apply { addView(text("Не удалось оформить блок; показан исходник.")); addView(pagedText(block.raw, index, false)) }
            }
            rendered.add(block to view); addView(view)
        }
        val extra = column()
        item.message?.optJSONArray("tool_trace")?.let { trace ->
            if (trace.length() > 20) extra.addView(text("Последние 20 из ${trace.length()} действий").apply { textSize = 12f })
            for (index in maxOf(0, trace.length() - 20) until trace.length()) trace.optJSONObject(index)?.let { entry ->
                val state = JsonProtocol.nonBlank(entry, "status") ?: if (entry.optBoolean("ok", true)) "готово" else "ошибка"
                val theme = currentTheme()
                val failed = !entry.optBoolean("ok", true) || state.lowercase() in setOf("error", "failed", "ошибка")
                extra.addView(card(if (failed) theme.errorBg else theme.toolBg).apply {
                    addView(text("${JsonProtocol.string(entry, "name")} · $state").apply {
                        textSize = 12f; setTextColor(if (failed) theme.errorText else theme.toolText)
                    })
                })
            }
        }
        val annotations = item.annotations ?: item.message?.optJSONArray("annotations")
        val links = linkedSetOf<String>()
        for (index in 0 until (annotations?.length() ?: 0)) {
            val raw = annotations?.optJSONObject(index) ?: continue
            val citation = raw.optJSONObject("url_citation") ?: raw
            val url = citation.optString("url")
            if (ResponseRenderSafety.externalLink(url) && links.add(url))
                extra.addView(button(citation.optString("title").ifBlank { url }) { openLink(url) })
        }
        val finish = item.message?.let { JsonProtocol.text(it, "finish_reason") }
        val notice = item.message?.let { JsonProtocol.nonBlank(it, "status_notice") ?: JsonProtocol.nonBlank(it, "error") }
        notice?.let { extra.addView(text(it)) }
        if (item.text.isBlank() && notice == null) {
            val currentStatus = item.message?.let { JsonProtocol.nonBlank(it, "status") }
            if (currentStatus == "completed") extra.addView(text("Ответ завершён без видимого текста."))
            else if (currentStatus != null) extra.addView(text(ResponseStatusLabel.restored(currentStatus)))
        }
        if (finish == "length") {
            extra.addView(text("Ответ прерван. Продолжение создаст новый запрос."))
            extra.addView(button("Продолжить", continueAnswer))
        }
        if (!item.user && item.message?.optString("status") == "completed" && evidenceSources != null) {
            val evidenceVersion = parseVersion.get()
            val panel = LocalEvidencePanel(context, item.message, evidenceSources) {
                if (!detached && parseVersion.get() == evidenceVersion) {
                    asyncLayoutPending = true
                    scheduleAsyncLayoutCommitCheck(evidenceVersion)
                }
            }
            evidencePanel = panel
            extra.addView(panel)
        }
        if (extra.childCount > 0) { footer = extra; addView(extra) }
    }

    private fun blockView(block: ResponseBlockParser.Block, index: Int): View = when (block) {
        is ResponseBlockParser.Block.Prose -> if (block.raw.length > 100_000) pagedText(block.raw, index, false) else column().apply {
            val formulas = formulaRanges[index].orEmpty()
            val native = markdown(block.raw)
            if (formulas.isNotEmpty()) addView(ScientificMathText(context, native, autoPreview = autoScientificPreview) {
                val prepared = MathTextTokens.prepare(block.raw, formulas)
                val node = markwon.parse(prepared.markdown); safeTree(node)
                val rendered = bridgeMarkwonSpansForHtml(markwon.render(node))
                ScientificRendererDocument.mathInText(android.text.Html.toHtml(rendered, android.text.Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL), prepared)
            }) else addView(native)
            if (formulas.isNotEmpty() && !autoScientificPreview)
                addView(button("Превью формул (${formulas.size})") { formulaPreview(formulas) })
        }
        is ResponseBlockParser.Block.Code -> code(block, index)
        is ResponseBlockParser.Block.Details -> details(block, index)
        is ResponseBlockParser.Block.Table -> table(block, tableStates.getOrPut(index) { TableState() })
        is ResponseBlockParser.Block.File -> card(currentTheme().fileBg).apply {
            if (!ResponseRenderSafety.textFilename(block.name)) {
                addView(text("Имя или формат текстового файла недопустимы; показан исходник.")); addView(pagedText(block.raw, index, false))
            } else {
                addView(text("📄 ${block.name} · ${utf8Bytes(block.content)} байт · ${if (block.closed) "UTF-8" else "создаётся…"}").apply {
                    setTextColor(currentTheme().fileText)
                })
                if (block.closed) addView(row().apply {
                    addView(button("Сохранить") { saveFile(block) })
                    addView(button("Копировать") { copy(block.content) })
                })
            }
        }
    }

    private fun extractNodeText(root: Node): String {
        val out = StringBuilder()
        val stack = java.util.ArrayDeque<Node>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            when (current) {
                is Text -> out.append(current.literal)
                is org.commonmark.node.Code -> out.append(current.literal)
            }
            var child = current.lastChild
            while (child != null) {
                stack.add(child)
                child = child.previous
            }
        }
        return out.toString()
    }

    private fun bridgeMarkwonSpansForHtml(source: Spanned): Spanned {
        val out = SpannableStringBuilder(source)
        val allSpans = out.getSpans(0, out.length, Any::class.java)
        for (span in allSpans) {
            val start = out.getSpanStart(span)
            val end = out.getSpanEnd(span)
            val flags = out.getSpanFlags(span)
            if (start < 0 || end <= start) continue
            when (span.javaClass.simpleName) {
                "StrongEmphasisSpan" -> out.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, end, flags)
                "EmphasisSpan" -> out.setSpan(android.text.style.StyleSpan(Typeface.ITALIC), start, end, flags)
                "CodeSpan", "CodeBlockSpan" -> out.setSpan(android.text.style.TypefaceSpan("monospace"), start, end, flags)
                "BlockQuoteSpan" -> out.setSpan(android.text.style.QuoteSpan(), start, end, flags)
            }
        }
        return out
    }

    private fun safeTree(root: Node) {
        val pending = java.util.ArrayDeque<Node>(); pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            val literal = when (node) {
                is Image -> "[Изображение не загружено: ${extractNodeText(node).ifBlank { node.title ?: "изображение" }}]"
                is HtmlInline -> when {
                    Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).matches(node.literal) -> "\n"
                    ResponseRenderSafety.nativeInlineTag(node.literal) -> null
                    else -> node.literal
                }
                is HtmlBlock -> if (ResponseRenderSafety.nativeHtmlBlock(node.literal)) null else node.literal
                else -> null
            }
            if (literal != null) { node.insertBefore(Text(literal)); node.unlink(); continue }
            if (node is Link && !ResponseRenderSafety.externalLink(node.destination)) node.destination = ""
            var child = node.firstChild
            while (child != null) { pending.add(child); child = child.next }
        }
    }
    private fun markdown(source: String): TextView = text().apply {
        // This protects renderer work, without shortening source, response or context.
        if (source.length > 100_000) { text = source; contentDescription = "Большой фрагмент показан исходным текстом" }
        else try {
            val node = markwon.parse(source); safeTree(node)
            markwon.setParsedMarkdown(this, markwon.render(node))
            Linkify.addLinks(this, Linkify.WEB_URLS)
            val styled = SpannableStringBuilder(text)
            styled.getSpans(0, styled.length, URLSpan::class.java).forEach { span ->
                val start = styled.getSpanStart(span); val end = styled.getSpanEnd(span)
                styled.removeSpan(span)
                if (ResponseRenderSafety.externalLink(span.url)) styled.setSpan(object : URLSpan(span.url) {
                    override fun onClick(widget: View) { openLink(url) }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = styled
        } catch (_: Exception) { text = source }
    }

    private fun code(block: ResponseBlockParser.Block.Code, index: Int): View {
        val theme = currentTheme()
        val language = block.language.lowercase()
        val scientific = language in setOf("latex", "tex", "math")
        val diagram = language in setOf("mermaid", "svg")
        val fill = when { scientific -> theme.mathBg; diagram -> theme.diagramBg; else -> theme.codeBg }
        val ink = when { scientific -> theme.mathText; diagram -> theme.diagramText; else -> theme.codeText }
        val border = when { scientific -> theme.mathBorder; diagram -> theme.diagramBorder; else -> theme.codeBorder }
        return card(fill, border).apply {
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(block.language.ifBlank { "text" } + if (block.closed) "" else " · пишется…").apply {
                    textSize = 12f; setTextColor(ink)
                }, LayoutParams(0, -2, 1f))
                addView(button("Копировать") { copy(block.content) })
            })
            if (block.closed && autoScientificPreview && block.language.lowercase() in setOf("latex", "tex", "math", "mermaid", "svg")) {
                val visual = when (block.language.lowercase()) {
                    "mermaid" -> if (ScientificRendererDocument.mermaidProblem(block.content) == null) MermaidDiagramView(context, block.content, embedded = true) else null
                    "svg" -> if (block.content.length <= 200_000) safeSvg(block.content) else null
                    else -> if (block.content.length <= ScientificRendererDocument.MAX_MATH_CHARACTERS) ScientificMathView(context, block.content) else null
                }
                if (visual != null) { addView(visual); return@apply }
            }
            if (block.closed && scientificAssets) {
                if (block.language.equals("mermaid", true)) addView(button("Превью диаграммы") {
                    val problem = ScientificRendererDocument.mermaidProblem(block.content)
                    if (problem != null) previewNotice(problem) else scientificPreview(block.content) { ScientificRendererDocument.mermaid(block.content) }
                })
                if (block.language.lowercase() in setOf("latex", "tex", "math")) addView(button("Превью формулы") {
                    formulaPreview(listOf(MathDelimiterScanner.Formula(0, block.content.length, block.content, block.content, true)))
                })
            }
            if (block.closed && block.language.lowercase() in setOf("html", "svg"))
                addView(button(if (block.language.equals("svg", true)) "Превью SVG" else "Код / Превью") { preview(block) })
            if (block.content.length > 100_000) {
                addView(pagedText(block.content, index, true))
                return@apply
            }
            val body = text(block.content).apply {
                setTextColor(ink)
                typeface = Typeface.MONOSPACE; textSize = 14f; setHorizontallyScrolling(true)
                if (block.closed && block.content.length <= 100_000 && block.language.lowercase() in
                    setOf("kt", "kotlin", "java", "py", "python", "js", "javascript", "ts", "typescript", "json", "sql", "sh", "bash")) {
                    val styled = SpannableStringBuilder(block.content)
                    Regex("\\b(class|fun|def|return|if|else|for|while|import|from|val|var|const|let|public|private|true|false|null|SELECT|FROM|WHERE)\\b")
                        .findAll(block.content).forEach { match -> styled.setSpan(ForegroundColorSpan(theme.accent),
                            match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    text = styled
                }
            }
            addView(HorizontalScrollView(context).apply { isFillViewport = false; addView(body, LayoutParams(-2, -2)) }, LayoutParams(-1, -2))
        }
    }

    private fun pagedText(source: String, index: Int, code: Boolean): View = column().apply {
        val state = pageStates.getOrPut(index) { PageState() }
        val body = text().apply { if (code) { typeface = Typeface.MONOSPACE; textSize = 14f; setHorizontallyScrolling(true) } }
        val position = text().apply { textSize = 12f }
        fun show() {
            val page = ResponseTextPages.page(source, if (state.followTail) Int.MAX_VALUE else state.page)
            state.page = page.index; body.text = page.text
            position.text = buildString {
                append("Фрагмент ${page.index + 1} из ${page.count} · полный текст сохранён")
                page.fallbackNotice?.let { append(" · ").append(it) }
            }
        }
        addView(row().apply {
            addView(button("←") { state.followTail = false; state.page = (state.page - 1).coerceAtLeast(0); show() })
            addView(button("→") { state.followTail = false; state.page++; show() })
            addView(button("Конец") { state.followTail = true; show() })
        })
        addView(row().apply {
            addView(button("Копировать всё") { copy(source) })
            addView(button("Сохранить всё") { saveFile(ResponseBlockParser.Block.File("", "response-${index + 1}.txt", source, true)) })
        })
        addView(position)
        if (scientificAssets && !code) addView(button("Формулы фрагмента") {
            val page = ResponseTextPages.page(source, if (state.followTail) Int.MAX_VALUE else state.page)
            val formulas = MathDelimiterScanner.scan(page.text)
            if (formulas.isEmpty()) previewNotice(page.fallbackNotice ?: "В этом фрагменте нет закрытых формул. Полный исходник можно сохранить в файл.")
            else formulaPreview(formulas)
        })
        if (code) addView(HorizontalScrollView(context).apply { addView(body, LayoutParams(-2, -2)) }, LayoutParams(-1, -2))
        else addView(body)
        show()
    }

    private fun details(block: ResponseBlockParser.Block.Details, index: Int): View = card(currentTheme().logBg).apply {
        if (detailDepth >= 8) { addView(pagedText(block.raw, index, false)); return@apply }
        val state = detailStates.getOrPut(index) { DetailsState(block.expanded) }
        val caption = button("") {}
        caption.setTextColor(currentTheme().logText)
        val holder = column()
        fun refresh() {
            caption.text = (if (state.expanded) "▾ " else "▸ ") + block.summary + if (block.closed) "" else " · создаётся…"
            holder.visibility = if (state.expanded) VISIBLE else GONE
            if (state.expanded) {
                val body = state.body ?: RichResponsePanel(context, saveFile, {}, detailDepth + 1).also { state.body = it }
                if (body.parent !== holder) { (body.parent as? ViewGroup)?.removeView(body); holder.addView(body) }
                body.submit(ChatBubbleItem.create("${renderId}:details:$index", "", block.content, false))
            }
        }
        caption.setOnClickListener { state.expanded = !state.expanded; refresh() }
        addView(caption); addView(holder); refresh()
    }

    private fun table(block: ResponseBlockParser.Block.Table, state: TableState): View = card(currentTheme().tableRowBg).apply {
        val theme = currentTheme()
        var shown = 0
        val grid = column()
        val columnWidthsPx = IntArray(block.header.size) { colIndex ->
            var maxChars = block.header.getOrNull(colIndex).orEmpty().length
            val sampleLimit = minOf(block.rows.size, 100)
            for (r in 0 until sampleLimit) {
                val cellLen = block.rows[r].getOrNull(colIndex).orEmpty().length
                if (cellLen > maxChars) maxChars = cellLen
            }
            val estimatedDp = (maxChars * 9 + 26).coerceIn(88, 280)
            dp(estimatedDp)
        }
        fun append(values: List<String>, header: Boolean) {
            grid.addView(row().apply {
                setBackgroundColor(if (header) theme.tableHeaderBg else theme.tableRowBg)
                for (index in block.header.indices) addView(markdown(values.getOrNull(index).orEmpty()).apply {
                    if (header) setTypeface(typeface, Typeface.BOLD)
                    gravity = when (block.align[index]) { 0 -> Gravity.CENTER; 1 -> Gravity.END; else -> Gravity.START }
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                }, LayoutParams(columnWidthsPx[index], -2))
            })
        }
        append(block.header, true)
        val next = button("Ещё строки") { }
        fun more(end: Int = minOf(shown + 50, block.rows.size)) {
            for (index in shown until end) append(block.rows[index], false)
            shown = end; next.visibility = if (shown < block.rows.size) VISIBLE else GONE
            state.shown = maxOf(state.shown, shown)
            next.text = "Ещё строки ($shown из ${block.rows.size})"
        }
        next.setOnClickListener { more() }; more(minOf(state.shown, block.rows.size))
        addView(HorizontalScrollView(context).apply {
            addView(grid, LayoutParams(-2, -2))
            setOnScrollChangeListener { _: View, x: Int, _: Int, _: Int, _: Int -> state.scrollX = x }
            post { scrollTo(state.scrollX, 0) }
        }, LayoutParams(-1, -2))
        addView(next)
    }

    private fun safeSvg(source: String): View = object : LinearLayout(context) {
        private val theme = currentTheme()
        private val original = text(source).apply { typeface = Typeface.MONOSPACE; setTextColor(theme.diagramText); visibility = GONE }
        private val notice = text().apply { textSize = 12f; visibility = GONE }
        private val retry = button("Повторить превью") { showPreview() }.apply { visibility = GONE }
        private var web: WebView? = null
        private var retryRequired = false

        private fun destroyRenderer() {
            val old = web ?: return
            web = null; removeView(old); old.stopLoading(); old.destroy()
        }
        private fun showPreview() {
            retryRequired = false
            if (web == null) {
                val renderer = WebView(context).apply {
                    setBackgroundColor(theme.diagramBg)
                    settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.domStorageEnabled = false; settings.blockNetworkLoads = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            if (web === view) {
                                destroyRenderer(); original.visibility = VISIBLE
                                notice.text = "Превью недоступно; исходный код сохранён."; notice.visibility = VISIBLE
                                retryRequired = true
                                retry.visibility = VISIBLE
                            }
                            return true
                        }
                    }
                }
                web = renderer; addView(renderer, LayoutParams(-1, dp(240)))
                renderer.loadDataWithBaseURL("https://preview.invalid/", ResponseRenderSafety.previewDocument(source,
                    theme.copy(assistantBubbleBg = theme.diagramBg, textPrimary = theme.diagramText)), "text/html", "UTF-8", null)
            }
            original.visibility = GONE; notice.visibility = GONE; retry.visibility = GONE; web?.visibility = VISIBLE
        }
        init {
            orientation = VERTICAL
            setBackgroundColor(theme.diagramBg)
            addView(notice); addView(retry); addView(original); showPreview()
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (web == null && !retryRequired) showPreview()
        }
        override fun onDetachedFromWindow() {
            destroyRenderer(); original.visibility = VISIBLE
            super.onDetachedFromWindow()
        }
    }

    private fun preview(block: ResponseBlockParser.Block.Code) {
        if (block.content.length > 200_000) {
            val dialog = AppThemeViews.dialog(context, currentTheme()).setMessage("Превью слишком велико для безопасной обработки на телефоне. Исходный код доступен полностью.")
                .setPositiveButton("Закрыть", null).create()
            dialogs.add(dialog); dialog.setOnDismissListener { dialogs.remove(dialog) }; dialog.show(); return
        }
        var destroyed = false
        var rendererFailure: (() -> Unit)? = null
        val web = WebView(context).apply {
            setBackgroundColor(currentTheme().assistantBubbleBg)
            settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.domStorageEnabled = false; settings.blockNetworkLoads = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    (view.parent as? ViewGroup)?.removeView(view)
                    if (!destroyed) { destroyed = true; view.destroy() }
                    rendererFailure?.invoke()
                    return true
                }
            }
            loadDataWithBaseURL("https://preview.invalid/", ResponseRenderSafety.previewDocument(block.content, currentTheme()), "text/html", "UTF-8", null)
        }
        val tabs = column()
        val original = text(block.content).apply { typeface = Typeface.MONOSPACE; visibility = GONE }
        rendererFailure = { original.visibility = VISIBLE; tabs.addView(text("Превью недоступно; исходный код сохранён.")) }
        tabs.addView(row().apply {
            addView(button("Код") { if (!destroyed) web.visibility = GONE; original.visibility = VISIBLE })
            addView(button("Превью") { if (!destroyed) { original.visibility = GONE; web.visibility = VISIBLE } })
        })
        tabs.addView(original); tabs.addView(web, LayoutParams(-1, dp(360)))
        val dialog = AppThemeViews.dialog(context, currentTheme()).setTitle("Превью без скриптов и сети").setView(tabs).setPositiveButton("Закрыть", null).create()
        dialogs.add(dialog); dialog.setOnDismissListener {
            dialogs.remove(dialog)
            if (!destroyed) { web.stopLoading(); destroyed = true; web.destroy() }
        }; dialog.show()
    }

    private fun previewNotice(message: String) {
        val dialog = AppThemeViews.dialog(context, currentTheme()).setMessage(message).setPositiveButton("Закрыть", null).create()
        dialogs.add(dialog); dialog.setOnDismissListener { dialogs.remove(dialog) }; dialog.show()
    }
    private fun scientificPreview(source: String, document: () -> String) {
        try {
            val dialog = ScientificRendererPreview.create(context, document(), source) { dialogs.remove(it) }
            dialogs.add(dialog); dialog.show()
        } catch (_: Exception) { previewNotice("Превью недоступно. Исходник сохранён полностью.") }
    }
    private fun formulaPreview(formulas: List<MathDelimiterScanner.Formula>) {
        if (formulas.sumOf { it.latex.length.toLong() } > ScientificRendererDocument.MAX_MATH_CHARACTERS) {
            if (formulas.size == 1) { previewNotice("Формула слишком велика для превью. Исходник сохранён полностью."); return }
            val dialog = AppThemeViews.dialog(context, currentTheme()).setTitle("Выберите формулу для превью")
                .setItems(formulas.mapIndexed { i, formula -> "${i + 1}. ${formula.source.take(80)}" }.toTypedArray()) { _, index -> formulaPreview(listOf(formulas[index])) }
                .setNegativeButton("Закрыть", null).create()
            dialogs.add(dialog); dialog.setOnDismissListener { dialogs.remove(dialog) }; dialog.show(); return
        }
        scientificPreview(formulas.joinToString("\n\n") { it.source }) { ScientificRendererDocument.math(formulas) }
    }

    private fun utf8Bytes(value: String): Long {
        var count = 0L; var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            count += when { point < 128 || point in 0xD800..0xDFFF -> 1; point < 2048 -> 2; point < 65536 -> 3; else -> 4 }
            index += Character.charCount(point)
        }
        return count
    }
    override fun onDetachedFromWindow() {
        detached = true; parseVersion.incrementAndGet()
        parseJob?.cancel(true); parseJob = null; parseJobVersion = null; asyncLayoutPending = false
        latest = null; evidencePanel = null
        handler.removeCallbacks(refresh); scheduled = false
        dialogs.toList().forEach { it.dismiss() }
        super.onDetachedFromWindow()
    }
}
