package com.example.openaichat

/**
 * Minimal SSE framing parser for Responses streaming.
 *
 * It follows the SSE record rules that matter to the app: comments are ignored,
 * data lines may be repeated and are joined with '\n', a single optional space
 * after the colon is removed, and a blank line dispatches the accumulated event.
 */
data class SseEvent(
    val event: String?,
    val data: String,
    val id: String?
)

class SseEventParser {
    private val dataLines = mutableListOf<String>()
    private var eventType: String? = null
    private var lastEventId: String? = null

    fun acceptLine(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        var value = if (colon >= 0) line.substring(colon + 1) else ""
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "data" -> dataLines += value
            "event" -> eventType = value
            "id" -> lastEventId = value
            "retry" -> Unit
        }
        return null
    }

    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        if (dataLines.isEmpty()) {
            eventType = null
            return null
        }
        val result = SseEvent(
            event = eventType,
            data = dataLines.joinToString("\n"),
            id = lastEventId
        )
        dataLines.clear()
        eventType = null
        return result
    }
}
