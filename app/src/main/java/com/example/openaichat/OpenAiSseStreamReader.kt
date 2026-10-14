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
        val maxStreamChars: Long = 512L * 1024 * 1024,
        val maxAllocationBytes: Long = Long.MAX_VALUE,
        // Internal deterministic pressure seam. Production uses measured heap.
        val maxSharedAllocationBytes: Long = Long.MAX_VALUE
    ) {
        init {
            require(maxLineChars > 0 && maxEventChars >= maxLineChars && maxStreamChars >= maxEventChars)
            require(maxAllocationBytes > 0L && maxSharedAllocationBytes > 0L)
        }
    }

    fun read(reader: BufferedReader, onEvent: (JSONObject) -> Boolean): OpenAiSseReadResult =
        read(reader, Limits(), onEvent)

    internal fun read(reader: BufferedReader, limits: Limits, onEvent: (JSONObject) -> Boolean): OpenAiSseReadResult {
        var terminal: OpenAiStreamEnd? = null
        var semanticError: String? = null
        fun consume(data: String): Boolean {
            if (data == "[DONE]") return true
            val event = try {
                JSONObject(data)
            } catch (e: Exception) {
                throw UnrecoverableResponsesStreamException("Malformed Responses SSE JSON", e)
            }
            val type = JsonProtocol.string(event, "type")
            if (type.isBlank()) throw UnrecoverableResponsesStreamException("Responses SSE event has no type")
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

        BoundedResponseDecoder.readSse(reader, BoundedResponseDecoder.SseLimits(
            maxLineChars = limits.maxLineChars,
            maxEventChars = limits.maxEventChars,
            maxStreamChars = limits.maxStreamChars,
            maxAllocationBytes = limits.maxAllocationBytes,
            maxSharedAllocationBytes = limits.maxSharedAllocationBytes
        )) { frame -> consume(frame.data) }

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
