package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Durable counterpart to active_text, written in the same SQLite CAS row update
 * as the SSE sequence. Reconnect/WorkManager must restore the indexed parts
 * instead of treating already assembled output as an unordered text suffix.
 */
/**
 * No retry of the same unrecoverable Responses cursor. An owned failure
 * finalization preserves the received text and makes the issue visible.
 */
class UnrecoverableResponsesCheckpointException(
    message: String, cause: Throwable? = null
) : IOException(message, cause)

object ResponseIndexedCheckpoint {
    private const val VERSION = 1

    fun serialize(state: ResponseEventState): String {
        val parts = JSONArray()
        state.indexedTextParts.toSortedMap().forEach { (key, value) ->
            parts.put(JSONObject().put("output_index", key.outputIndex)
                .put("content_index", key.contentIndex).put("text", value))
        }
        val typedItems = JSONArray()
        state.typedOutputItems.toSortedMap().forEach { (index, item) ->
            typedItems.put(JSONObject().put("output_index", index)
                .put("item", JSONObject(item.toString())))
        }
        val assembled = state.indexedTextParts.toSortedMap().values.joinToString("") + state.unindexedText
        require(assembled == state.text) { "Responses checkpoint accumulator and text differ" }
        return JSONObject().put("version", VERSION)
            .put("text", state.text)
            .put("unindexed_text", state.unindexedText)
            .put("reasoning_summary", state.reasoningSummary)
            .put("legacy_flat_checkpoint", state.legacyFlatCheckpoint)
            .put("terminal_state", state.terminalState?.wireStatus ?: JSONObject.NULL)
            .apply {
                // The paid terminal output must survive a crash between the
                // terminal cursor write and one-shot chat finalization.
                state.terminalResponse?.let {
                    put("terminal_response", JSONObject(it.toString()))
                }
            }
            .put("parts", parts)
            .put("typed_output_items", typedItems).toString()
    }

    fun restore(active: JSONObject): ResponseEventState {
        val responseId = JsonProtocol.nonBlank(active, "response_id")
        val text = JsonProtocol.string(active, "text")
        val raw = JsonProtocol.string(active, "indexed_checkpoint")
        if (raw.isBlank()) {
            // Existing v23 in-progress responses have only flat text. It is
            // impossible to infer past output/content indices from that text.
            // Keep unindexed legacy deltas in arrival order; explicitly reject
            // indexed continuation instead of silently corrupting the answer.
            return ResponseEventState(responseId = responseId, text = text, unindexedText = text,
                legacyFlatCheckpoint = text.isNotEmpty() &&
                    active.optLong("last_sequence_number", -1L) >= 0L)
        }
        val snapshot = try { JSONObject(raw) } catch (error: Exception) {
            throw UnrecoverableResponsesCheckpointException("Damaged Responses indexed checkpoint", error)
        }
        if (snapshot.optInt("version") != VERSION)
            throw UnrecoverableResponsesCheckpointException("Unsupported Responses indexed checkpoint version")
        val indexed = linkedMapOf<ResponseContentIndex, String>()
        val parts = snapshot.optJSONArray("parts")
            ?: throw UnrecoverableResponsesCheckpointException("Missing Responses indexed checkpoint parts")
        for (i in 0 until parts.length()) {
            val item = parts.optJSONObject(i)
                ?: throw UnrecoverableResponsesCheckpointException("Invalid Responses indexed checkpoint item")
            val out = item.optInt("output_index", -1)
            val content = item.optInt("content_index", -1)
            if (out < 0 || content < 0 || !item.has("text"))
                throw UnrecoverableResponsesCheckpointException("Invalid Responses indexed checkpoint coordinates")
            val key = ResponseContentIndex(out, content)
            if (indexed.put(key, JsonProtocol.string(item, "text")) != null)
                throw UnrecoverableResponsesCheckpointException("Duplicate Responses indexed checkpoint coordinate")
        }
        val loose = JsonProtocol.string(snapshot, "unindexed_text")
        val expected = indexed.toSortedMap().values.joinToString("") + loose
        if (JsonProtocol.string(snapshot, "text") != text || expected != text) {
            // Refuse a corrupt cursor/text/parts pair before the resumed SSE
            // mutates the checkpoint or changes the next model's context.
            throw UnrecoverableResponsesCheckpointException("Responses indexed checkpoint text mismatch")
        }
        val typedItems = linkedMapOf<Int, JSONObject>()
        snapshot.optJSONArray("typed_output_items")?.let { array ->
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i)
                    ?: throw UnrecoverableResponsesCheckpointException("Invalid typed Responses item")
                val index = entry.optInt("output_index", -1)
                val item = entry.optJSONObject("item")
                    ?: throw UnrecoverableResponsesCheckpointException("Missing typed Responses item")
                if (index < 0 || JsonProtocol.nonBlank(item, "type") == null ||
                    typedItems.put(index, JSONObject(item.toString())) != null) {
                    throw UnrecoverableResponsesCheckpointException(
                        "Duplicated or unsafe typed Responses output item")
                }
            }
        }
        val terminalName = JsonProtocol.string(snapshot, "terminal_state")
        val terminalState = if (terminalName.isBlank()) null
            else ResponseTerminalState.entries.firstOrNull { it.wireStatus == terminalName }
                ?: throw UnrecoverableResponsesCheckpointException("Unknown Responses terminal state")
        val terminalResponse = snapshot.optJSONObject("terminal_response")
        val terminalId = terminalResponse?.let { JsonProtocol.nonBlank(it, "id") }
        if (responseId != null && terminalId != null && responseId != terminalId)
            throw UnrecoverableResponsesCheckpointException("Responses checkpoint response ID mismatch")
        if (terminalResponse != null && terminalState == null)
            throw UnrecoverableResponsesCheckpointException("Terminal Responses output without terminal state")
        if (terminalState != null && terminalResponse == null) {
            // No paid terminal provider output exists in the checkpoint; a
            // retry cannot reconstruct it, so do not silently declare success.
            throw UnrecoverableResponsesCheckpointException("Terminal Responses output was not preserved")
        }
        return ResponseEventState(responseId = responseId ?: terminalId, text = text, unindexedText = loose,
            indexedTextParts = indexed,
            reasoningSummary = JsonProtocol.string(snapshot, "reasoning_summary"),
            legacyFlatCheckpoint = snapshot.optBoolean("legacy_flat_checkpoint", false),
            terminalState = terminalState,
            terminalResponse = terminalResponse,
            completionError = when (terminalState) {
                ResponseTerminalState.FAILED -> terminalResponse?.optJSONObject("error")?.let {
                    JsonProtocol.nonBlank(it, "message")
                } ?: terminalResponse?.let { JsonProtocol.nonBlank(it, "error") }
                ResponseTerminalState.INCOMPLETE -> terminalResponse?.optJSONObject("incomplete_details")?.let {
                    JsonProtocol.nonBlank(it, "reason")
                }
                else -> null
            },
            usage = terminalResponse?.optJSONObject("usage"),
            typedOutputItems = typedItems)
    }
}
