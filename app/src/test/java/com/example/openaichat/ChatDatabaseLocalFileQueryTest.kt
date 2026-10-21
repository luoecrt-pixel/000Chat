package com.example.openaichat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatDatabaseLocalFileQueryTest {
    private lateinit var context: Context
    private lateinit var db: ChatDatabase
    private val files = mutableListOf<File>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("astra_chats.db")
        db = ChatDatabase(context)
    }

    @After fun cleanup() {
        db.close()
        context.deleteDatabase("astra_chats.db")
        files.forEach(File::delete)
    }

    private fun file(name: String) = File(context.filesDir, name).apply {
        writeText("content for $name")
        files.add(this)
    }

    private fun message() = JSONObject().put("role", "assistant").put("text", "answer")

    private fun seed(chatId: String, messages: List<JSONObject> = emptyList()) {
        db.upsertCurrentChat(chatId, "Title $chatId", null, null, messages)
    }

    private fun artifact(chatId: String, file: File) {
        assertTrue(db.registerLocalArtifact(chatId, "request-$chatId", JSONObject()
            .put("path", file.path).put("filename", file.name).put("size_bytes", file.length())
            .put("mime_type", "text/plain")))
    }

    private fun paths(items: List<JSONObject>) = items.map { it.getString("path") }.toSet()

    @Test fun missingArtifactPathColumnStopsOwnershipCollectionInsteadOfInventingAPath() {
        val original = file("schema-corruption-original.txt")
        seed("a")
        for (populated in listOf(false, true)) {
            db.writableDatabase.execSQL("DROP TABLE local_artifacts")
            db.writableDatabase.execSQL("CREATE TABLE local_artifacts(chat_id TEXT, request_id TEXT, created_at INTEGER, metadata TEXT)")
            if (populated) db.writableDatabase.execSQL("INSERT INTO local_artifacts VALUES(?,?,?,?)",
                arrayOf("a", "request-a", 1, JSONObject().put("path", original.path).toString()))
            assertThrows("Corrupt schema must stop global cleanup; populated=$populated", SQLiteException::class.java) {
                db.listAllLocalFilePaths()
            }
            assertThrows("Corrupt schema must stop scoped cleanup; populated=$populated", SQLiteException::class.java) {
                db.listLocalFilePaths("a")
            }
            assertFalse(db.readableDatabase.inTransaction())
            assertTrue(original.isFile)
        }
    }

    @Test fun scopedListingIncludesOnlyItsArtifactsAnnotationsAndImagesWhileGlobalListingIsPreserved() {
        val artifactA = file("artifact-a.txt")
        val annotationA = file("annotation-a.txt")
        val imageA = file("image-a.png")
        val artifactB = file("artifact-b.txt")
        val imageB = file("image-b.png")
        seed("a", listOf(message()
            .put("annotations", JSONArray()
                .put(JSONObject().put("local_path", annotationA.path).put("filename", annotationA.name))
                .put(JSONObject().put("local_path", artifactA.path).put("filename", artifactA.name)))
            .put("generated_images", JSONArray().put(JSONObject().put("local_path", imageA.path)))))
        // A foreign row with only generated_images detects a missing parenthesized OR.
        seed("b", listOf(message().put("generated_images", JSONArray()
            .put(JSONObject().put("local_path", imageB.path)))))
        artifact("a", artifactA)
        artifact("b", artifactB)

        val scoped = db.listLocalFiles("a")
        assertEquals(setOf(artifactA.path, annotationA.path, imageA.path), paths(scoped))
        assertEquals(3, scoped.size) // Artifact/annotation duplicates remain deduplicated.
        assertTrue(scoped.all { it.getString("chat_id") == "a" && it.getString("chat_title") == "Title a" })
        assertEquals(setOf(artifactB.path, imageB.path), paths(db.listLocalFiles("b")))
        assertTrue(db.listLocalFiles("").isEmpty())
        assertTrue(db.listLocalFiles("missing").isEmpty())
        assertEquals(setOf(artifactA.path, annotationA.path, imageA.path, artifactB.path, imageB.path),
            paths(db.listLocalFiles()))
    }

    @Test fun scopedListingNeverParsesForeignArtifactMetadata() {
        val good = file("good.txt")
        val foreign = file("foreign.txt")
        seed("a")
        seed("b")
        artifact("a", good)
        artifact("b", foreign)
        assertEquals(1, db.writableDatabase.update("local_artifacts", ContentValues().apply {
            put("metadata", "broken foreign metadata")
        }, "chat_id=?", arrayOf("b")))

        assertEquals(setOf(good.path), paths(db.listLocalFiles("a")))
        assertTrue(db.listLocalFiles("missing").isEmpty())
    }

    @Test fun ownershipQueriesPreserveEveryReferenceDespiteNullBlankAndMalformedColumnsAcrossReopen() {
        val refs = listOf("/original.txt", "/image.jpg", "/attachment.pdf", "/derived.utf8",
            "/wire.pdf", "/annotation.txt", "/generated.png", "/artifact.txt", "/archived.txt")
        seed("a", List(4) { message() })
        seed("b", listOf(message().put("file_paths", JSONArray().put("/foreign.txt"))))
        // Position 0 keeps all nullable reference columns NULL.
        db.writableDatabase.update("messages", ContentValues().apply {
            put("file_paths", ""); put("attachments", " \t"); put("annotations", ""); put("generated_images", " ")
        }, "chat_id=? AND position=1", arrayOf("a"))
        db.writableDatabase.update("messages", ContentValues().apply {
            put("file_paths", "broken"); put("attachments", "not JSON"); put("annotations", "{broken")
        }, "chat_id=? AND position=2", arrayOf("a"))
        db.writableDatabase.update("messages", ContentValues().apply {
            put("file_paths", JSONArray().put(refs[0]).put("").toString())
            put("image_path", refs[1])
            put("attachments", JSONArray().put(JSONObject().put("path", refs[2])
                .put("text_path", refs[3]).put("wire_path", refs[4])).toString())
            put("annotations", JSONArray().put(JSONObject().put("local_path", refs[5])).toString())
            put("generated_images", JSONArray().put(JSONObject().put("local_path", refs[6])).toString())
        }, "chat_id=? AND position=3", arrayOf("a"))
        assertTrue(db.registerLocalArtifact("a", "r", JSONObject().put("path", refs[7])))
        db.writableDatabase.insertOrThrow("request_recovery_archive", null, ContentValues().apply {
            put("chat_id", "a"); put("request_id", "archived"); put("created_at", 1L)
            put("recovery_message", JSONObject().put("file_paths", JSONArray().put(refs[8])).toString())
        })
        val checkpointOnly = "/checkpoint-only.txt"
        db.writableDatabase.insertOrThrow("openai_tool_checkpoints", null, ContentValues().apply {
            put("chat_id", "a"); put("request_id", "checkpoint")
            put("request_checkpoint", JSONObject().put("attachments", JSONArray().put(
                JSONObject().put("path", checkpointOnly))).toString())
        })

        repeat(2) {
            assertEquals(refs.toSet() + checkpointOnly, db.listLocalFilePaths("a").toSet())
            assertEquals(setOf("/foreign.txt"), db.listLocalFilePaths("b").toSet())
            assertEquals(refs.toSet() + "/foreign.txt", db.listAllLocalFilePaths().toSet())
            if (it == 0) {
                db.close()
                db = ChatDatabase(context)
            }
        }
    }

    @Test fun scopedArtifactsUseChatIndexAfterRepeatedOpen() {
        seed("a")
        repeat(2) {
            val plans = db.readableDatabase.rawQuery(
                "EXPLAIN QUERY PLAN SELECT metadata FROM local_artifacts WHERE chat_id=? ORDER BY created_at DESC",
                arrayOf("a")).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
            }
            assertTrue(plans.toString(), plans.any { it.contains("idx_local_artifacts_chat_created") })
            if (it == 0) {
                db.close()
                db = ChatDatabase(context)
            }
        }
    }
    @Test fun artifactManifestRetainsAllVersionsAndCacheCannotCrossChatOwnership() {
        val original = file("cache-source.txt"); seed("a"); seed("b"); artifact("a", original)
        val sha = "a".repeat(64)
        val manifest = OpenRouterLocalAttachment("f", original.name, "text", original.path, "text/plain",
            original.length(), sha, textPath = "/derived.utf8", wirePath = "/wire.dat")
        assertFalse(db.persistLocalArtifactAttachment("b", original.path, original.length(), sha, manifest))
        assertFalse(db.persistLocalArtifactAttachment("a", original.path, original.length() + 1, sha, manifest))
        assertTrue(db.persistLocalArtifactAttachment("a", original.path, original.length(), sha, manifest))
        assertTrue(db.listAllLocalFilePaths().containsAll(listOf(original.path, "/derived.utf8", "/wire.dat")))
        assertTrue(db.listLocalFilePaths("a").contains("/derived.utf8"))
        assertFalse(db.listLocalFilePaths("b").contains("/derived.utf8"))
    }

    @Test fun toolResultReplayIsAtomicRequestOwnedAndChecksArgumentIdentity() {
        seed("a"); db.setActiveResponse("a", null, "r", "", "in_progress", 1, "max", "max", false, 0, 0, true)
        fun result(path: String) = JSONObject().put("path", path).put("tool_name", "patch_local_file")
            .put("arguments_sha256", "hash").put("source_path", "/previous.txt").put("patch_diff_path", "/diff.json")
        assertNotNull(db.registerLocalArtifactToolResultIfActive("a", "r", "call", result("/new.txt")))
        assertEquals("/new.txt", db.registerLocalArtifactToolResultIfActive("a", "r", "call", result("/duplicate.txt"))!!.getString("path"))
        assertThrows(IllegalStateException::class.java) {
            db.registerLocalArtifactToolResultIfActive("a", "r", "call", result("/bad.txt").put("arguments_sha256", "different"))
        }
        assertNull(db.registerLocalArtifactToolResultIfActive("a", "other", "call", result("/foreign.txt")))
        assertNull(db.getLocalArtifactToolResult("a", "other", "call"))
        assertTrue(db.listAllLocalFilePaths().containsAll(listOf("/new.txt", "/previous.txt", "/diff.json")))
    }

    @Test fun legacyAnnotationManifestCasPreservesMetadataAndNeverTouchesForeignChat() {
        val source = file("legacy-manifest.txt")
        val item = JSONObject().put("local_path", source.path).put("filename", source.name)
            .put("size_bytes", source.length()).put("custom", "keep")
        seed("a", listOf(message().put("annotations", JSONArray().put(item))))
        seed("b", listOf(message().put("annotations", JSONArray().put(JsonProtocol.objectCopy(item)))))
        val manifest = OpenRouterLocalAttachment("f", source.name, "text", source.path, "text/plain",
            source.length(), "a".repeat(64), textPath = "/legacy-derived.utf8")
        assertTrue(db.persistLocalArtifactAttachment("a", source.path, source.length(), manifest.sha256, manifest))
        assertEquals("keep", db.getChat("a")!!.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("annotations").getJSONObject(0).getString("custom"))
        assertTrue(db.listLocalFiles("a").single().has("attachment_manifest"))
        assertFalse(db.listLocalFiles("b").single().has("attachment_manifest"))
        assertTrue(db.listLocalFilePaths("a").contains("/legacy-derived.utf8"))
        assertFalse(db.listLocalFilePaths("b").contains("/legacy-derived.utf8"))
        assertTrue(db.listAllLocalFilePaths().contains("/legacy-derived.utf8"))
        assertFalse(db.persistLocalArtifactAttachment("missing", source.path, source.length(), manifest.sha256, manifest))
    }

    @Test fun legacyOpenAiSourcesStayChatScopedAcrossReopenWithoutNormalizationWrites() {
        val text = file("legacy-raw.bin").apply { writeText("Точная цитата 😀 из старого вложения", Charsets.UTF_8) }
        val json = file("legacy-json.bin").apply { writeText("{\"value\":42}") }
        val pdf = file("legacy-pdf.bin")
        val badUtf8 = file("legacy-encoding.bin").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x41)) }
        val foreign = file("foreign-source.txt")
        seed("a", listOf(message().put("role", "user")
            .put("file_paths", JSONArray().put(text.path).put(json.path).put(pdf.path).put(badUtf8.path))
            .put("file_names", JSONArray().put("Заметки.md").put("payload").put("report.pdf").put("legacy.txt"))
            .put("file_mimes", JSONArray().put("text/markdown").put("application/json").put("text/plain").put("text/plain"))))
        seed("b", listOf(message().put("role", "user").put("file_paths", JSONArray().put(foreign.path))
            .put("file_names", JSONArray().put("foreign.txt")).put("file_mimes", JSONArray().put("text/plain"))))
        fun diskFiles() = context.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(context.filesDir).path }.toSet()
        val before = diskFiles()
        repeat(2) {
            val sources = LocalEvidenceSources.forChat(context, "a")
            assertEquals(setOf(text.path, json.path, badUtf8.path), sources.map { it.path }.toSet())
            assertEquals("Заметки.md", sources.single { it.path == text.path }.name)
            assertEquals("application/json", sources.single { it.path == json.path }.mime)
            assertTrue(sources.all { it.textPath == null })
            assertTrue(LocalEvidenceSources.forChat(context, "missing").isEmpty())
            assertEquals(setOf(foreign.path), LocalEvidenceSources.forChat(context, "b").map { it.path }.toSet())
            assertEquals(0L, LocalEvidenceVerifier.quotes("Точная цитата", sources.filter { it.path == text.path }).matches.single().offset)
            assertTrue(LocalEvidenceVerifier.quotes("anything", sources.filter { it.path == badUtf8.path }).incomplete)
            assertEquals(before, diskFiles())
            if (it == 0) { db.close(); db = ChatDatabase(context) }
        }
    }

}
