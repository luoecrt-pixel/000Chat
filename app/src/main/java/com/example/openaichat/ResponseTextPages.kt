package com.example.openaichat

/** UI pagination only: source, clipboard and API history remain complete. */
object ResponseTextPages {
    const val CHARACTERS = 32_000
    const val SAFE_UI_SOURCE_BUDGET = 512_000

    data class Page(
        val index: Int,
        val count: Int,
        val text: String,
        val fallbackNotice: String? = null
    )

    private data class PageSlice(val start: Int, val end: Int, val notice: String?)
    private data class CachedLayout(val source: String, val slices: List<PageSlice>)

    @Volatile
    private var cachedLayout: CachedLayout? = null

    private fun computeSlices(source: String): List<PageSlice> {
        val cached = cachedLayout
        if (cached != null && cached.source == source) {
            return cached.slices
        }
        val oversizedSource = source.length > SAFE_UI_SOURCE_BUDGET
        val formulas = if (!oversizedSource && source.length > CHARACTERS &&
            (source.indexOf('$') >= 0 || source.indexOf('\\') >= 0)
        ) {
            try { MathDelimiterScanner.scan(source) } catch (_: Exception) { emptyList() }
        } else {
            emptyList()
        }
        val oversizedFormulas = formulas.filter {
            it.latex.length > ScientificRendererDocument.MAX_MATH_CHARACTERS
        }
        val slices = mutableListOf<PageSlice>()
        var start = 0
        while (start < source.length) {
            var end = minOf(source.length.toLong(), start.toLong() + CHARACTERS).toInt()
            var notice: String? = if (oversizedSource) {
                "Источник превышает безопасный бюджет UI-разбора формул; показан полный текст по страницам."
            } else {
                null
            }
            if (end < source.length) {
                val crossing = formulas.firstOrNull { it.start < end && it.end > end }
                if (crossing != null) {
                    val formulaLength = crossing.end - crossing.start
                    when {
                        crossing.start > start && formulaLength <= CHARACTERS -> {
                            end = crossing.start
                        }
                        formulaLength <= CHARACTERS && crossing.end <= source.length -> {
                            end = crossing.end
                        }
                    }
                }
                if (end > start + 1 && end < source.length &&
                    Character.isHighSurrogate(source[end - 1]) &&
                    Character.isLowSurrogate(source[end])
                ) {
                    end--
                }
            }
            if (end <= start) {
                end = minOf(source.length.toLong(), start.toLong() + CHARACTERS).toInt()
                if (end > start + 1 && end < source.length &&
                    Character.isHighSurrogate(source[end - 1]) &&
                    Character.isLowSurrogate(source[end])
                ) {
                    end--
                }
            }
            if (notice == null && oversizedFormulas.any { it.start < end && it.end > start }) {
                notice = "Формула на границе страницы превышает безопасный бюджет превью; показан исходный текст без потерь."
            }
            slices += PageSlice(start, end, notice)
            start = end
        }
        cachedLayout = CachedLayout(source, slices)
        return slices
    }

    fun page(source: String, requested: Int): Page {
        if (source.isEmpty()) {
            return Page(index = 0, count = 1, text = "")
        }
        val slices = computeSlices(source)
        val count = slices.size.coerceAtLeast(1)
        val index = requested.coerceIn(0, count - 1)
        val selected = slices[index]
        return Page(
            index = index,
            count = count,
            text = source.substring(selected.start, selected.end),
            fallbackNotice = selected.notice
        )
    }
}
