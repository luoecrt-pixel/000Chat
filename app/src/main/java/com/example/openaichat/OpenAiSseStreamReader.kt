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
    fun read(reader: BufferedReader, onEvent: (JSONObject) -> Boolean): OpenAiSseReadResult {
        val parser = SseEventParser()
        var terminal: OpenAiStreamEnd? = null
        var semanticError: String? = null

        fun consume(data: String): Boolean {
            if (data == "[DONE]") return true
            val event = try {
                JSONObject(data)
            } catch (e: Exception) {
                throw IOException("Malformed Responses SSE JSON", e)
            }
            val type = event.optString("type")
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
                semanticError = event.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: event.optString("message").takeIf { it.isNotBlank() }
                    ?: "Responses streaming error"
            }
            return terminal != null
        }

        while (true) {
            val line = reader.readLine() ?: break
            val frame = parser.acceptLine(line) ?: continue
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
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                when (part.optString("type")) {
                    "output_text" -> text.append(part.optString("text"))
                    "refusal" -> text.append(part.optString("refusal"))
                }
            }
        }
        return text.toString()
    }
}
