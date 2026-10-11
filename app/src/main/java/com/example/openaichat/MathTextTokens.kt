package com.example.openaichat

/** Presentation-only placeholders survive Markdown without making LaTeX Markdown syntax. */
object MathTextTokens {
    data class Prepared(val markdown: String, val prefix: String, val items: List<Pair<String, MathDelimiterScanner.Formula>>)
    fun prepare(source: String, formulas: List<MathDelimiterScanner.Formula>): Prepared {
        val prefix = "MATH" + java.util.UUID.randomUUID().toString().replace("-", "") + "X"
        val output = StringBuilder(source.length)
        val items = mutableListOf<Pair<String, MathDelimiterScanner.Formula>>()
        var cursor = 0
        formulas.forEachIndexed { index, formula ->
            require(formula.start >= cursor && formula.end <= source.length && formula.start < formula.end)
            require(source.substring(formula.start, formula.end) == formula.source)
            val token = prefix + index + "END"
            output.append(source, cursor, formula.start).append(token)
            items.add(token to formula); cursor = formula.end
        }
        output.append(source, cursor, source.length)
        return Prepared(output.toString(), prefix, items)
    }
}
