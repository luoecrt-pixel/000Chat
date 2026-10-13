package com.example.openaichat

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException

enum class OpenAiStreamEnd(val wireStatus: String) {
    COMPLETED("completed"),
    INCOMPLETE("incomplete"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    DISCONNECTED("disconnected")
}

data class OpenAiSseReadResult(
    val end: OpenAiStreamEnd,
    val errorMessage: String? = null
)

/**
 * Responses SSE framing and end semantics, shared by foreground and resume.
 * [DONE] or EOF without a Responses terminal event is never success.
 * Terminal events return immediately: the remote server need not close its socket.
 * Callers own response state, event sequencing and UI/persistence ownership.
 */
object OpenAiSseStreamReader {
    // Hard limits on *decoded characters* (UTF-8 bytes can be up to 4x).
    // Limits are deliberately larger than ordinary text events because tool
    // image_generation_call results may contain PNG base64 in terminal JSON.
    // Exceeding a limit is an explicit failure, never a truncated success.
    internal data class Limits(
        val maxLineChars: Int = 128 * 1024 * 1024,
        val maxEventChars: Int = 160 * 1024 * 1024,
        val maxStreamChars: Long = 512L * 1024 * 1024
    ) {
        init {
            require(maxLineChars > 0 && maxEventChars >= maxLineChars && maxStreamChars >= maxEventChars)
        }
    }

    fun read(reader: BufferedReader, onEvent: (JSONObject) -> Boolean): OpenAiSseReadResult =
        read(reader, Limits(), onEvent)

    internal fun read(reader: BufferedReader, limits: Limits, onEvent: (JSONObject) -> Boolean): OpenAiSseReadResult {
        val parser = SseEventParser()
        var terminal: OpenAiStreamEnd? = null
        var semanticError: String? = null
        var eventChars = 0L
        var streamChars = 0L

        // BufferedReader.readLine() can allocate an unbounded string *before*
        // we can inspect its length; read incrementally and fail at the boundary.
        fun boundedLine(): String? {
            val line = StringBuilder()
            while (true) {
                val ch = reader.read()
                if (ch < 0) return if (line.isEmpty()) null else line.toString()
                if (ch == '\n'.code) return line.toString()
                if (line.length >= limits.maxLineChars) {
                    throw IOException("Responses SSE line limit exceeded: ${limits.maxLineChars} characters")
                }
                line.append(ch.toChar())
            }
        }

        fun consume(data: String): Boolean {
            if (data == "[DONE]") return true
            val event = try {
                JSONObject(data)
            } catch (e: Exception) {
                throw IOException("Malformed Responses SSE JSON", e)
            }
            val type = JsonProtocol.string(event, "type")
            if (type.isBlank()) throw IOException("Responses SSE event has no type")
            // Duplicated/out-of-order frames rejected by the sequence owner must
            // not supply a terminal event or close the transport prematurely.
            if (!onEvent(event)) return false
            terminal = when (type) {
                "response.completed" -> OpenAiStreamEnd.COMPLETED
                "response.incomplete" -> OpenAiStreamEnd.INCOMPLETE
                "response.cancelled" -> OpenAiStreamEnd.CANCELLED
                "response.failed", "error", "response.error" -> OpenAiStreamEnd.FAILED
                else -> null
            }
            if (type == "error" || type == "response.error") {
                semanticError = event.optJSONObject("error")?.let { JsonProtocol.nonBlank(it, "message") }
                    ?: JsonProtocol.nonBlank(event, "message")
                    ?: "Responses streaming error"
            }
            return terminal != null
        }

        while (true) {
            val line = boundedLine() ?: break
            streamChars += line.length.toLong() + 1L
            if (streamChars > limits.maxStreamChars)
                throw IOException("Responses SSE stream limit exceeded: ${limits.maxStreamChars} characters")
            eventChars += line.length.toLong() + 1L
            if (eventChars > limits.maxEventChars)
                throw IOException("Responses SSE event limit exceeded: ${limits.maxEventChars} characters")
            val frame = parser.acceptLine(line) ?: continue
            eventChars = 0L
            if (consume(frame.data)) break
        }
        // A final frame without a trailing blank line is still a real SSE record.
        if (terminal == null) parser.finish()?.let { consume(it.data) }

        return OpenAiSseReadResult(
            end = terminal ?: OpenAiStreamEnd.DISCONNECTED,
            errorMessage = semanticError
        )
    }

    fun terminalOutputText(response: JSONObject?): String {
        val output = response?.optJSONArray("output") ?: return ""
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (JsonProtocol.string(item, "type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                when (JsonProtocol.string(part, "type")) {
                    "output_text" -> text.append(JsonProtocol.string(part, "text"))
                    "refusal" -> text.append(JsonProtocol.string(part, "refusal"))
                }
            }
        }
        return text.toString()
    }
}
