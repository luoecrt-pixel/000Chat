package com.example.openaichat

/** Presentation-only parsing. Never replace the canonical response or signed protocol. */
object ResponseBlockParser {
    sealed class Block(open val raw: String) {
        data class Prose(override val raw: String) : Block(raw)
        data class Code(override val raw: String, val language: String, val content: String, val closed: Boolean) : Block(raw)
        data class File(override val raw: String, val name: String, val content: String, val closed: Boolean) : Block(raw)
        data class Details(override val raw: String, val summary: String, val content: String, val closed: Boolean, val expanded: Boolean) : Block(raw)
        data class Table(override val raw: String, val header: List<String>, val align: List<Int>, val rows: List<List<String>>) : Block(raw)
    }
    private val tableSeparator = Regex(":?-{1,}:?")
    private val fence = Regex("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$")
    private val file = Regex("^ {0,3}<file name=\"([^\"\\r\\n]+)\">[ \\t]*$")
    private val details = Regex("^ {0,3}<details(?:[ \\t]+open(?:=\"open\")?)?[ \\t]*>[ \\t]*$", RegexOption.IGNORE_CASE)
    private val summary = Regex("^ {0,3}<summary>(.*?)</summary>[ \\t]*$", RegexOption.IGNORE_CASE)
    private data class Line(val start: Int, val end: Int, val text: String)

    fun parse(source: String, cancelled: () -> Boolean = { false }): List<Block> =
        parse(source, cancelled, allowWholeDocumentJson = true)

    internal fun parse(
        source: String,
        cancelled: () -> Boolean = { false },
        allowWholeDocumentJson: Boolean = true
    ): List<Block> {
        fun checkpoint() { if (Thread.currentThread().isInterrupted || cancelled()) throw java.util.concurrent.CancellationException("Superseded response presentation") }
        checkpoint()
        if (source.isEmpty()) return emptyList()
        val trimmed = source.trim()
        if (allowWholeDocumentJson && source.length <= 100_000 && (trimmed.startsWith('{') || trimmed.startsWith('['))) {
            val json = StrictJsonValue.isObjectOrArray(trimmed)
            if (json) return listOf(Block.Code(source, "json", source, true))
        }
        val lines = mutableListOf<Line>()
        var offset = 0
        while (offset < source.length) {
            checkpoint()
            val newline = source.indexOf('\n', offset)
            val end = if (newline < 0) source.length else newline + 1
            lines.add(Line(offset, end, source.substring(offset, if (newline < 0) end else newline).removeSuffix("\r")))
            offset = end
        }
        val blocks = mutableListOf<Block>()
        var index = 0
        var proseStart = 0
        fun flush(end: Int) { if (end > proseStart) blocks.add(Block.Prose(source.substring(proseStart, end))) }
        while (index < lines.size) {
            checkpoint()
            val line = lines[index]
            val opening = fence.matchEntire(line.text)
            val tagged = file.matchEntire(line.text)
            if (opening != null && !(opening.groupValues[1][0] == '`' && '`' in opening.groupValues[2])) {
                flush(line.start)
                val marker = opening.groupValues[1]
                val closing = Regex("^ {0,3}${Regex.escape(marker[0].toString())}{${marker.length},}[ \\t]*$")
                var next = index + 1
                while (next < lines.size && !closing.matches(lines[next].text)) { checkpoint(); next++ }
                val closed = next < lines.size
                val bodyEnd = if (closed) lines[next].start else source.length
                val rawEnd = if (closed) lines[next].end else source.length
                blocks.add(Block.Code(source.substring(line.start, rawEnd), opening.groupValues[2].trim().substringBefore(' '),
                    source.substring(line.end, bodyEnd), closed))
                index = if (closed) next + 1 else lines.size
                proseStart = rawEnd
                continue
            }
            if (tagged != null) {
                flush(line.start)
                var next = index + 1
                while (next < lines.size && lines[next].text.trim() != "</file>") { checkpoint(); next++ }
                val closed = next < lines.size
                val bodyEnd = if (closed) lines[next].start else source.length
                val rawEnd = if (closed) lines[next].end else source.length
                blocks.add(Block.File(source.substring(line.start, rawEnd), tagged.groupValues[1], source.substring(line.end, bodyEnd), closed))
                index = if (closed) next + 1 else lines.size
                proseStart = rawEnd
                continue
            }
            if (details.matches(line.text) && index + 1 < lines.size) {
                val title = summary.matchEntire(lines[index + 1].text)
                if (title != null) {
                    flush(line.start)
                    var next = index + 2
                    var depth = 1
                    var inFenceClosingRegex: Regex? = null
                    var inFile = false
                    while (next < lines.size) {
                        checkpoint()
                        val candidate = lines[next].text
                        if (inFile) {
                            if (candidate.trim() == "</file>") inFile = false
                        } else if (inFenceClosingRegex != null) {
                            if (inFenceClosingRegex.matches(candidate)) inFenceClosingRegex = null
                        } else {
                            val found = fence.matchEntire(candidate)
                            if (found != null && !(found.groupValues[1][0] == '`' && '`' in found.groupValues[2])) {
                                val marker = found.groupValues[1]
                                inFenceClosingRegex = Regex("^ {0,3}${Regex.escape(marker[0].toString())}{${marker.length},}[ \\t]*$")
                            }
                            else if (file.matches(candidate)) inFile = true
                            else if (details.matches(candidate)) depth++
                            else if (candidate.trim().equals("</details>", true)) { depth--; if (depth == 0) break }
                        }
                        next++
                    }
                    val closed = next < lines.size
                    val rawEnd = if (closed) lines[next].end else source.length
                    blocks.add(Block.Details(source.substring(line.start, rawEnd), title.groupValues[1],
                        source.substring(lines[index + 1].end, if (closed) lines[next].start else source.length), closed,
                        Regex("\\bopen\\b", RegexOption.IGNORE_CASE).containsMatchIn(line.text)))
                    index = if (closed) next + 1 else lines.size; proseStart = rawEnd
                    continue
                }
            }
            if (index + 1 < lines.size && '|' in line.text) {
                val header = cells(line.text, cancelled)
                val separators = cells(lines[index + 1].text, cancelled)
                if (header.isNotEmpty() && header.size == separators.size && separators.all { tableSeparator.matches(it.trim()) }) {
                    flush(line.start)
                    var next = index + 2
                    val rows = mutableListOf<List<String>>()
                    while (next < lines.size && '|' in lines[next].text && lines[next].text.isNotBlank()) {
                        checkpoint(); rows.add(cells(lines[next].text, cancelled)); next++
                    }
                    val rawEnd = lines[next - 1].end
                    blocks.add(Block.Table(source.substring(line.start, rawEnd), header, separators.map {
                        when { it.trim().startsWith(':') && it.trim().endsWith(':') -> 0; it.trim().endsWith(':') -> 1; else -> -1 }
                    }, rows))
                    index = next; proseStart = rawEnd
                    continue
                }
            }
            index++
        }
        flush(source.length)
        return blocks
    }

