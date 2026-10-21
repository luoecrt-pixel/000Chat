package com.example.openaichat

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalArtifactAttachmentResolverTest {
    @Test fun legacyArtifactIsMaterializedOnceAndManifestSurvivesReopen() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("astra_chats.db")
        val file = File(context.filesDir, "resolver-original.txt").apply { writeText("source bytes") }
        try {
            var firstPath = ""
            ChatDatabase(context).use { db ->
                db.ensureChat("resolver")
                assertTrue(db.registerLocalArtifact("resolver", "request", JSONObject().put("path", file.path)
                    .put("filename", file.name).put("size_bytes", file.length()).put("mime_type", "text/plain")))
                val first = LocalArtifactAttachmentResolver.resolve(context, db, "resolver", db.listLocalFiles("resolver").single())
                firstPath = first.textPath!!
                assertTrue(File(firstPath).isFile)
                assertTrue(firstPath in db.listAllLocalFilePaths())
            }
            ChatDatabase(context).use { db ->
                val row = db.listLocalFiles("resolver").single()
                assertTrue(row.has("attachment_manifest"))
                val second = LocalArtifactAttachmentResolver.resolve(context, db, "resolver", row)
                assertEquals(firstPath, second.textPath)
                assertThrows(IOException::class.java) {
                    LocalArtifactAttachmentResolver.resolve(context, db, "foreign", row)
                }
                file.writeText("other bytes!")
                assertThrows(IOException::class.java) {
                    LocalArtifactAttachmentResolver.resolve(context, db, "resolver", row)
                }
                assertEquals(firstPath, db.listLocalFiles("resolver").single().getJSONObject("attachment_manifest").getString("text_path"))
            }
        } finally { file.delete(); context.deleteDatabase("astra_chats.db") }
    }
}
