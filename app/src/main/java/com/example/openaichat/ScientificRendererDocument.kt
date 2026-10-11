package com.example.openaichat

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Only app-owned JavaScript runs. Model strings are data, never HTML or scripts. */
object ScientificRendererDocument {
    const val ORIGIN = "https://response-renderer.invalid/"
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
    private fun scriptData(value: String) = value.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    fun math(formulas: List<MathDelimiterScanner.Formula>): String {
        require(formulas.isNotEmpty() && formulas.sumOf { it.latex.length.toLong() } <= MAX_MATH_CHARACTERS)
        val data = JSONArray(formulas.map { JSONObject().put("source", it.source).put("latex", it.latex).put("display", it.display) })
        return document("math", scriptData(data.toString()))
    }
    fun mermaid(source: String): String {
        require(mermaidProblem(source) == null)
        return document("mermaid", scriptData(JSONObject.quote(source)))
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
    private fun document(mode: String, data: String): String {
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
                if (item.display) formula.className = 'display-formula';
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
            mermaid.initialize({startOnLoad:false, securityLevel:'strict', htmlLabels:false, flowchart:{htmlLabels:false}, theme:'dark', maxTextSize:50000, maxEdges:500, suppressErrorRendering:true});
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
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="$csp">$katex<style>body{margin:12px;color:#fff;background:#1c1d22;font:16px sans-serif}section{overflow:auto;padding:12px 0}.display-formula{display:block;overflow:auto}.failed{white-space:pre-wrap;color:#ffb4ab}blockquote{border-left:3px solid #888;padding-left:12px}pre,code,tt{font-family:monospace}svg{max-width:100%;height:auto}</style></head><body><div id="result"></div><script type="module" nonce="$nonce">const result=document.getElementById('result');try{$engine await document.fonts.ready;document.title='renderer:ready';}catch(_){result.textContent='Превью недоступно. Исходник доступен в приложении.';document.title='renderer:failed';}</script></body></html>"""
    }
}