    /** Reuse stable closed rich blocks; provisional prose/table/fences remain reparsable. */
    class Session {
        private var previous = ""
        private var committedChars = 0
        private var committed: List<Block> = emptyList()
        @Synchronized fun update(source: String, cancelled: () -> Boolean = { false }): List<Block> {
            fun checkpoint() { if (Thread.currentThread().isInterrupted || cancelled()) throw java.util.concurrent.CancellationException("Superseded response presentation") }
            var append = source.length >= previous.length
            var offset = 0
            while (append && offset < previous.length) {
                checkpoint()
                val count = minOf(16384, previous.length - offset)
                append = source.regionMatches(offset, previous, offset, count)
                offset += count
            }
            val base = if (append) committed else emptyList()
            val start = if (append) committedChars else 0
            val tail = parse(source.substring(start), cancelled, allowWholeDocumentJson = start == 0)
            val blocks = base + tail
            var end = start
            var stableCount = base.size
            var stableEnd = start
            tail.forEachIndexed { index, block ->
                checkpoint()
                end += block.raw.length
                val stable = when (block) {
                    is Block.Code -> block.closed && block.raw.endsWith('\n') &&
                        (block.raw.trimStart().startsWith("```") || block.raw.trimStart().startsWith("~~~"))
                    is Block.File -> block.closed && block.raw.endsWith('\n')
                    is Block.Details -> block.closed && block.raw.endsWith('\n')
                    is Block.Table -> index + 1 < tail.size && '\n' in tail[index + 1].raw
                    is Block.Prose -> false
                }
                if (stable) { stableCount = base.size + index + 1; stableEnd = end }
            }
            checkpoint()
            previous = source; committedChars = stableEnd; committed = blocks.take(stableCount)
            return blocks
        }
        @Synchronized fun reset() { previous = ""; committedChars = 0; committed = emptyList() }
    }

    /** Pipes inside code spans and escaped pipes are literal cell contents. */
    fun cells(line: String, cancelled: () -> Boolean = { false }): List<String> {
        val values = mutableListOf<String>()
        val value = StringBuilder()
        var index = 0
        var ticks = 0
        val text = line.trim()
        fun isUnescapedEndPipe(s: String): Boolean {
            if (!s.endsWith('|')) return false
            var slashes = 0
            var k = s.length - 2
            while (k >= 0 && s[k] == '\\') { slashes++; k-- }
            return slashes % 2 == 0
        }
        while (index < text.length) {
            if (index % 4096 == 0 && (Thread.currentThread().isInterrupted || cancelled())) throw java.util.concurrent.CancellationException("Superseded table presentation")
            val c = text[index]
            if (c == '\\' && index + 1 < text.length && (text[index + 1] == '|' || text[index + 1] == '\\')) {
                value.append('\\').append(text[index + 1]); index += 2; continue
            }
            if (c == '`') {
                var end = index + 1
                while (end < text.length && text[end] == '`') end++
                val run = end - index
                if (ticks == 0) ticks = run else if (ticks == run) ticks = 0
                value.append(text, index, end); index = end; continue
            }
            if (c == '|' && ticks == 0) { values.add(value.toString().trim()); value.setLength(0) }
            else value.append(c)
            index++
        }
        values.add(value.toString().trim())
        if (text.startsWith('|') && values.isNotEmpty()) values.removeAt(0)
        if (isUnescapedEndPipe(text) && values.isNotEmpty()) values.removeAt(values.lastIndex)
        return values
    }
}
