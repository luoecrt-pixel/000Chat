package com.example.openaichat

import android.database.sqlite.SQLiteDatabase
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile

/** Extracts complete text/rows to disk. Binary diagrams and embedded images are not represented as text. */
object StructuredDocumentExtractor {
    private const val MAX_EXPANDED_BYTES = 200L * 1024 * 1024
    private class Budget { var used = 0L }
    private fun stream(zip: ZipFile, path: String, budget: Budget): InputStream {
        val entry = zip.getEntry(path) ?: throw IOException("В документе нет $path")
        return object : FilterInputStream(zip.getInputStream(entry)) {
            private fun checked(n: Int): Int {
                if (n > 0) budget.used += n
                if (budget.used > MAX_EXPANDED_BYTES) throw IOException("Распакованный документ превышает безопасный размер 200 МиБ; данные не обрезались")
                if (Thread.currentThread().isInterrupted) throw IOException("Чтение документа отменено")
                return n
            }
            override fun read() = super.read().let { value -> checked(if (value < 0) 0 else 1); value }
            override fun read(b: ByteArray, off: Int, len: Int) = checked(`in`.read(b, off, len))
        }
    }
    private fun parse(zip: ZipFile, path: String, budget: Budget, event: (XmlPullParser, Int) -> Unit) {
        stream(zip, path, budget).use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null) // Honor the XML declaration/BOM rather than corrupt UTF-16 documents.
            var type = parser.eventType
            while (type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.DOCDECL) throw IOException("DTD в документе не поддерживается")
                event(parser, type)
                type = parser.nextToken()
            }
        }
    }
    private fun attribute(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i).substringAfter(':') == name) return p.getAttributeValue(i)
        return null
    }
    private fun name(p: XmlPullParser) = p.name?.substringAfter(':').orEmpty()
    private fun append(builder: StringBuilder, text: String?) {
        if (builder.length + (text?.length ?: 0) > 1_000_000) throw IOException("Один элемент документа превышает безопасный размер; содержимое не обрезалось")
        if (text != null) builder.append(text)
    }
    fun extract(source: File, target: File, extension: String) {
        val budget = Budget()
        try {
            ZipFile(source).use { zip ->
                if (extension == "xlsx") xlsx(zip, target, budget)
                else text(zip, target, extension, budget)
            }
        } catch (e: Exception) { target.delete(); throw e }
    }
    private fun text(zip: ZipFile, target: File, extension: String, budget: Budget) {
        val entries = when (extension) {
            "docx" -> listOf("word/document.xml") + zip.entries().asSequence().map { it.name }
                .filter { it.matches(Regex("word/(header|footer)[0-9]+\\.xml")) || it in setOf("word/footnotes.xml", "word/endnotes.xml") }.sorted().toList()
            "pptx" -> zip.entries().asSequence().map { it.name }
                .filter { it.matches(Regex("ppt/(slides/slide|notesSlides/notesSlide)[0-9]+\\.xml")) }
                .sortedBy { Regex("[0-9]+").find(it)?.value?.toIntOrNull() ?: 0 }.toList()
            else -> listOf("content.xml")
        }
        target.writer(Charsets.UTF_8).use { out ->
            out.write("[Извлечён текст документа. Встроенные изображения, графики и оформление не представлены текстом.]\n")
            entries.forEach { path ->
                out.write("\n[$path]\n")
                var depth = 0
                parse(zip, path, budget) { p, type ->
                    val n = name(p)
                    if (type == XmlPullParser.START_TAG && n in setOf("t", "p", "h")) depth++
                    if (type in setOf(XmlPullParser.TEXT, XmlPullParser.CDSECT) && depth > 0) out.write(p.text.orEmpty())
                    if (type == XmlPullParser.ENTITY_REF && depth > 0) {
                        val value = p.text ?: throw IOException("Неизвестная XML-сущность")
                        out.write(value)
                    }
                    if (type == XmlPullParser.END_TAG && n in setOf("t", "p", "h")) {
                        depth = (depth - 1).coerceAtLeast(0)
                        if (n != "t") out.write("\n")
                    }
                    if (type == XmlPullParser.END_TAG && n in setOf("tc", "table-cell")) out.write("\t")
                    if (type == XmlPullParser.END_TAG && n in setOf("tr", "table-row")) out.write("\n")
                }
            }
        }
    }
    private fun xlsx(zip: ZipFile, target: File, budget: Budget) {
        val dateFormats = mutableSetOf<Int>().apply { addAll(14..22); addAll(27..36); addAll(45..47); addAll(50..58) }
        val cellFormats = mutableListOf<Int>()
        var inCellFormats = false
        if (zip.getEntry("xl/styles.xml") != null) parse(zip, "xl/styles.xml", budget) { p, type ->
            val n = name(p)
            if (type == XmlPullParser.START_TAG && n == "numFmt") {
                val code = attribute(p, "formatCode").orEmpty()
                    .replace(Regex("\"[^\"]*\""), "").replace(Regex("\\\\."), "")
                    .replace(Regex("\\[(?![hms]+\\])[^\\]]*\\]", RegexOption.IGNORE_CASE), "")
                if (Regex("[ymdhs]", RegexOption.IGNORE_CASE).containsMatchIn(code))
                    attribute(p, "numFmtId")?.toIntOrNull()?.let { dateFormats.add(it) }
            }
            if (type == XmlPullParser.START_TAG && n == "cellXfs") inCellFormats = true
            if (type == XmlPullParser.END_TAG && n == "cellXfs") inCellFormats = false
            if (type == XmlPullParser.START_TAG && n == "xf" && inCellFormats)
                cellFormats.add(attribute(p, "numFmtId")?.toIntOrNull() ?: 0)
        }
        val indexFile = File(target.parentFile, target.name + ".strings.db")
        val index = SQLiteDatabase.openOrCreateDatabase(indexFile, null)
        try {
            index.execSQL("CREATE TABLE strings (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            if (zip.getEntry("xl/sharedStrings.xml") != null) {
                var item: StringBuilder? = null
                var inText = false
                var phonetic = 0
                var id = 0
                index.beginTransaction()
                try {
                    parse(zip, "xl/sharedStrings.xml", budget) { p, type ->
                        when {
                            type == XmlPullParser.START_TAG && name(p) == "rPh" -> phonetic++
                            type == XmlPullParser.END_TAG && name(p) == "rPh" -> phonetic--
                            type == XmlPullParser.START_TAG && name(p) == "si" -> item = StringBuilder()
                            type == XmlPullParser.START_TAG && name(p) == "t" -> inText = true
                            type in setOf(XmlPullParser.TEXT, XmlPullParser.ENTITY_REF, XmlPullParser.CDSECT) && inText && phonetic == 0 -> item?.let { append(it, p.text) }
                            type == XmlPullParser.END_TAG && name(p) == "t" -> inText = false
                            type == XmlPullParser.END_TAG && name(p) == "si" -> {
                                val value = item ?: throw IOException("Повреждённая общая строка XLSX")
                                index.execSQL("INSERT INTO strings VALUES (?, ?)", arrayOf(id++, value.toString())); item = null
                            }
                        }
                    }
                    index.setTransactionSuccessful()
                } finally { index.endTransaction() }
            }
            val relationships = linkedMapOf<String, String>()
            parse(zip, "xl/_rels/workbook.xml.rels", budget) { p, type ->
                if (type == XmlPullParser.START_TAG && name(p) == "Relationship") {
                    val id = attribute(p, "Id") ?: return@parse
                    val raw = attribute(p, "Target") ?: return@parse
                    if (attribute(p, "TargetMode") == "External") return@parse
                    val uri = URI("xl/").resolve(raw).normalize()
                    if (uri.isAbsolute) return@parse
                    relationships[id] = uri.path.removePrefix("/")
                }
            }
            val sheets = linkedMapOf<String, String>()
            parse(zip, "xl/workbook.xml", budget) { p, type ->
                if (type == XmlPullParser.START_TAG && name(p) == "sheet") {
                    val sheet = attribute(p, "name") ?: return@parse
                    val id = attribute(p, "id") ?: return@parse
                    val path = relationships[id] ?: throw IOException("Не найдена связь листа $sheet")
                    sheets[sheet] = path
                }
            }
            if (sheets.isEmpty()) throw IOException("В XLSX не найдены листы")
            target.writer(Charsets.UTF_8).use { out ->
                sheets.forEach { (sheet, path) ->
                    out.write(JSONObject().put("sheet", sheet).put("record", "sheet").toString()); out.write("\n")
                    var row = 0L
                    var cells = JSONArray()
                    var cell: JSONObject? = null
                    var value = StringBuilder()
                    var formula = StringBuilder()
                    var textMode: String? = null
                    var phonetic = 0
                    parse(zip, path, budget) { p, type ->
                        val n = name(p)
                        when {
                            type == XmlPullParser.START_TAG && n == "rPh" -> phonetic++
                            type == XmlPullParser.END_TAG && n == "rPh" -> phonetic--
                            type == XmlPullParser.START_TAG && n == "row" -> { row = attribute(p, "r")?.toLongOrNull() ?: row + 1; cells = JSONArray() }
                            type == XmlPullParser.START_TAG && n == "c" -> {
                                val format = cellFormats.getOrNull(attribute(p, "s")?.toIntOrNull() ?: 0) ?: 0
                                cell = JSONObject().put("ref", attribute(p, "r").orEmpty()).put("type", attribute(p, "t") ?: "n")
                                    .put("num_format_id", format).put("date_formatted", format in dateFormats && (attribute(p, "t") ?: "n") == "n")
                                value = StringBuilder(); formula = StringBuilder()
                            }
                            type == XmlPullParser.START_TAG && n in setOf("v", "t", "f") -> {
                                textMode = n
                                if (n == "f") {
                                    cell?.put("formula", "")
                                    attribute(p, "t")?.let { cell?.put("formula_t", it) }
                                    attribute(p, "si")?.let { cell?.put("formula_si", it) }
                                    attribute(p, "ref")?.let { cell?.put("formula_ref", it) }
                                }
                            }
                            type in setOf(XmlPullParser.TEXT, XmlPullParser.ENTITY_REF, XmlPullParser.CDSECT) && textMode != null && phonetic == 0 -> append(if (textMode == "f") formula else value, p.text)
                            type == XmlPullParser.END_TAG && n in setOf("v", "t", "f") -> textMode = null
                            type == XmlPullParser.END_TAG && n == "c" -> {
                                val c = cell ?: throw IOException("Повреждённая ячейка XLSX")
                                val raw = value.toString()
                                val displayed = if (c.optString("type") == "s") {
                                    val id = raw.toIntOrNull() ?: throw IOException("Неверный индекс строки XLSX")
                                    SqliteLargeTextQuery.query(index, "strings", arrayOf("value"), "id=?", arrayOf(id.toString()), null, null, null, "1").use {
                                        if (!it.moveToFirst()) throw IOException("Не найдена общая строка XLSX")
                                        it.getString(0)
                                    }
                                } else raw
                                c.put("value", displayed)
                                if (formula.isNotEmpty()) c.put("formula", formula.toString())
                                cells.put(c); cell = null
                            }
                            type == XmlPullParser.END_TAG && n == "row" -> {
                                out.write(JSONObject().put("sheet", sheet).put("row", row).put("cells", cells).toString()); out.write("\n")
                            }
                        }
                    }
                }
            }
        } finally {
            index.close()
            listOf("", "-journal", "-wal", "-shm").forEach { File(indexFile.path + it).delete() }
        }
    }
}
