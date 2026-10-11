package com.example.openaichat

/** Closed math only, outside inline code. Source offsets and delimiters are retained. */
object MathDelimiterScanner {
    data class Formula(val start: Int, val end: Int, val source: String, val latex: String, val display: Boolean)
    fun scan(source: String, cancelled: () -> Boolean = { false }): List<Formula> {
        val formulas = mutableListOf<Formula>()
        // A failed opener must leave later, different delimiters visible. Remember negative
        // searches so repeated unclosed openers do not rescan the entire remaining answer.
        val withoutClosing = mutableMapOf<String, Pair<Int, Int>>()
        fun check(index: Int) {
            if (index % 1024 == 0 && (cancelled() || Thread.currentThread().isInterrupted))
                throw java.util.concurrent.CancellationException("Superseded formula scan")
        }
        fun escaped(index: Int): Boolean {
            var cursor = index - 1
            while (cursor >= 0 && source[cursor] == '\\') cursor--
            return (index - cursor - 1) % 2 != 0
        }
        var index = 0
        while (index < source.length) {
            check(index)
            if (source[index] == '`' && !escaped(index)) {
                var end = index + 1
                while (end < source.length && source[end] == '`') end++
                val marker = source.substring(index, end)
                var close = end
                while (close < source.length) {
                    check(close)
                    if (source.startsWith(marker, close) && (close == 0 || source[close - 1] != '`') &&
                        (close + marker.length == source.length || source[close + marker.length] != '`')) break
                    close++
                }
                index = if (close < source.length) close + marker.length else source.length
                continue
            }
            val opening = when {
                source.startsWith("\\(", index) && !escaped(index) -> "\\(" to "\\)"
                source.startsWith("\\[", index) && !escaped(index) -> "\\[" to "\\]"
                source.startsWith("${'$'}${'$'}", index) && !escaped(index) -> "${'$'}${'$'}" to "${'$'}${'$'}"
                source[index] == '$' && !escaped(index) -> "${'$'}" to "${'$'}"
                else -> null
            }
            if (opening == null) { index++; continue }
            val (left, right) = opening
            val display = left == "\\[" || left.length == 2 && left[0] == '$'
            val bodyStart = index + left.length
            val absent = withoutClosing[right]
            if (absent != null && bodyStart >= absent.first && bodyStart <= absent.second) {
                index += left.length; continue
            }
            var close = bodyStart
            while (close < source.length) {
                check(close)
                if (!display && source[close] == '\n') break
                if (source.startsWith(right, close) && !escaped(close)) break
                close++
            }
            val complete = close < source.length && source.startsWith(right, close) && !escaped(close)
            if (!complete) {
                withoutClosing[right] = bodyStart to close
                index += left.length; continue
            }
            val body = source.substring(bodyStart, close)
            val moneySafe = left != "${'$'}" || body.isNotEmpty() && !body.first().isWhitespace() && !body.last().isWhitespace() &&
                (close + right.length >= source.length || !source[close + right.length].isDigit())
            if (complete && body.isNotBlank() && moneySafe) {
                val end = close + right.length
                formulas.add(Formula(index, end, source.substring(index, end), body, display))
                index = end
            } else index += left.length
        }
        return formulas
    }
}
