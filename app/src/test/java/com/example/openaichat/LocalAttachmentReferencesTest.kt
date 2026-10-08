package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAttachmentReferencesTest {
    @Test
    fun manifestTracksOriginalTextAndNormalizedWireCopies() {
        val manifest = JSONArray()
            .put(JSONObject().put("path", "/local/photo.jpg").put("wire_path", "/local/wire_photo.jpg"))
            .put(JSONObject().put("path", "/local/note.txt").put("text_path", "/local/txt_note.utf8"))
        assertEquals(
            setOf("/local/photo.jpg", "/local/wire_photo.jpg", "/local/note.txt", "/local/txt_note.utf8"),
            LocalAttachmentReferences.openRouterPaths(manifest)
        )
    }

    @Test
    fun orphanCleanupProtectsWiresAndInactiveChatDraftsAfterSevenDays() {
        val inactiveComposer = JSONObject()
            .put("file_paths", JSONArray().put("/local/attached-pdf"))
            .put("image_path", "/local/image")
            .put("openrouter_attachments", JSONArray().put(
                JSONObject().put("path", "/local/portrait.jpg").put("wire_path", "/local/wire_portrait.jpg")
            ))
        val saved = mapOf<String, Any>(
            "composer_state_chat-inactive" to inactiveComposer.toString(),
            "composer_state_chat-current" to "{}",
            "other_pref" to JSONObject().put("file_paths", JSONArray().put("/local/unrelated")).toString(),
            "composer_state_corrupt" to "{invalid"
        )
        val references = LocalAttachmentReferences.allComposerPaths(saved)
        assertEquals(
            setOf("/local/attached-pdf", "/local/image", "/local/portrait.jpg", "/local/wire_portrait.jpg"),
            references
        )
        val now = 11L * 24 * 60 * 60 * 1000
        val olderThanWeek = 2L * 24 * 60 * 60 * 1000
        assertFalse(LocalAttachmentReferences.isOrphanOldEnough("/local/wire_portrait.jpg", olderThanWeek, now, references))
        assertFalse(LocalAttachmentReferences.isOrphanOldEnough("/local/attached-pdf", olderThanWeek, now, references))
        assertTrue(LocalAttachmentReferences.isOrphanOldEnough("/local/truly-orphaned", olderThanWeek, now, references))
        assertFalse(LocalAttachmentReferences.isOrphanOldEnough("/local/young-orphan", now - 60_000, now, references))
    }

    @Test
    fun malformedDraftsAndMissingManifestDoNotCrashReferenceScan() {
        assertTrue(LocalAttachmentReferences.openRouterPaths(null).isEmpty())
        assertTrue(LocalAttachmentReferences.allComposerPaths(mapOf(
            "composer_state_" to null,
            "composer_state_invalid" to "[not an object]"
        )).isEmpty())
    }
}
