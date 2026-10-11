package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Compare only durable attachment fields; never replace a stale chat snapshot. */
object AttachmentMessageState {
    val arrays = listOf("file_ids", "file_names", "file_sizes", "file_paths", "file_mimes", "attachments")
    val strings = listOf("image_path", "image_file_id", "image_name", "image_mime")
    val columns = arrays + strings + "image_size"
    fun snapshot(message: JSONObject, includeManifest: Boolean = true): JSONObject = JSONObject().apply {
        arrays.filter { includeManifest || it != "attachments" }.forEach {
            put(it, message.optJSONArray(it) ?: JSONArray())
        }
        strings.forEach { put(it, JsonProtocol.string(message, it)) }
        put("image_size", message.optLong("image_size", 0L))
    }
    fun fingerprint(message: JSONObject, includeManifest: Boolean = true): String =
        StreamingJsonRequestBody.sha256(snapshot(message, includeManifest))

    /** Remove all legacy references to the same original as a manifest item. */
    fun remove(message: JSONObject, manifestIndex: Int? = null, fileIndex: Int? = null, image: Boolean = false): JSONObject =
        JsonProtocol.objectCopy(message).apply {
            val manifest = optJSONArray("attachments") ?: JSONArray()
            val path = manifestIndex?.let { manifest.optJSONObject(it)?.let { o -> JsonProtocol.string(o, "path") } }
                ?: fileIndex?.let { optJSONArray("file_paths")?.let { a -> JsonProtocol.string(a, it) } }
                ?: if (image) JsonProtocol.string(this, "image_path") else null
            put("attachments", JSONArray().apply {
                for (i in 0 until manifest.length()) {
                    val item = manifest.optJSONObject(i) ?: continue
                    if (i != manifestIndex && (path.isNullOrBlank() || JsonProtocol.string(item, "path") != path)) put(item)
                }
            })
            val paths = optJSONArray("file_paths") ?: JSONArray()
            val count = maxOf(paths.length(), optJSONArray("file_ids")?.length() ?: 0, optJSONArray("file_names")?.length() ?: 0)
            val keep = (0 until count).filter { i -> i != fileIndex && (path.isNullOrBlank() || JsonProtocol.string(paths, i) != path) }
            arrays.filter { it != "attachments" }.forEach { key ->
                val old = optJSONArray(key) ?: JSONArray()
                put(key, JSONArray().apply { keep.forEach { i -> put(old.opt(i) ?: if (key == "file_sizes") 0L else "") } })
            }
            if (image || (!path.isNullOrBlank() && JsonProtocol.string(this, "image_path") == path)) {
                strings.forEach { remove(it) }; put("image_size", 0L)
            }
        }
}
