package com.example.openaichat

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan
import android.text.style.ForegroundColorSpan
import android.text.style.BackgroundColorSpan
import android.text.style.URLSpan
import android.graphics.Typeface
import org.json.JSONArray
import java.util.regex.Pattern

object RichTextRenderer {
    data class Rendered(val text: CharSequence)

    private val plainUrlPattern = Pattern.compile("https?://[^\\s<>\\\"]+")

    fun render(response: AstraResponseModel): CharSequence = render(response.text, response.annotations)

    fun render(source: String, urlAnnotations: JSONArray? = null, theme: ThemeColors? = null): CharSequence {
        if (source.isEmpty()) return ""
        val out = SpannableStringBuilder()
        val sourceToOutput = IntArray(source.length + 1) { -1 }
        var i = 0

        fun appendPlain(start: Int, end: Int) {
            val outStart = out.length
            out.append(source, start, end)
            val outEnd = out.length
            for (k in start until end) sourceToOutput[k] = outStart + (k - start)
            sourceToOutput[end] = outEnd
        }

        while (i < source.length) {
            if (source[i] == '\\' && i + 1 < source.length && "*_~`[]\\<>".contains(source[i + 1])) {
                appendPlain(i + 1, i + 2)
                i += 2
                continue
            }
            if (source.startsWith("```", i)) {
                val end = source.indexOf("```", i + 3)
                if (end >= i + 3) {
                    val start = out.length
                    appendPlain(i + 3, end)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    theme?.let {
                        out.setSpan(ForegroundColorSpan(it.codeText), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(it.codeBg), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    i = end + 3
                    continue
                }
            }
            if (source.startsWith("https://", i) || source.startsWith("http://", i)) {
                var end = i
                while (end < source.length && !source[end].isWhitespace() && source[end] != '<' && source[end] != '"') end++
                appendPlain(i, end)
                i = end
                continue
            }
            if (source[i] == '[') {
                val close = source.indexOf("](", i + 1)
                if (close > i + 1) {
                    val urlEnd = source.indexOf(')', close + 2)
                    if (urlEnd > close + 2) {
                        val label = source.substring(i + 1, close)
                        val url = source.substring(close + 2, urlEnd)
                        if ('\n' !in label && '[' !in label && ']' !in label &&
                            url.none { it.isWhitespace() }) {
                            val outStart = out.length
                            out.append(label)
                            val outEnd = out.length
                            for (k in i + 1 until close) sourceToOutput[k] = outStart + (k - (i + 1))
                            sourceToOutput[i] = outStart
                            sourceToOutput[close] = outEnd
                            sourceToOutput[close + 1] = outEnd
                            sourceToOutput[urlEnd] = outEnd
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                out.setSpan(URLSpan(url), outStart, outEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            i = urlEnd + 1
                            continue
                        }
                    }
                }
            }
            if (source.startsWith("**", i)) {
                val end = source.indexOf("**", i + 2)
                if (end > i + 2) {
                    val outStart = out.length
                    appendPlain(i + 2, end)
                    out.setSpan(StyleSpan(Typeface.BOLD), outStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sourceToOutput[i]=outStart; sourceToOutput[i+1]=outStart
                    sourceToOutput[end]=out.length; sourceToOutput[end+1]=out.length
                    i = end + 2
                    continue
                }
            }
            if (source.startsWith("~~", i)) {
                val end = source.indexOf("~~", i + 2)
                if (end > i + 2) {
                    val outStart = out.length
                    appendPlain(i + 2, end)
                    out.setSpan(StrikethroughSpan(), outStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sourceToOutput[i]=outStart; sourceToOutput[i+1]=outStart
                    sourceToOutput[end]=out.length; sourceToOutput[end+1]=out.length
                    i = end + 2
                    continue
                }
            }
            if (source[i] == '`') {
                val end = source.indexOf('`', i + 1)
                if (end > i + 1) {
                    val outStart = out.length
                    appendPlain(i + 1, end)
                    out.setSpan(TypefaceSpan("monospace"), outStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    theme?.let {
                        out.setSpan(ForegroundColorSpan(it.codeText), outStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(it.codeBg), outStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    sourceToOutput[i]=outStart; sourceToOutput[end]=out.length
                    i = end + 1
                    continue
                }
            }
            if (source[i] == '*' || source[i] == '_') {
                val marker = source[i]
                val end = source.indexOf(marker, i + 1)
                val intraword = (i > 0 && source[i - 1].isLetterOrDigit()) ||
                    (end + 1 < source.length && source[end + 1].isLetterOrDigit())
                val spacedAsterisk = marker == '*' &&
                    ((i + 1 < source.length && source[i + 1].isWhitespace()) ||
                        (end > i + 1 && source[end - 1].isWhitespace()))
                val crossesLine = end > i + 1 && '\n' in source.substring(i + 1, end)
                if (end > i + 1 && !intraword && !spacedAsterisk && !crossesLine && !source.startsWith("**", i)) {
                    val outStart = out.length
                    appendPlain(i + 1, end)
                    out.setSpan(StyleSpan(Typeface.ITALIC), outStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sourceToOutput[i]=outStart; sourceToOutput[end]=out.length
                    i = end + 1
                    continue
                }
            }
            val start = i
            i++
            while (i < source.length && source[i] != '[' && source[i] != '`' && source[i] != '*' && source[i] != '_' && source[i] != '~' && source[i] != '\\') i++
            appendPlain(start, i)
        }

        for (k in sourceToOutput.indices) {
            if (sourceToOutput[k] < 0) sourceToOutput[k] = if (k == 0) 0 else sourceToOutput[k - 1]
        }
        for (annIndex in 0 until (urlAnnotations?.length() ?: 0)) {
            val raw = urlAnnotations?.optJSONObject(annIndex) ?: continue
            val ann = raw.optJSONObject("url_citation") ?: raw
            val url = ann.optString("url").trim()
            if (!(url.startsWith("http://") || url.startsWith("https://"))) continue
            val apiStart = ann.optInt("start_index", -1)
            val apiEnd = ann.optInt("end_index", -1)
            if (apiStart < 0 || apiEnd <= apiStart) continue
            val start = normalizeIndex(source, apiStart)
            val end = normalizeIndex(source, apiEnd)
            if (start < 0 || end <= start || start >= sourceToOutput.size) continue
            val outStart = sourceToOutput[start].coerceIn(0, out.length)
            val outEnd = sourceToOutput[end.coerceIn(0, sourceToOutput.lastIndex)].coerceIn(outStart, out.length)
            if (outEnd > outStart) out.setSpan(URLSpan(url), outStart, outEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Make bare URLs clickable too, while respecting explicit Markdown links.
        val plainText = out.toString()
        val matcher = plainUrlPattern.matcher(plainText)
        while (matcher.find()) {
            val start = matcher.start()
            val end = trimUrlPunctuation(plainText, matcher.end())
            if (end <= start) continue
            if (out.getSpans(start, end, URLSpan::class.java).isEmpty()) {
                val url = out.subSequence(start, end).toString()
                out.setSpan(URLSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    private fun trimUrlPunctuation(text: String, end: Int): Int {
        var e = end
        while (e > 0 && ")].,;!?\"'".contains(text[e - 1])) e--
        return e
    }

    private fun normalizeIndex(source: String, apiIndex: Int): Int {
        if (apiIndex < 0) return -1
        val utf16Candidate = apiIndex.takeIf { it <= source.length }
        val codePointCount = source.codePointCount(0, source.length)
        val codePointCandidate = apiIndex.takeIf { it <= codePointCount }?.let { source.offsetByCodePoints(0, it) }
        // OpenAI exposes character indices; Android strings use UTF-16. If a candidate
        // lands inside a surrogate pair, prefer the Unicode code-point mapping.
        if (utf16Candidate != null) {
            val insideSurrogate = utf16Candidate > 0 && utf16Candidate < source.length &&
                Character.isLowSurrogate(source[utf16Candidate]) && Character.isHighSurrogate(source[utf16Candidate - 1])
            if (!insideSurrogate) return utf16Candidate
        }
        return codePointCandidate ?: -1
    }
}
