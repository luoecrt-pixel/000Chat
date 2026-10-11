package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

/** Counts actual source scans, including canonical fingerprints; never logs paths or contents. */
internal object FileBackedIoTelemetry {
    data class Snapshot(val files: Int, val readPasses: Long, val readBytes: Long, val workNanos: Long)
    fun snapshot(value: Any?): Snapshot {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var files = 0; var passes = 0L; var bytes = 0L; var nanos = 0L
        fun visit(item: Any?) {
            if (item == null || (item !is FileBackedDataUrl && item !is JSONObject && item !is JSONArray)) return
            if (!seen.add(item)) return
            when (item) {
                is FileBackedDataUrl -> item.ioStats().let { files += it.files; passes += it.readPasses; bytes += it.readBytes; nanos += it.workNanos }
                is JSONObject -> item.keys().forEach { visit(item.opt(it)) }
                is JSONArray -> for (i in 0 until item.length()) visit(item.opt(i))
            }
        }
        visit(value)
        return Snapshot(files, passes, bytes, nanos)
    }
}
