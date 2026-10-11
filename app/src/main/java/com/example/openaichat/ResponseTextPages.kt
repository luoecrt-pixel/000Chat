package com.example.openaichat

/** UI pagination only: source, clipboard and API history remain complete. */
object ResponseTextPages {
    const val CHARACTERS = 32_000
    data class Page(val index: Int, val count: Int, val text: String)
    fun page(source: String, requested: Int): Page {
        val count = ((source.length.toLong() + CHARACTERS - 1L) / CHARACTERS).coerceAtLeast(1).toInt()
        val index = requested.coerceIn(0, count - 1)
        var start = index * CHARACTERS
        var end = minOf(source.length.toLong(), start.toLong() + CHARACTERS).toInt()
        if (start > 0 && start < source.length && Character.isHighSurrogate(source[start - 1]) && Character.isLowSurrogate(source[start])) start--
        if (end > 0 && end < source.length && Character.isHighSurrogate(source[end - 1]) && Character.isLowSurrogate(source[end])) end--
        return Page(index, count, source.substring(start, end))
    }
}
