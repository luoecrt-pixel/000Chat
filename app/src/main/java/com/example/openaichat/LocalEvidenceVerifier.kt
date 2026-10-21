package com.example.openaichat

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/** Evidence about local tool reads and exact text presence; never a claim that an answer is true. */
object LocalEvidenceVerifier {
    data class Coverage(val fileId: String, val readChars: Long, val totalChars: Long?, val exactFullRows: Boolean) {
        val partial: Boolean get() = !exactFullRows && (totalChars == null || readChars < totalChars)
        val percent: Int? get() = totalChars?.takeIf { it > 0 }?.let { (readChars.toDouble() / it * 100).toInt().coerceIn(0, 100) }
    }
    private data class Event(val name: String, val args: JSONObject, val result: JSONObject)
    fun evidence(name: String, args: JSONObject, result: JSONObject): JSONObject? {
        if (!result.optBoolean("ok", false) || name !in setOf("file_read_range", "read_attached_file", "file_table_stats", "read_attached_table")) return null
        val out = JSONObject().put("file_id", result.optString("file_id", args.optString("file_id")))
        for (key in listOf("offset", "next_offset", "total_chars", "eof", "complete_requested_range", "offset_past_eof"))
            if (result.has(key)) out.put(key, result.opt(key))
        for (key in listOf("start_row", "end_row", "sheet")) if (args.has(key)) out.put(key, args.opt(key))
        return out
    }
    fun coverage(message: JSONObject): List<Coverage> {
        val events = mutableListOf<Event>()
        message.optJSONArray("tool_trace")?.let { trace -> for (i in 0 until trace.length()) {
            val entry = trace.optJSONObject(i) ?: continue
            val proof = entry.optJSONObject("evidence") ?: continue
            if (entry.optBoolean("ok", false)) events.add(Event(entry.optString("name"), proof, proof))
        } }
        message.optJSONArray("openai_protocol_output")?.let { output ->
            val calls = mutableMapOf<String, JSONObject>(); val duplicate = mutableSetOf<String>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (item.optString("type") == "function_call") {
                    val id = item.optString("call_id")
                    if (calls.put(id, item) != null) duplicate.add(id)
                }
            }
            val resultIds = mutableSetOf<String>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (item.optString("type") == "function_call_output" && !resultIds.add(item.optString("call_id")))
                    duplicate.add(item.optString("call_id"))
            }
            val received = mutableSetOf<String>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (item.optString("type") != "function_call_output") continue
                val id = item.optString("call_id"); if (id in duplicate || !received.add(id)) continue
                val call = calls[id] ?: continue
                val args = runCatching { JSONObject(call.getString("arguments")) }.getOrNull() ?: continue
                val result = runCatching { JSONObject(item.getString("output")) }.getOrNull() ?: continue
                evidence(call.optString("name"), args, result)?.let { events.add(Event(call.optString("name"), args, it)) }
            }
        }
        val intervals = linkedMapOf<String, MutableList<Pair<Long, Long>>>()
        val totals = mutableMapOf<String, MutableSet<Long>>()
        val exact = mutableSetOf<String>()
        for ((name, args, result) in events) {
            val id = result.optString("file_id"); if (id.isBlank()) continue
            if (name in setOf("file_table_stats", "read_attached_table")) {
                if (result.optBoolean("complete_requested_range") && args.optLong("start_row", 1) == 1L &&
                    !args.has("end_row") && !args.has("sheet")) exact.add(id)
                continue
            }
            if (result.optBoolean("offset_past_eof")) continue
            val start = result.optLong("offset", -1); val end = result.optLong("next_offset", -1)
            if (start < 0 || end < start) continue
            intervals.getOrPut(id) { mutableListOf() }.add(start to end)
            if (result.has("total_chars")) result.optLong("total_chars", -1).takeIf { it >= end }?.let {
                totals.getOrPut(id) { mutableSetOf() }.add(it)
            }
        }
        return intervals.map { (id, ranges) ->
            var count = 0L; var end = 0L
            for ((from, to) in ranges.sortedBy { it.first }) {
                if (to > end) { count += to - maxOf(from, end); end = to }
            }
            Coverage(id, count, totals[id]?.singleOrNull(), id in exact)
        }
    }
    data class Match(val source: OpenRouterLocalAttachment, val offset: Long)
    data class Quotes(val matches: List<Match>, val incomplete: Boolean) {
        val label: String get() = when {
            matches.isNotEmpty() -> "✓ Цитата найдена в исходном файле"
            incomplete -> "Проверка неполна: часть исходников недоступна"
            else -> "Не найдено в исходном файле"
        }
    }
    internal fun textFile(a: OpenRouterLocalAttachment): File {
        if (a.kind !in setOf("text", "document", "table")) throw IOException("Нет текстового представления")
        return a.textPath?.let(::File) ?: if (a.kind == "text" && (a.textEncoding == null || a.textEncoding.equals("UTF-8", true)))
            File(a.path) else throw IOException("Нет сохранённого UTF-8 представления")
    }
    fun quotes(query: String, sources: List<OpenRouterLocalAttachment>, active: () -> Boolean = { true }): Quotes {
        require(query.length in 1..4096) { "Цитата должна содержать 1–4096 символов" }
        val matches = mutableListOf<Match>(); var incomplete = sources.isEmpty()
        for (source in sources) {
            if (!active() || Thread.currentThread().isInterrupted) throw IOException("Проверка отменена")
            try {
                OpenRouterAttachmentPreflight.verifySources(listOf(source))
                val file = textFile(source); val size = file.length(); val modified = file.lastModified()
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                var position = 0L; var tail = ""; var match: Long? = null
                InputStreamReader(file.inputStream(), decoder).use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        if (!active() || Thread.currentThread().isInterrupted) throw IOException("Проверка отменена")
                        val count = reader.read(buffer); if (count < 0) break
                        val chunk = tail + String(buffer, 0, count)
                        val at = chunk.indexOf(query)
                        if (at >= 0) { match = position - tail.length + at; break }
                        tail = chunk.takeLast(query.length - 1); position += count
                    }
                }
                if (size != file.length() || modified != file.lastModified()) throw IOException("Исходник изменён")
                OpenRouterAttachmentPreflight.verifySources(listOf(source))
                match?.let { matches.add(Match(source, it)) }
            } catch (e: IOException) {
                if (!active() || Thread.currentThread().isInterrupted) throw e
                incomplete = true
            }
        }
        return Quotes(matches, incomplete)
    }
}
