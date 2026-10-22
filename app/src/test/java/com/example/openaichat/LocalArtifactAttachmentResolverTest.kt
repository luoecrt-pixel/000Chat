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
                assertEquals(OpenRouterAttachmentStore.sha256(File(firstPath)), first.textSha256)
                assertEquals(first, OpenRouterLocalAttachment.fromJson(first.toJson()))
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

    @Test fun sameSizeDerivedCorruptionIsRebuiltFromOriginalAndDurablyReused() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("astra_chats.db")
        val source = File(context.filesDir, "derived-integrity.txt").apply { writeText("valid original") }
        val originalBytes = source.readBytes()
        try {
            var repaired: OpenRouterLocalAttachment? = null
            ChatDatabase(context).use { db ->
                db.ensureChat("integrity")
                assertTrue(db.registerLocalArtifact("integrity", "request", JSONObject().put("path", source.path)
                    .put("filename", source.name).put("size_bytes", source.length()).put("mime_type", "text/plain")))
                val first = LocalArtifactAttachmentResolver.resolve(context, db, "integrity", db.listLocalFiles("integrity").single())
                File(first.textPath!!).writeText("x".repeat(File(first.textPath).length().toInt()))
                assertArrayEquals(originalBytes, source.readBytes())
                repaired = LocalArtifactAttachmentResolver.resolve(context, db, "integrity", db.listLocalFiles("integrity").single())
                assertNotEquals(first.textPath, repaired!!.textPath)
                assertEquals("valid original", File(repaired!!.textPath!!).readText())
                assertEquals(OpenRouterAttachmentStore.sha256(File(repaired!!.textPath!!)), repaired!!.textSha256)
            }
            ChatDatabase(context).use { db ->
                val next = LocalArtifactAttachmentResolver.resolve(context, db, "integrity", db.listLocalFiles("integrity").single())
                assertEquals(repaired, next)
                assertTrue(next.textPath in db.listAllLocalFilePaths())
            }
        } finally { source.delete(); context.deleteDatabase("astra_chats.db") }
    }

    @Test fun anUnhashedLegacyDerivativeIsNotTrustedAndWireCorruptionCannotBeReused() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("astra_chats.db")
        val source = File(context.filesDir, "legacy-integrity.txt").apply { writeText("original text") }
        val legacy = File(context.filesDir, "legacy-corrupt.utf8").apply { writeText("untrusted copy") }
        val wire = File(context.filesDir, "wire-copy.bin").apply { writeText("wire bytes") }
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat("legacy")
                val manifest = OpenRouterLocalAttachment("legacy", source.name, "text", source.path, "text/plain", source.length(),
                    OpenRouterAttachmentStore.sha256(source), textPath = legacy.path, textEncoding = "UTF-8")
                assertTrue(db.registerLocalArtifact("legacy", "request", JSONObject().put("path", source.path)
                    .put("filename", source.name).put("size_bytes", source.length()).put("mime_type", "text/plain")
                    .put("attachment_manifest", manifest.toJson())))
                val migrated = LocalArtifactAttachmentResolver.resolve(context, db, "legacy", db.listLocalFiles("legacy").single())
                assertNotEquals(legacy.path, migrated.textPath)
                assertEquals("legacy", migrated.id)
                assertEquals(source.readText(), File(migrated.textPath!!).readText())
                val wireBound = migrated.copy(wirePath = wire.path, wireSha256 = OpenRouterAttachmentStore.sha256(wire))
                assertTrue(db.persistLocalArtifactAttachment("legacy", source.path, source.length(), wireBound.sha256, wireBound))
                wire.writeText("bad! bytes") // Same length; existence and size cannot detect this replacement.
                val repaired = LocalArtifactAttachmentResolver.resolve(context, db, "legacy", db.listLocalFiles("legacy").single())
                assertNull(repaired.wirePath)
                assertEquals("original text", source.readText())
            }
        } finally { source.delete(); legacy.delete(); wire.delete(); context.deleteDatabase("astra_chats.db") }
    }
}
