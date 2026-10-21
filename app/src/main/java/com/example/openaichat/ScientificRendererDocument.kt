package com.example.openaichat

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Only app-owned JavaScript runs. Model strings are data, never HTML or scripts. */
object ScientificRendererDocument {
    const val ORIGIN = "https://response-renderer.invalid/"
    const val DOCUMENT_URL = ORIGIN + "index.html"
    const val MAX_MATH_CHARACTERS = 16_000
    const val MAX_DIAGRAM_CHARACTERS = 50_000
    fun assetPath(url: Uri): String? {
        if (url.scheme != "https" || url.host != "response-renderer.invalid" || url.port != -1 || url.userInfo != null || url.query != null) return null
        val path = url.path ?: return null
        if (!Regex("^/response-renderer/(?:katex|mermaid)/[A-Za-z0-9._/-]+$").matches(path)) return null
        if (path.split('/').drop(1).any { it == "." || it == ".." || it.isEmpty() }) return null
        if (path.substringAfterLast('.').lowercase() !in setOf("js", "mjs", "css", "woff", "woff2", "ttf")) return null
        return path.removePrefix("/")
    }
    fun mermaidProblem(source: String): String? = when {
        source.length > MAX_DIAGRAM_CHARACTERS -> "Диаграмма слишком велика для превью; исходник сохранён полностью."
        source.contains("%%{") || Regex("(?m)^\\s*---\\s*$").containsMatchIn(source) -> "Настройки внутри диаграммы не разрешены в превью."
        Regex("(?im)(?:^|;)\\s*click\\b").containsMatchIn(source) -> "Обработчики click не разрешены в превью."
        Regex("<[A-Za-z!/][^>]*>").containsMatchIn(source) -> "HTML внутри диаграммы не разрешён в превью."
        else -> null
    }
    private fun scriptData(value: String) = value.replace("\\/", "/").replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    fun math(formulas: List<MathDelimiterScanner.Formula>): String {
        require(formulas.isNotEmpty() && formulas.sumOf { it.latex.length.toLong() } <= MAX_MATH_CHARACTERS)
        val data = JSONArray(formulas.map { JSONObject().put("source", it.source).put("latex", it.latex).put("display", it.display) })
        return document("math", scriptData(data.toString()))
    }
    fun mermaid(source: String, embedded: Boolean = false): String {
        require(mermaidProblem(source) == null)
        return document("mermaid", scriptData(JSONObject.quote(source)), embedded)
    }
    /** html must come from sanitized native Markdown, never model-supplied HTML. */
    fun mathInText(html: String, prepared: MathTextTokens.Prepared): String {
        require(html.length <= 400_000 && prepared.items.isNotEmpty())
        require(prepared.prefix.matches(Regex("MATH[a-f0-9]{32}X")))
        require(prepared.items.mapIndexed { index, item -> item.first == prepared.prefix + index + "END" }.all { it })
        require(prepared.items.sumOf { it.second.latex.length.toLong() } <= MAX_MATH_CHARACTERS)
        val items = JSONArray(prepared.items.map { (token, formula) ->
            JSONObject().put("token", token).put("source", formula.source).put("latex", formula.latex).put("display", formula.display)
        })
        return document("math-text", scriptData(JSONObject().put("html", html).put("prefix", prepared.prefix).put("items", items).toString()))
    }
    fun themed(document: String, theme: ThemeColors): String {
        // Change only our first stylesheet. A matching string inside model data
        // must remain byte-for-byte identical, including signed/copied sources.
        val styleStart = document.indexOf("<style>")
        if (styleStart < 0) return document
        val start = document.indexOf(THEME_START, styleStart)
        val end = document.indexOf(THEME_END, (start + THEME_START.length).coerceAtLeast(0))
        if (start != styleStart + "<style>".length || end < 0 ||
            end + THEME_END.length != document.indexOf("</style>", styleStart)) return document
        return document.replaceRange(start + THEME_START.length, end, style(theme))
    }
    private const val THEME_START = "/*integral-theme:start*/"
    private const val THEME_END = "/*integral-theme:end*/"
    private fun style(theme: ThemeColors): String {
        fun css(color: Int) = "#%06x".format(color and 0xFFFFFF)
        return """
            :root{--math-bg:${css(theme.mathBg)};--math-text:${css(theme.mathText)};--math-border:${css(theme.mathBorder)};--diagram-bg:${css(theme.diagramBg)};--diagram-text:${css(theme.diagramText)};--diagram-border:${css(theme.diagramBorder)}}
            body{margin:8px;color:${css(theme.textPrimary)};background:${css(theme.assistantBubbleBg)};font:16px/1.5 sans-serif;overflow-wrap:anywhere}
            section,.display-formula{display:block;overflow:auto;margin:8px 0;padding:12px;color:var(--math-text);background:var(--math-bg);border:1px solid var(--math-border);border-radius:18px 10px 18px 10px}
            .inline-formula{color:var(--math-text);background:var(--math-bg);border-radius:6px;padding:2px 3px}
            .failed{white-space:pre-wrap;color:${css(theme.errorText)};background:${css(theme.errorBg)};border:1px solid ${css(theme.errorText)};border-radius:12px;padding:8px}
            blockquote{margin:8px 0;border-left:3px solid var(--math-border);border-radius:3px 12px 12px 3px;padding:8px 12px;background:var(--math-bg)}
            pre,code,tt{font-family:monospace;color:${css(theme.codeText)};background:${css(theme.codeBg)};border-radius:6px}
            #result.mermaid{overflow:auto;padding:10px;color:var(--diagram-text);background:var(--diagram-bg);border:1px solid var(--diagram-border);border-radius:18px 10px 18px 10px}
            #result.mermaid.embedded{border:0;border-radius:0;padding:0}
            #result.mermaid .edgeLabel rect.background,#result.mermaid .edgeLabel .labelBkg{fill:var(--diagram-bg)!important;opacity:0.95!important}
            #result.mermaid .edgeLabel text,#result.mermaid .edgeLabel tspan{paint-order:stroke fill;stroke:var(--diagram-bg);stroke-width:6px;stroke-linejoin:round}
            svg{max-width:100%;height:auto}p{margin:6px 0}.katex-display{margin:0.35em 0}
        """.trimIndent()
    }

