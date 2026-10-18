package com.example.openaichat

import org.json.JSONObject

/** Immutable identity of one historical source, captured before its HTTP download starts. */
data class HistoricalAttachmentDownloadTarget private constructor(
    val chatId: String,
    val messageId: Long,
    val requestId: String?,
    val fileId: String,
    val index: Int?,
    private val sourceFingerprint: String,
    private val identityFingerprint: String
) {
    val image: Boolean get() = index == null

    fun matches(message: JSONObject, downloadedPath: String? = null): Boolean {
        if (JsonProtocol.nonBlank(message, "request_id") != requestId) return false
        if (downloadedPath == null) return fingerprint(message, index, true) == sourceFingerprint
        return fingerprint(message, index, false) == identityFingerprint &&
            path(message, index) == downloadedPath
    }

    companion object {
        fun file(chatId: String, message: JSONObject, index: Int): HistoricalAttachmentDownloadTarget? =
            if (index < 0) null else capture(chatId, message, index)

        fun image(chatId: String, message: JSONObject): HistoricalAttachmentDownloadTarget? =
            capture(chatId, message, null)

        private fun capture(chatId: String, message: JSONObject, index: Int?): HistoricalAttachmentDownloadTarget? {
            val messageId = message.optLong("_db_message_id", 0L)
            val requestId = JsonProtocol.nonBlank(message, "request_id")
            val fileId = if (index == null) JsonProtocol.string(message, "image_file_id")
                else message.optJSONArray("file_ids")?.let { JsonProtocol.string(it, index) }.orEmpty()
            if (chatId.isBlank() || fileId.isBlank() || fileId == "null" || (messageId <= 0L && requestId == null)) return null
            return HistoricalAttachmentDownloadTarget(chatId, messageId, requestId, fileId, index,
                fingerprint(message, index, true), fingerprint(message, index, false))
        }

        private fun path(message: JSONObject, index: Int?): String = if (index == null) {
            JsonProtocol.string(message, "image_path")
        } else message.optJSONArray("file_paths")?.let { JsonProtocol.string(it, index) }.orEmpty()

        private fun fingerprint(message: JSONObject, index: Int?, includeLocalState: Boolean): String {
            val source = JSONObject()
            if (index == null) {
                for (key in listOf("image_file_id", "image_name")) source.put(key, JsonProtocol.string(message, key))
                if (includeLocalState) {
                    for (key in listOf("image_path", "image_mime")) source.put(key, JsonProtocol.string(message, key))
                    source.put("image_size", message.optLong("image_size", 0L))
                }
            } else {
                for (key in listOf("file_ids", "file_names") + if (includeLocalState) listOf("file_paths", "file_mimes", "file_sizes") else emptyList()) {
                    val values = message.optJSONArray(key)
                    source.put(key, if (key == "file_sizes") values?.optLong(index, 0L) ?: 0L
                        else values?.let { JsonProtocol.string(it, index) }.orEmpty())
                }
            }
            return StreamingJsonRequestBody.sha256(source)
        }
    }
}
