package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Durable counterpart to active_text, written in the same SQLite CAS row update
 * as the SSE sequence. Reconnect/WorkManager must restore the indexed parts
 * instead of treating already assembled output as an unordered text suffix.
 */
object ResponseIndexedCheckpoint {
    private const val VERSION = 1

    fun serialize(state: ResponseEventState): String {
        val parts = JSONArray()
        state.indexedTextParts.toSortedMap().forEach { (key, value) ->
            parts.put(JSONObject().put("output_index", key.outputIndex)
                .put("content_index", key.contentIndex).put("text", value))
        }
        return JSONObject().put("version", VERSION)
            .put("text", state.text)
            .put("unindexed_text", state.unindexedText)
            .put("reasoning_summary", state.reasoningSummary)
            .put("parts", parts).toString()
    }

    fun restore(active: JSONObject): ResponseEventState {
        val text = JsonProtocol.string(active, "text")
        val raw = JsonProtocol.string(active, "indexed_checkpoint")
        if (raw.isBlank()) {
            // Existing v23 in-progress responses have only flat text. It is
            // impossible to infer past output/content indices from that text.
            // Keep unindexed legacy deltas in arrival order; explicitly reject
            // indexed continuation instead of silently corrupting the answer.
            return ResponseEventState(text = text, unindexedText = text,
                legacyFlatCheckpoint = text.isNotEmpty() &&
                    active.optLong("last_sequence_number", -1L) >= 0L)
        }
        val snapshot = try { JSONObject(raw) } catch (error: Exception) {
            throw IOException("Damaged Responses indexed checkpoint", error)
        }
        if (snapshot.optInt("version") != VERSION)
            throw IOException("Unsupported Responses indexed checkpoint version")
        val indexed = linkedMapOf<ResponseContentIndex, String>()
        val parts = snapshot.optJSONArray("parts")
            ?: throw IOException("Missing Responses indexed checkpoint parts")
        for (i in 0 until parts.length()) {
            val item = parts.optJSONObject(i)
                ?: throw IOException("Invalid Responses indexed checkpoint item")
            val out = item.optInt("output_index", -1)
            val content = item.optInt("content_index", -1)
            if (out < 0 || content < 0 || !item.has("text"))
                throw IOException("Invalid Responses indexed checkpoint coordinates")
            val key = ResponseContentIndex(out, content)
            if (indexed.put(key, JsonProtocol.string(item, "text")) != null)
                throw IOException("Duplicate Responses indexed checkpoint coordinate")
        }
        val loose = JsonProtocol.string(snapshot, "unindexed_text")
        val expected = indexed.toSortedMap().values.joinToString("") + loose
        if (JsonProtocol.string(snapshot, "text") != text || expected != text) {
            // Refuse a corrupt cursor/text/parts pair before the resumed SSE
            // mutates the checkpoint or changes the next model's context.
            throw IOException("Responses indexed checkpoint text mismatch")
        }
        return ResponseEventState(text = text, unindexedText = loose,
            indexedTextParts = indexed,
            reasoningSummary = JsonProtocol.string(snapshot, "reasoning_summary"))
    }
}
