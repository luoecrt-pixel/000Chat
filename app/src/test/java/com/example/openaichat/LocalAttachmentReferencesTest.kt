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

    @Test
    fun isOrphanOldEnoughRecognizesCanonicalSymlinkAliasesAndFailsClosed() {
        val tempDir = kotlin.io.path.createTempDirectory("orphan-canonical-").toFile()
        try {
            val nested = java.io.File(tempDir, "sub/dir").apply { mkdirs() }
            val file = java.io.File(nested, "note.txt").apply { writeText("content") }
            val nonCanonicalAlias = java.io.File(tempDir, "sub/../sub/dir/note.txt").path
            val retention = 7L * 24 * 60 * 60 * 1000
            val now = 100_000_000L + retention * 2

            // Referenced via non-canonical path, queried via canonical path (and vice versa).
            org.junit.Assert.assertFalse(
                LocalAttachmentReferences.isOrphanOldEnough(
                    path = file.canonicalPath,
                    lastModifiedMs = 1_000L,
                    nowMs = now,
                    referencedPaths = setOf(nonCanonicalAlias),
                    retentionMs = retention
                )
            )
            org.junit.Assert.assertFalse(
                LocalAttachmentReferences.isOrphanOldEnough(
                    path = nonCanonicalAlias,
                    lastModifiedMs = 1_000L,
                    nowMs = now,
                    referencedPaths = setOf(file.canonicalPath),
                    retentionMs = retention
                )
            )
            // Invalid path with NUL character fails closed (never treated as deletable orphan).
            org.junit.Assert.assertFalse(
                LocalAttachmentReferences.isOrphanOldEnough(
                    path = "bad\u0000path",
                    lastModifiedMs = 1_000L,
                    nowMs = now,
                    referencedPaths = emptySet(),
                    retentionMs = retention
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
