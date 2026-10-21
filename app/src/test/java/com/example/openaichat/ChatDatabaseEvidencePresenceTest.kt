package com.example.openaichat

import android.content.ContentValues
import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatDatabaseEvidencePresenceTest {
    private lateinit var context: Context
    private lateinit var db: ChatDatabase
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("astra_chats.db")
        db = ChatDatabase(context)
    }
    @After fun cleanup() { db.close(); context.deleteDatabase("astra_chats.db") }
    private fun message() = JSONObject().put("role", "user").put("text", "plain")
    private fun seed(id: String, vararg messages: JSONObject) = db.upsertCurrentChat(id, id, null, null, messages.toList())
    private fun manifest(kind: String, name: String, mime: String) = JSONObject()
        .put("kind", kind).put("name", name).put("mime", mime).put("path", "/missing/$name")

    @Test fun plainTextNullEmptyAndMalformedColumnsHaveNoCandidates() {
        seed("plain", message(), message(), message())
        assertFalse(db.hasLocalEvidenceCandidates("plain"))
        db.writableDatabase.update("messages", ContentValues().apply {
            put("attachments", ""); put("file_paths", "[]"); put("annotations", "null")
        }, "chat_id=?", arrayOf("plain"))
        assertFalse(db.hasLocalEvidenceCandidates("plain"))
        db.writableDatabase.update("messages", ContentValues().apply {
            put("attachments", "{broken"); put("file_paths", "not-json"); put("annotations", " ")
        }, "chat_id=?", arrayOf("plain"))
        assertFalse(db.hasLocalEvidenceCandidates("plain"))
        assertFalse(db.hasLocalEvidenceCandidates(""))
        assertFalse(db.hasLocalEvidenceCandidates("missing-chat"))
    }

    @Test fun missingTextDocumentAndTableManifestsAreCandidatesOnlyInTheirChat() {
        seed("other", message())
        for (kind in listOf("text", "document", "table")) {
            val name = if (kind == "document") "document.docx" else "data.txt"
            seed(kind, message().put("attachments", JSONArray().put(manifest(kind, name,
                if (kind == "document") "application/vnd.openxmlformats-officedocument.wordprocessingml.document" else "text/plain"))))
            assertTrue(kind, db.hasLocalEvidenceCandidates(kind))
        }
        assertFalse(db.hasLocalEvidenceCandidates("other"))
        db.close(); db = ChatDatabase(context)
        assertTrue(db.hasLocalEvidenceCandidates("document"))
    }

    @Test fun legacyPathsUseKnownTextFilenameWithoutMimeAndDoNotReadMissingFiles() {
        seed("legacy", message().put("file_paths", JSONArray().put("/does-not-exist/report.txt")))
        assertTrue(db.hasLocalEvidenceCandidates("legacy"))
        seed("named", message().put("file_paths", JSONArray().put("/missing/opaque"))
            .put("file_names", JSONArray().put("script.py")))
        assertTrue(db.hasLocalEvidenceCandidates("named"))
    }

    @Test fun retainedArtifactWithoutVisibleAssistantOrAnnotationRemainsCandidate() {
        seed("stopped", message())
        assertTrue(db.registerLocalArtifact("stopped", "stopped-request", JSONObject()
            .put("path", "/missing/retained.txt").put("filename", "retained.txt").put("mime_type", "text/plain")))
        assertTrue(db.hasLocalEvidenceCandidates("stopped"))
        seed("clean", message())
        assertFalse(db.hasLocalEvidenceCandidates("clean"))
    }

    @Test fun annotationOnlyLocalFileIsCandidateButWebCitationIsNot() {
        seed("file", message().put("annotations", JSONArray().put(JSONObject()
            .put("local_path", "/missing/quoted.csv").put("filename", "quoted.csv"))))
        seed("web", message().put("annotations", JSONArray().put(JSONObject().put("type", "url_citation")
            .put("url", "https://example.test/data.txt").put("title", "data.txt"))))
        assertTrue(db.hasLocalEvidenceCandidates("file"))
        assertFalse(db.hasLocalEvidenceCandidates("web"))
    }

    @Test fun imagesAndPdfAreExcludedEvenWithMisleadingTextMime() {
        seed("binary", message().put("attachments", JSONArray()
            .put(manifest("image", "photo.jpg", "image/jpeg")).put(manifest("pdf", "report.pdf", "application/pdf")))
            .put("file_paths", JSONArray().put("/missing/report.pdf").put("/missing/image.png"))
            .put("file_mimes", JSONArray().put("text/plain").put("text/plain")))
        assertFalse(db.hasLocalEvidenceCandidates("binary"))
    }

    @Test fun malformedForeignArtifactCannotAffectScopedPresence() {
        seed("foreign", message()); seed("clean", message())
        assertTrue(db.registerLocalArtifact("foreign", "request", JSONObject().put("path", "/missing/source.txt").put("filename", "source.txt")))
        db.writableDatabase.update("local_artifacts", ContentValues().apply { put("metadata", "broken") },
            "chat_id=?", arrayOf("foreign"))
        assertFalse(db.hasLocalEvidenceCandidates("clean"))
        assertFalse(db.hasLocalEvidenceCandidates("foreign"))
    }
}
