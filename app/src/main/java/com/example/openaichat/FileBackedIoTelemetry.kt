package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

/** Counts actual source scans, including canonical fingerprints; never logs paths or contents. */
internal object FileBackedIoTelemetry {
    data class Snapshot(val files: Int, val readPasses: Long, val readBytes: Long, val workNanos: Long) {
        val workMs: Long get() = workNanos / 1_000_000L
        val hasSourceWork: Boolean get() = files > 0 || readPasses > 0 || readBytes > 0
        fun deltaFrom(previous: Snapshot?): Snapshot = if (previous == null) this else Snapshot(
            files, (readPasses - previous.readPasses).coerceAtLeast(0L),
            (readBytes - previous.readBytes).coerceAtLeast(0L),
            (workNanos - previous.workNanos).coerceAtLeast(0L))
        fun toJson(): JSONObject = JSONObject().put("files", files)
            .put("source_read_passes", readPasses).put("source_read_bytes", readBytes)
            .put("file_work_ms", workMs)
    }
    fun snapshot(value: Any?): Snapshot {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var files = 0; var passes = 0L; var bytes = 0L; var nanos = 0L
        fun visit(item: Any?) {
            if (item == null || (item !is FileBackedDataUrl && item !is FileBackedText && item !is JSONObject && item !is JSONArray)) return
            if (!seen.add(item)) return
            when (item) {
                is FileBackedDataUrl -> item.ioStats().let { files += it.files; passes += it.readPasses; bytes += it.readBytes; nanos += it.workNanos }
                is FileBackedText -> item.ioStats().let { files += it.files; passes += it.readPasses; bytes += it.readBytes; nanos += it.workNanos }
                is JSONObject -> item.keys().forEach { visit(item.opt(it)) }
                is JSONArray -> for (i in 0 until item.length()) visit(item.opt(i))
            }
        }
        visit(value)
        return Snapshot(files, passes, bytes, nanos)
    }
    /** Compact counters only: no attachment paths, arguments, file contents or credentials. */
    fun mergeCompactTelemetry(
        toolTelemetry: JSONObject? = null,
        fileIo: Snapshot? = null,
        fileIoJson: JSONObject? = null,
        costDetails: JSONObject? = null
    ): JSONObject? {
        val out = JSONObject()
        toolTelemetry?.let { tools ->
            for (key in listOf("rounds_completed", "calls_executed")) {
                if (tools.has(key)) out.put(key, tools.optLong(key, 0L).coerceAtLeast(0L))
            }
            tools.optJSONArray("executed_tools")?.let { names ->
                val clean = (0 until names.length()).mapNotNull { i ->
                    JsonProtocol.string(names, i).trim().takeIf { it.isNotEmpty() }
                }.distinct()
                if (clean.isNotEmpty()) out.put("executed_tools", JSONArray(clean))
            }
            tools.optJSONObject(OpenAiUsageLedger.FIELD)?.let {
                out.put(OpenAiUsageLedger.FIELD, JsonProtocol.objectCopy(it))
            }
        }
        val io = fileIo?.toJson() ?: fileIoJson
        if (io != null && (io.optInt("files") > 0 || io.optLong("source_read_passes") > 0L || io.optLong("source_read_bytes") > 0L)) {
            out.put("files", io.optInt("files").coerceAtLeast(0))
            for (key in listOf("source_read_passes", "source_read_bytes", "file_work_ms")) {
                out.put(key, io.optLong(key).coerceAtLeast(0L))
            }
        }
        costDetails?.takeIf { it.length() > 0 }?.let { out.put("cost_details", JsonProtocol.objectCopy(it)) }
        return out.takeIf { it.length() > 0 }
    }

    /** Missing values retain existing observations; the response ledger has its own merge rules. */
    fun mergeTelemetryJson(existingRaw: String?, incoming: JSONObject?): String? {
        val existing = existingRaw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (incoming == null || incoming.length() == 0) return existing?.toString()
        val merged = existing?.let(JsonProtocol::objectCopy) ?: JSONObject()
        incoming.keys().forEach { key ->
            val value = incoming.opt(key)
            if (value == null || value == JSONObject.NULL) return@forEach
            when {
                key == OpenAiUsageLedger.FIELD && value is JSONObject ->
                    merged.put(key, OpenAiUsageLedger.merge(merged.optJSONObject(key), value))
                key == "cost_details" && value is JSONObject ->
                    merged.put(key, mergeObjects(merged.optJSONObject(key), value))
                key in setOf("rounds_completed", "calls_executed", "files", "source_read_passes", "source_read_bytes", "file_work_ms") ->
                    value.toString().toLongOrNull()?.takeIf { it >= 0L }?.let {
                        merged.put(key, maxOf(merged.optLong(key, 0L), it))
                    }
                key == "executed_tools" && value is JSONArray -> {
                    val previous = merged.optJSONArray(key) ?: JSONArray()
                    val names = listOf(previous, value).flatMap { names ->
                        (0 until names.length()).mapNotNull { index ->
                            JsonProtocol.text(names, index)?.trim()?.takeIf { it.isNotEmpty() }
                        }
                    }.distinct()
                    if (names.isNotEmpty()) merged.put(key, JSONArray(names))
                }
                else -> merged.put(key, JsonProtocol.copy(value))
            }
        }
        return merged.takeIf { it.length() > 0 }?.toString()
    }

    private fun mergeObjects(existing: JSONObject?, incoming: JSONObject): JSONObject =
        (existing?.let(JsonProtocol::objectCopy) ?: JSONObject()).apply {
            incoming.keys().forEach { key ->
                val value = incoming.opt(key)
                if (value == null || value == JSONObject.NULL) return@forEach
                put(key, if (value is JSONObject) mergeObjects(optJSONObject(key), value) else JsonProtocol.copy(value))
            }
        }
}
