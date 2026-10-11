package com.example.openaichat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuditFollowupRegressionTest {
    @Test fun notificationQueueIsCommittedWithAnswerAndSurvivesReopenBeforePost() {
        val context: Context = RuntimeEnvironment.getApplication()
        val id = "atomic-notification-test"
        ChatDatabase(context).use { db ->
            db.ensureChat(id, "Answer")
            db.setActiveResponse(id, null, "request-atomic", "", "in_progress", 1, "max", "max", false, 0, 0, true)
            val assistant = JSONObject().put("role", "assistant").put("request_id", "request-atomic").put("status", "completed").put("text", "Full answer")
            assertTrue(db.finalizeCurrentChatIfActiveRequest(id, "Answer", null, null, listOf(assistant), "request-atomic"))
            assertFalse(db.finalizeCurrentChatIfActiveRequest(id, "Answer", null, null, listOf(assistant), "request-atomic"))
        }
        ChatDatabase(context).use { db ->
            try {
                val notification = db.pendingAnswerNotifications().single { it.optString("chat_id") == id }
                assertTrue(notification.getBoolean("completed")); assertEquals("request-atomic", notification.getString("request_id"))
                db.acknowledgeAnswerNotification(id, "request-atomic")
                assertTrue(db.pendingAnswerNotifications().none { it.optString("chat_id") == id })
            } finally { db.deleteChat(id) }
        }
    }
    @Test fun diskPagesSurviveReopenWithoutDownloadingPrefixesAndRejectChangedVersion() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "github-pages-test").apply { deleteRecursively(); mkdirs() }
        val text = "科学😀\n".repeat(25000)
        var downloads = 0
        var conditions = 0
        val fetch: (String?, (InputStreamReader, String) -> Unit) -> String = { tag, save ->
            if (tag == null) { downloads++; InputStreamReader(text.byteInputStream(), Charsets.UTF_8).use { save(it, "v1") } }
            else { assertEquals("v1", tag); conditions++ }
            "v1"
        }
        try {
            val restored = StringBuilder(); var offset = 0L
            do {
                val page = GitHubTextPageCache(dir).read("repo/ref/account-a", offset, 40000,
                    "v1".takeIf { offset > 0 }, { true }, fetch)
                restored.append(page.getString("content")); offset = page.getLong("next_offset")
            } while (!page.getBoolean("eof"))
            assertEquals(text, restored.toString()); assertEquals(1, downloads); assertTrue(conditions > 0)
            assertThrows(java.io.IOException::class.java) {
                GitHubTextPageCache(dir).read("repo/ref/account-a", 20, 20, "v1", { true }) { _, save ->
                    InputStreamReader("changed".byteInputStream(), Charsets.UTF_8).use { save(it, "v2") }; "v2"
                }
            }
            assertEquals(text.take(20), GitHubTextPageCache(dir).read("repo/ref/account-a", 0, 20, "v1", { true }, fetch).getString("content"))
            GitHubTextPageCache(dir).read("repo/ref/account-b", 0, 20, null, { true }, fetch)
            assertEquals(2, downloads)
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidUtf8LeavesNoPartialOrInstalledSnapshot() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "github-bad-test").apply { deleteRecursively(); mkdirs() }
        val cache = GitHubTextPageCache(dir)
        try {
            assertThrows(java.io.IOException::class.java) {
                cache.read("repo/ref", 0, 20, null, { true }) { _, save ->
                    InputStreamReader(byteArrayOf(0xc3.toByte(), 0x28).inputStream(), cache.decoder()).use { save(it, "v1") }; "v1"
                }
            }
            assertTrue(dir.listFiles().orEmpty().none { it.extension in setOf("partial", "utf16") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun largeSharedXlsxValueAndFileAnnotationsRemainComplete() {
        val context: Context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "large-metadata-test").apply { deleteRecursively(); mkdirs() }
        val text = "科".repeat(850000)
        val xlsx = File(dir, "input.xlsx")
        try {
            ZipOutputStream(xlsx.outputStream()).use { zip ->
                fun entry(path: String, content: String) { zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                entry("xl/sharedStrings.xml", "<sst><si><t>$text</t></si></sst>")
                entry("xl/workbook.xml", "<workbook><sheets><sheet name=\"Data\" id=\"r1\"/></sheets></workbook>")
                entry("xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
                entry("xl/worksheets/sheet1.xml", "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row></sheetData></worksheet>")
            }
            val extracted = File(dir, "rows.jsonl")
            StructuredDocumentExtractor.extract(xlsx, extracted, "xlsx")
            assertEquals(text, JSONObject(extracted.readLines()[1]).getJSONArray("cells").getJSONObject(0).getString("value"))
            ChatDatabase(context).use { db ->
                val id = "large-file-metadata-test"
                db.ensureChat(id, "Files")
                try {
                    val local = File(dir, "created.txt").apply { writeText("data") }
                    assertTrue(db.registerLocalArtifact(id, "r", JSONObject().put("path", local.path).put("description", text)))
                    val attachment = JSONObject().put("local_path", xlsx.path).put("filename", "input.xlsx").put("note", text)
                    db.upsertCurrentChat(id, "Files", null, null, listOf(JSONObject().put("role", "assistant").put("text", "ok").put("annotations", JSONArray().put(attachment))))
                    val files = db.listLocalFiles()
                    assertEquals(text, files.single { it.optString("path") == local.path }.getString("description"))
                    assertTrue(files.any { it.optString("path") == xlsx.path })
                } finally { db.deleteChat(id) }
            }
        } finally { dir.deleteRecursively() }
    }
}
