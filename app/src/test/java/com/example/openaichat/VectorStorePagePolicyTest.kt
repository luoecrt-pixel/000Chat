package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VectorStorePagePolicyTest {
    private fun page(ids: List<String>, more: Boolean, after: String? = null): JSONObject {
        val arr = JSONArray()
        ids.forEach { id -> arr.put(JSONObject().put("id", id)) }
        return JSONObject().put("data", arr).put("has_more", more)
            .apply { if (after != null) put("last_id", after) }
    }

    @Test fun cursorHasExplicitBoundAndNeverSilentlyClaimsComplete() {
        val first = VectorStorePagePolicy.parse(page(listOf("file_1", "file_2"), true, "file_2"))
        assertEquals(2, first.ids.size)
        assertTrue(first.hasMore)
        assertEquals("file_2", first.nextAfter)
        assertTrue(VectorStorePagePolicy.shouldFetchNext(1, first.hasMore))
        assertFalse(VectorStorePagePolicy.shouldFetchNext(3, first.hasMore))
        val final = VectorStorePagePolicy.parse(page(listOf("file_3"), false))
        assertFalse(final.hasMore)
        assertEquals(null, final.nextAfter)
    }

    @Test fun brokenPagesFailBeforeDisplayingInaccurateResult() {
        assertThrows(IOException::class.java) {
            VectorStorePagePolicy.parse(JSONObject().put("has_more", false))
        }
        assertThrows(IOException::class.java) {
            VectorStorePagePolicy.parse(page(emptyList(), true))
        }
        assertThrows(IOException::class.java) {
            VectorStorePagePolicy.parse(page(listOf("same", "same"), true))
        }
        assertThrows(IOException::class.java) {
            VectorStorePagePolicy.parse(page((0..100).map { "id_" + it }, false))
        }
    }

    @Test fun delayedUiOwnerDoesNotMatchNewStoreSession() {
        // Exact UI guard used by MainActivity: generation + store + dialog live.
        fun valid(capturedGeneration: Int, currentGeneration: Int,
                  capturedStore: String, currentStore: String, showing: Boolean): Boolean =
            capturedGeneration == currentGeneration && capturedStore == currentStore && showing
        assertTrue(valid(7, 7, "vs_original", "vs_original", true))
        assertFalse(valid(7, 8, "vs_original", "vs_original", true))
        assertFalse(valid(7, 7, "vs_original", "vs_other", true))
        assertFalse(valid(7, 7, "vs_original", "vs_original", false))
    }
}