    private fun document(mode: String, data: String, embedded: Boolean = false): String {
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val csp = "default-src 'none'; script-src 'self' 'nonce-$nonce'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
        val engine = if (mode == "math-text") """
            const data = $data;
            const parsed = new DOMParser().parseFromString(data.html, 'text/html');
            const allowed = new Set(['P','DIV','SPAN','B','STRONG','I','EM','U','S','STRIKE','BLOCKQUOTE','H1','H2','H3','H4','H5','H6','UL','OL','LI','PRE','CODE','TT','BR','SUP','SUB']);
            parsed.body.querySelectorAll('*').forEach(element => {
              if (!allowed.has(element.tagName)) { element.replaceWith(parsed.createTextNode(element.textContent)); return; }
              for (const attribute of Array.from(element.attributes)) element.removeAttribute(attribute.name);
            });
            for (const child of Array.from(parsed.body.childNodes)) result.appendChild(document.importNode(child, true));
            const items = new Map(data.items.map(item => [item.token,item]));
            const pattern = new RegExp(data.prefix + '[0-9]+END','g');
            const walker = document.createTreeWalker(result,NodeFilter.SHOW_TEXT);
            const nodes = []; while (walker.nextNode()) nodes.push(walker.currentNode);
            let replaced = 0;
            for (const node of nodes) {
              pattern.lastIndex = 0; const text = node.textContent; let match; let start = 0; const fragment = document.createDocumentFragment();
              while ((match = pattern.exec(text)) !== null) {
                const item = items.get(match[0]); if (!item) throw new Error('Unknown formula token');
                fragment.appendChild(document.createTextNode(text.slice(start,match.index)));
                const formula = document.createElement('span'); fragment.appendChild(formula);
                formula.className = item.display ? 'display-formula' : 'inline-formula';
                try { katex.render(item.latex, formula, {displayMode:item.display, throwOnError:true, trust:false, maxExpand:1000, maxSize:20, strict:'warn', macros:{}}); }
                catch (_) { formula.textContent = item.source; formula.className = 'failed'; }
                start = pattern.lastIndex; replaced++;
              }
              if (start) { fragment.appendChild(document.createTextNode(text.slice(start))); node.replaceWith(fragment); }
            }
            if (replaced !== data.items.length) throw new Error('Formula was not visible Markdown text');
        """ else if (mode == "math") """
            const items = $data;
            for (const item of items) {
              const section = document.createElement('section'); result.appendChild(section);
              try { katex.render(item.latex, section, {displayMode:item.display, throwOnError:true, trust:false, maxExpand:1000, maxSize:20, strict:'warn', macros:{}}); }
              catch (_) { section.textContent = item.source; section.className = 'failed'; }
            }
        """ else """
            const source = $data;
            const {default:mermaid} = await import('./response-renderer/mermaid/mermaid.esm.min.mjs');
            const colors = getComputedStyle(document.documentElement);
            const color = name => colors.getPropertyValue(name).trim();
            mermaid.initialize({startOnLoad:false, securityLevel:'strict', htmlLabels:false, flowchart:{htmlLabels:false}, theme:'base',themeVariables:{primaryColor:color('--diagram-bg'),primaryTextColor:color('--diagram-text'),textColor:color('--diagram-text'),primaryBorderColor:color('--diagram-border'),lineColor:color('--diagram-text'),secondaryColor:color('--math-bg'),secondaryTextColor:color('--math-text'),tertiaryColor:color('--math-bg'),edgeLabelBackground:color('--diagram-bg')}, maxTextSize:50000, maxEdges:500, suppressErrorRendering:true});
            await document.fonts.ready;
            const rendered = await mermaid.render('answerDiagram', source);
            const doc = new DOMParser().parseFromString(rendered.svg, 'image/svg+xml');
            if (doc.querySelector('parsererror') || doc.documentElement.localName !== 'svg') throw new Error('Invalid SVG');
            doc.querySelectorAll('script,foreignObject,iframe,image,animate,set').forEach(node => node.remove());
            doc.querySelectorAll('*').forEach(node => {
              for (const attribute of Array.from(node.attributes)) {
                if (/^on/i.test(attribute.name) || /^(?:href|xlink:href)$/i.test(attribute.name) && !attribute.value.startsWith('#')) node.removeAttribute(attribute.name);
              }
            });
            result.appendChild(document.importNode(doc.documentElement, true));
        """
        val katex = if (mode.startsWith("math")) """<link rel="stylesheet" href="./response-renderer/katex/katex.min.css"><script src="./response-renderer/katex/katex.min.js"></script>""" else ""
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="$csp">$katex<style>$THEME_START${style(AppThemePalette.DARK)}$THEME_END</style></head><body><div id="result" class="$mode${if (embedded) " embedded" else ""}"></div><script type="module" nonce="$nonce">const result=document.getElementById('result');try{$engine await document.fonts.ready;document.title='renderer:ready';}catch(_){result.textContent='Превью недоступно. Исходник доступен в приложении.';document.title='renderer:failed';}</script></body></html>"""
    }
}
