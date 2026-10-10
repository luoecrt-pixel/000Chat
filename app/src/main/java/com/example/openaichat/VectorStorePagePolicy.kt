package com.example.openaichat

import org.json.JSONObject
import java.io.IOException

data class VectorStorePage(
    val ids: List<String>,
    val hasMore: Boolean,
    val nextAfter: String?
)

/** Never assume the first 100 files is the entire remote store. */
object VectorStorePagePolicy {
    const val MAX_PAGES = 3
    const val PAGE_SIZE = 100
    const val MAX_FILENAME_LOOKUPS = 10

    fun parse(json: JSONObject): VectorStorePage {
        val data = json.optJSONArray("data")
            ?: throw IOException("Vector Store response has no data array")
        if (data.length() > PAGE_SIZE) throw IOException("Vector Store returned too many items")
        val ids = (0 until data.length()).map { index ->
            data.optJSONObject(index)?.optString("id").orEmpty()
                .takeIf { it.isNotBlank() } ?: throw IOException("Vector Store file without ID")
        }
        if (ids.distinct().size != ids.size) throw IOException("Vector Store duplicate file IDs")
        val more = json.optBoolean("has_more", false)
        val after = if (more) json.optString("last_id").ifBlank { ids.lastOrNull().orEmpty() } else null
        if (more && after.isNullOrBlank()) throw IOException("Vector Store reports more files but no cursor")
        return VectorStorePage(ids, more, after)
    }

    fun shouldFetchNext(pageIndex: Int, hasMore: Boolean) = hasMore && pageIndex < MAX_PAGES
}

/** Delayed network callbacks may only alter their original visible dialog/store. */
object VectorStoreUiOwnershipPolicy {
    fun mayCommit(
        capturedGeneration: Int,
        currentGeneration: Int,
        capturedStore: String?,
        selectedStore: String?,
        dialogVisible: Boolean
    ): Boolean = capturedGeneration == currentGeneration &&
        capturedStore != null && capturedStore == selectedStore && dialogVisible
}
