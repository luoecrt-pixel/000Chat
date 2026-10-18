package com.example.openaichat

/** UI pagination only: source, clipboard and API history remain complete. */
object ResponseTextPages {
    const val CHARACTERS = 32_000
    const val SAFE_UI_SOURCE_BUDGET = 512_000
    private const val OVERSIZED_PAGE_STRIDE = CHARACTERS - 1

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
        val formulas = if (source.length > CHARACTERS &&
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
            var notice: String? = null
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

    private fun oversizedPage(source: String, requested: Int): Page {
        // A spare UTF-16 unit keeps every page within CHARACTERS when a boundary moves
        // back over a surrogate pair. Each page needs only its two boundaries, no scan/cache.
        val count = ((source.length.toLong() + OVERSIZED_PAGE_STRIDE - 1) /
            OVERSIZED_PAGE_STRIDE).toInt()
        val index = requested.coerceIn(0, count - 1)
        fun boundary(offset: Long): Int {
            val end = minOf(source.length.toLong(), offset).toInt()
            return if (end > 0 && end < source.length &&
                Character.isHighSurrogate(source[end - 1]) &&
                Character.isLowSurrogate(source[end])
            ) end - 1 else end
        }
        val start = boundary(index.toLong() * OVERSIZED_PAGE_STRIDE)
        val end = boundary((index.toLong() + 1) * OVERSIZED_PAGE_STRIDE)
        return Page(
            index = index,
            count = count,
            text = source.substring(start, end),
            fallbackNotice = "Источник превышает безопасный бюджет UI-разбора формул; показан полный текст по страницам."
        )
    }

    fun page(source: String, requested: Int): Page {
        if (source.isEmpty()) {
            return Page(index = 0, count = 1, text = "")
        }
        if (source.length > SAFE_UI_SOURCE_BUDGET) {
            return oversizedPage(source, requested)
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
