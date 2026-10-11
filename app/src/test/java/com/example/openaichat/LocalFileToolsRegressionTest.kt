package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalFileToolsRegressionTest {
    private fun stats(a: OpenRouterLocalAttachment, args: JSONObject) = JSONObject(LocalFileTools(listOf(a))
        .execute("file_table_stats", args.put("file_id", a.id)))

    @Test fun fileListIncludesRegisteredArtifactsAndCorruptUtf8FailsClearly() {
        val folder = Files.createTempDirectory("file-list-").toFile()
        try {
            val file = File(folder, "created.txt").apply { writeText("hello") }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            val tools = LocalFileTools(emptyList()); tools.register(a)
            val listed = JSONObject(tools.execute("file_list", JSONObject()))
            assertTrue(listed.getBoolean("ok")); assertTrue(listed.getBoolean("eof"))
            assertEquals(a.id, listed.getJSONArray("files").getJSONObject(0).getString("file_id"))
            File(a.textPath!!).writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertFalse(JSONObject(tools.execute("file_read_range", JSONObject().put("file_id", a.id))).getBoolean("ok"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun oversizedOriginalPhotoFailsBeforeNetworkEncoding() {
        val folder = Files.createTempDirectory("claude-photo-").toFile()
        try {
            val file = File(folder, "large.jpg")
            java.io.RandomAccessFile(file, "rw").use { it.setLength(8_000_000L) }
            val a = OpenRouterLocalAttachment("large", file.name, "image", file.path, "image/jpeg", file.length(), "")
            val failure = assertThrows(java.io.IOException::class.java) { OpenRouterAttachmentPreflight.validate("anthropic/claude-sonnet-5.5", listOf(a)) }
            assertTrue(failure.message!!.contains("Base64")); assertTrue(file.exists())
        } finally { folder.deleteRecursively() }
    }

    @Test fun changedSourceDoesNotReuseItsOldDescriptorOrThinkingPrefix() {
        val folder = Files.createTempDirectory("file-integrity-").toFile()
        try {
            val file = File(folder, "data.txt").apply { writeText("original") }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            file.writeText("changed")
            assertThrows(java.io.IOException::class.java) { OpenRouterAttachmentPreflight.verifySources(listOf(a)) }
        } finally { folder.deleteRecursively() }
    }

    @Test fun csvProcessesAllRowsAndQuotedNewlines() {
        val folder = Files.createTempDirectory("csv-all-").toFile()
        try {
            val file = File(folder, "data.csv").apply { writeText("value,note\n" + "1,ok\n".repeat(1500) + "10000,late\n") }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            val result = stats(a, JSONObject().put("column", 1).put("start_row", 2))
            assertTrue(result.getBoolean("ok")); assertEquals(1501L, result.getLong("numeric_count"))
            assertEquals("11500", result.getString("sum_exact")); assertTrue(result.getBoolean("complete_requested_range"))
            file.writeText("value,note\n1,\"two\nlines\"\n2,\"escaped \"\"quote\"\"\"\n")
            val quoted = OpenRouterAttachmentStore.classifyLocalFile(file)
            assertEquals("3", stats(quoted, JSONObject().put("column", 1).put("start_row", 2)).getString("sum_exact"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun malformedCsvAndUnknownDevicePathFailClearly() {
        val folder = Files.createTempDirectory("csv-fail-").toFile()
        try {
            val file = File(folder, "broken.csv").apply { writeText("\"1\"2\n") }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            assertFalse(stats(a, JSONObject().put("column", 1)).getBoolean("ok"))
            val unauthorized = JSONObject(LocalFileTools(listOf(a)).execute("file_read_range",
                JSONObject().put("file_id", "/data/data/private-secret").put("limit", 100)))
            assertFalse(unauthorized.getBoolean("ok"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun xlsxRetainsLateRowsEmptySheetsAndFormulaWithoutInventingItsValue() {
        val folder = Files.createTempDirectory("xlsx-all-").toFile()
        try {
            val file = File(folder, "all.xlsx")
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                entry("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="r1" Target="worksheets/sheet1.xml"/><Relationship Id="r2" Target="worksheets/sheet2.xml"/></Relationships>""")
                entry("xl/workbook.xml", """<workbook xmlns:r="urn:relationships"><sheets><sheet name="Data" r:id="r1"/><sheet name="Empty" r:id="r2"/></sheets></workbook>""")
                val rows = (1..1500).joinToString("") { "<row r=\"$it\"><c r=\"A$it\"><v>1</v></c></row>" }
                entry("xl/worksheets/sheet1.xml", "<worksheet><sheetData>$rows<row r=\"1501\"><c r=\"A1501\"><f>1+9999</f><v>10000</v></c><c r=\"B1501\"><f>1+1</f></c></row></sheetData></worksheet>")
                entry("xl/worksheets/sheet2.xml", "<worksheet><sheetData/></worksheet>")
            }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            val result = stats(a, JSONObject().put("column", 1).put("sheet", "Data"))
            assertTrue(result.getBoolean("ok")); assertEquals("11500", result.getString("sum_exact"))
            assertEquals(1501L, result.getLong("numeric_count"))
            assertEquals(0L, stats(a, JSONObject().put("column", 1).put("sheet", "Empty")).getLong("rows_scanned"))
            assertFalse(stats(a, JSONObject().put("column", 1).put("sheet", "Unknown")).getBoolean("ok"))
            assertFalse(stats(a, JSONObject().put("column", 2).put("sheet", "Data").put("start_row", 1501)).getBoolean("ok"))
            val text = File(a.textPath!!).readText()
            assertTrue(text.contains("A1501")); assertTrue(text.contains("1+9999"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun sharedFormulaWithoutCachedValueIsNotSilentlyCountedAsAnEmptyCell() {
        val folder = Files.createTempDirectory("xlsx-shared-formula-").toFile()
        try {
            val file = File(folder, "shared.xlsx")
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, xml: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(xml.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="r1" Target="worksheets/sheet1.xml"/></Relationships>""")
                entry("xl/workbook.xml", """<workbook xmlns:r="urn:relationships"><sheets><sheet name="Shared" r:id="r1"/></sheets></workbook>""")
                entry("xl/worksheets/sheet1.xml", """<worksheet><sheetData>
                    <row r="1"><c r="A1"><f t="shared" si="0" ref="A1:A3">ROW()</f><v>1</v></c></row>
                    <row r="2"><c r="A2"><f t="shared" si="0"/></c></row>
                    <row r="3"><c r="A3"><f t="shared" si="0"/><v>3</v></c></row>
                    </sheetData></worksheet>""".trimIndent())
            }
            val a = OpenRouterAttachmentStore.classifyLocalFile(file)
            val strict = stats(a, JSONObject().put("column", 1).put("sheet", "Shared"))
            assertFalse(strict.getBoolean("ok"))
            val nonStrict = stats(a, JSONObject().put("column", 1).put("sheet", "Shared").put("strict", false))
            assertTrue(nonStrict.getBoolean("ok"))
            assertEquals(1L, nonStrict.getLong("invalid_cells"))
            assertEquals(0L, nonStrict.getLong("missing_cells"))
            assertEquals("4", nonStrict.getString("sum_exact"))
            val lines = File(a.textPath!!).readLines().map { JSONObject(it) }
            val follower = lines.first { it.optLong("row") == 2L }.getJSONArray("cells").getJSONObject(0)
            assertTrue(follower.has("formula"))
            assertEquals("", follower.getString("formula"))
            assertEquals("shared", follower.getString("formula_t"))
            assertEquals("0", follower.getString("formula_si"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun blankHistoricalPathDoesNotSilentlyDropAnUploadedFile() {
        val message = JSONObject().put("role", "user").put("text", "Use my dataset")
            .put("file_ids", JSONArray().put("file_123")).put("file_paths", JSONArray().put(""))
        assertThrows(java.io.IOException::class.java) { OpenAiLocalContextEncoder.encode(listOf(message)) }
        assertThrows(java.io.IOException::class.java) { AttachmentHistoryPolicy.validate(message) }
    }

    @Test fun wholeFileContainerInputDoesNotInlineASpreadsheetPreview() {
        val folder = Files.createTempDirectory("full-input-").toFile()
        try {
            val file = File(folder, "all.csv").apply { writeText("x\n" + "1\n".repeat(1501)) }
            val m = JSONObject().put("role", "user").put("text", "Sum all rows")
                .put("provider", "openai").put("file_ids", JSONArray().put("file_native"))
                .put("file_paths", JSONArray().put(file.path)).put("file_names", JSONArray().put(file.name))
            val ids = OpenAiFileAnalysis.prepare(listOf(m), { true }) { source, _, _ ->
                assertEquals(file.readText(), source.readText()); "file_current_account"
            }
            val wire = OpenAiLocalContextEncoder.encode(listOf(m), ids)
            assertTrue(wire.toString().contains("file_current_account")); assertTrue(wire.toString().contains("Complete file"))
            assertFalse(wire.toString().contains("file_native"))
            assertFalse(wire.toString().contains("input_file"))
        } finally { folder.deleteRecursively() }
    }
}
