package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.PushbackReader
import java.math.BigDecimal
import java.math.MathContext

/** Request-scoped allow-list. A model cannot use these tools to read arbitrary device paths. */
class LocalFileTools(attachments: List<OpenRouterLocalAttachment>, private val active: () -> Boolean = { true }) {
    private val files = attachments.associateBy { it.id }.toMutableMap()
    private val legacyAliases = mutableMapOf<String, String>()
    private val ambiguousLegacyAliases = mutableSetOf<String>()
    init { attachments.forEach(::registerLegacyAlias) }
    private val pages = LocalTextPageIndex()
    companion object {
        const val SEARCH_LEFT_CONTEXT = 100
        const val SEARCH_RIGHT_CONTEXT = 180
        val names = setOf("file_list", "file_info", "file_read_range", "file_search", "file_table_stats")
        fun schemas(): JSONArray = JSONArray().apply {
            val catalog = OpenRouterAppToolCatalog.tools()
            for (i in 0 until catalog.length()) {
                val schema = catalog.getJSONObject(i)
                if (schema.getJSONObject("function").optString("name") in names) put(schema)
            }
        }
        fun descriptor(a: OpenRouterLocalAttachment): JSONObject = JSONObject()
            .put("file_id", a.id).put("filename", a.name).put("kind", a.kind).put("mime", a.mime).put("size_bytes", a.size)
            .put("source_encoding", a.textEncoding ?: JSONObject.NULL)
            .put("encoding_guessed", a.encodingGuessed)
            .put("reading", "Use file_info, file_search, file_read_range, or file_table_stats. Every part remains readable; choose partial or full processing for the task.")
    }
    private fun registerLegacyAlias(a: OpenRouterLocalAttachment) {
        val fileName = File(a.path).name
        if (fileName.isBlank()) return
        val alias = "local_$fileName"
        if (alias in ambiguousLegacyAliases) return
        val existing = legacyAliases[alias]
        if (existing == null || existing == a.id) legacyAliases[alias] = a.id
        else { legacyAliases.remove(alias); ambiguousLegacyAliases.add(alias) }
    }
    fun register(a: OpenRouterLocalAttachment) {
        files[a.id] = a
        registerLegacyAlias(a)
    }
    private fun file(args: JSONObject): OpenRouterLocalAttachment {
        if (!active()) throw IOException("Запрос остановлен")
        val id = args.getString("file_id")
        if (id !in files && id in ambiguousLegacyAliases)
            throw IOException("Неоднозначный идентификатор файла: $id. Укажите точный file_id.")
        val resolved = files[id] ?: legacyAliases[id]?.let { files[it] }
        return resolved?.takeIf {
            File(it.textPath ?: it.path).isFile ||
                (it.kind == "text" && (it.textEncoding == null || it.textEncoding.equals("UTF-8", ignoreCase = true)) && File(it.path).isFile)
        }
            ?: throw IOException("Файл недоступен в этом запросе: $id")
    }
    fun execute(name: String, args: JSONObject): String = try {
        if (!active()) throw IOException("Запрос остановлен")
        if (name == "file_list") list(args).put("ok", true).toString() else {
        val a = file(args)
        when (name) {
            "file_info" -> descriptor(a).put("text_available", a.kind in setOf("text", "document", "table"))
                .put("extraction", if (TextFileFormatPolicy.extension(a.name) == "xlsx") "All stored worksheet rows, cells, types, and cached formula values as JSON Lines. Formulas are not recalculated." else "Text; embedded images and formatting of office files are not represented.")
            "file_read_range" -> pages.range(textFile(a), args.optLong("offset", 0L), args.optInt("limit", 24000), active).put("file_id", a.id)
            "file_search" -> search(a, args)
            "file_table_stats" -> statistics(a, args)
            else -> throw IOException("Неизвестный файловый инструмент")
        }.put("ok", true).toString()
        }
    } catch (e: Exception) {
        if (!active() && e is IOException) throw e
        JSONObject().put("ok", false).put("error", e.message).toString()
    }
    private fun list(args: JSONObject): JSONObject {
        val offset = args.optInt("offset", 0); val limit = args.optInt("limit", 30)
        require(offset >= 0 && limit in 1..50)
        val available = files.values.toList()
        val end = minOf(available.size.toLong(), offset.toLong() + limit).toInt()
        val rows = JSONArray()
        if (offset < end) for (i in offset until end) rows.put(descriptor(available[i]))
        return JSONObject().put("files", rows).put("next_offset", end).put("eof", end == available.size)
    }
    private fun textFile(a: OpenRouterLocalAttachment): File {
        if (a.kind !in setOf("text", "document", "table")) throw IOException("Этот формат не является извлечённым текстом. Изображения/PDF передаются визуальной модели напрямую.")
        val candidate = File(a.textPath ?: a.path)
        if (candidate.isFile) return candidate
        val original = File(a.path)
        if (original.isFile && a.kind == "text" && (a.textEncoding == null || a.textEncoding.equals("UTF-8", ignoreCase = true))) {
            return original
        }
        return candidate
    }
    private fun strictReader(file: File): java.io.Reader {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return java.io.InputStreamReader(file.inputStream(), decoder)
    }
    private fun retainTailWithSurrogateGuard(source: String, maxChars: Int): String {
        if (maxChars <= 0 || source.isEmpty()) return ""
        var start = (source.length - maxChars).coerceAtLeast(0)
        if (start > 0 && start < source.length &&
            Character.isLowSurrogate(source[start]) &&
            Character.isHighSurrogate(source[start - 1])
        ) {
            start--
        }
        return source.substring(start)
    }
    private fun search(a: OpenRouterLocalAttachment, args: JSONObject): JSONObject {
        val query = args.getString("query"); require(query.length in 1..512) { "query must contain 1..512 characters" }
        val start = args.optLong("offset", 0L); require(start >= 0)
        val maxResults = if (args.has("limit")) args.getInt("limit") else args.optInt("max_results", 20)
        require(maxResults in 1..50)
        val matches = JSONArray()
        var position = 0L
        var carry = ""
        var scannedTo = start
        var eof = true
        // Preserve both the promised left snippet context (100 chars) and the maximum
        // cross-buffer prefix of the query (query.length - 1) so boundary matches
        // never lose their left context or split a surrogate pair.
        val carryWindow = SEARCH_LEFT_CONTEXT + query.length - 1
        strictReader(textFile(a)).use { reader ->
            val buf = CharArray(8192)
            while (position < start) {
                if (!active()) throw IOException("Поиск отменён")
                val n = reader.read(buf, 0, minOf(buf.size.toLong(), start - position).toInt())
                if (n < 0) return JSONObject().put("matches", matches).put("next_offset", position)
                    .put("eof", true).put("all_matches_returned", true).put("file_id", a.id)
                carry = retainTailWithSurrogateGuard(carry + String(buf, 0, n), SEARCH_LEFT_CONTEXT)
                position += n
            }
            while (true) {
                if (!active()) throw IOException("Поиск отменён")
                val n = reader.read(buf); if (n < 0) break
                val chunk = carry + String(buf, 0, n)
                val base = position - carry.length
                var at = chunk.indexOf(query, ignoreCase = true)
                while (at >= 0) {
                    val offset = base + at
                    if (offset >= start && offset + query.length > position) {
                        var lo = (at - SEARCH_LEFT_CONTEXT).coerceAtLeast(0)
                        var hi = (at + query.length + SEARCH_RIGHT_CONTEXT).coerceAtMost(chunk.length)
                        if (lo < chunk.length && Character.isLowSurrogate(chunk[lo])) lo++
                        if (hi > lo && Character.isHighSurrogate(chunk[hi - 1])) hi--
                        matches.put(JSONObject().put("offset", offset).put("snippet", chunk.substring(lo, hi)))
                        if (matches.length() >= maxResults) {
                            scannedTo = offset + 1; eof = false; break
                        }
                    }
                    at = chunk.indexOf(query, at + 1, ignoreCase = true)
                }
                position += n
                if (!eof) break
                carry = retainTailWithSurrogateGuard(chunk, carryWindow)
                scannedTo = position
            }
        }
        return JSONObject().put("file_id", a.id).put("matches", matches).put("next_offset", scannedTo)
            .put("eof", eof).put("all_matches_returned", eof)
    }
    private fun statistics(a: OpenRouterLocalAttachment, args: JSONObject): JSONObject {
        val column = args.getInt("column"); require(column >= 1)
        val first = args.optLong("start_row", 1L); require(first >= 1)
        val last = args.optLong("end_row", Long.MAX_VALUE); require(last >= first)
        var sheet = JsonProtocol.nonBlank(args, "sheet")
        var sheetFound = false
        var count = 0L; var scanned = 0L; var missing = 0L; var invalid = 0L
        var sum = BigDecimal.ZERO; var min: BigDecimal? = null; var max: BigDecimal? = null
        val examples = JSONArray()
        fun accept(row: Long, raw: String?, uncomputed: Boolean = false, incompatible: String? = null) {
            if (!active()) throw IOException("Анализ отменён")
            if (row < first || row > last) return
            scanned++
            if (raw.isNullOrBlank() && !uncomputed && incompatible == null) { missing++; return }
            val n = raw?.trim()?.toBigDecimalOrNull()?.takeIf { it.precision() <= 10000 && kotlin.math.abs(it.scale().toLong()) <= 10000 }
            if (n == null || uncomputed || incompatible != null) {
                invalid++
                if (examples.length() < 20) examples.put(JSONObject().put("row", row).put("value", raw?.take(100))
                    .put("formula_without_cached_value", uncomputed).put("incompatible_numeric_type", incompatible ?: JSONObject.NULL))
                return
            }
            count++; sum = sum.add(n)
            if (min?.let { n < it } != false) min = n
            if (max?.let { n > it } != false) max = n
        }
        val ext = TextFileFormatPolicy.extension(a.name)
        if (ext == "xlsx") {
            strictReader(textFile(a)).buffered().useLines { lines ->
                lines.forEach { line ->
                    if (!active()) throw IOException("Анализ отменён")
                    if (line.isNotBlank()) {
                        val row = JSONObject(line)
                        if (sheet == null) sheet = row.getString("sheet")
                        if (row.getString("sheet") == sheet) {
                            sheetFound = true
                            if (row.optString("record") == "sheet") return@forEach
                            val cells = row.getJSONArray("cells")
                            val cell = (0 until cells.length()).mapNotNull { cells.optJSONObject(it) }
                                .firstOrNull { c -> columnNumber(c.optString("ref")) == column }
                            val value = cell?.let { JsonProtocol.text(it, "value") }
                            val incompatible = when {
                                cell?.optBoolean("date_formatted", false) == true -> "date_formatted_serial"
                                cell?.optString("type") == "b" -> "boolean"
                                else -> null
                            }
                            accept(row.getLong("row"), value, cell?.has("formula") == true && value.isNullOrBlank(), incompatible)
                        }
                    }
                }
            }
            require(sheetFound) { "В XLSX нет выбранного листа: $sheet" }
        } else {
            require(ext in setOf("csv", "tsv", "tab")) { "Statistics supports CSV, TSV, and XLSX" }
            val delimiter = args.optString("delimiter", if (ext == "csv") "," else "\t")
            require(delimiter.length == 1)
            strictReader(textFile(a)).use { raw ->
                val reader = PushbackReader(raw, 1)
                var row = 0L
                for (fields in csvRows(reader, delimiter[0])) { row++; accept(row, fields.getOrNull(column - 1)) }
            }
        }
        if (invalid > 0 && args.optBoolean("strict", true)) throw IOException("В выбранных строках $invalid нечисловых, невычисленных, логических или форматированных как дата ячеек. Они не суммируются как обычные числа. Выберите строки и проверьте данные: $examples")
        return JSONObject().put("file_id", a.id).put("sheet", sheet ?: JSONObject.NULL)
            .put("rows_scanned", scanned).put("numeric_count", count).put("missing_cells", missing).put("invalid_cells", invalid)
            .put("sum_exact", sum.toPlainString()).put("min", min?.toPlainString()).put("max", max?.toPlainString())
            .put("mean_exact", JSONObject().put("numerator", sum.toPlainString()).put("denominator", count))
            .put("mean_decimal_34_digits", if (count == 0L) JSONObject.NULL else sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128).toString())
            .put("complete_requested_range", true).put("invalid_examples", examples)
    }
    private fun columnNumber(ref: String): Int {
        var n = 0
        for (c in ref.takeWhile { it.isLetter() }) n = n * 26 + (c.uppercaseChar() - 'A' + 1)
        return n
    }
    private fun csvRows(reader: PushbackReader, delimiter: Char): Sequence<List<String>> = sequence {
        var fields = mutableListOf<String>(); var cell = StringBuilder(); var quoted = false; var any = false
        var closedQuote = false
        while (true) {
            if (!active()) throw IOException("Чтение CSV отменено")
            val code = reader.read()
            if (code < 0) {
                if (quoted) throw IOException("Незакрытая кавычка CSV")
                if (any || cell.isNotEmpty() || fields.isNotEmpty()) { fields.add(cell.toString()); yield(fields) }
                break
            }
            val c = code.toChar(); any = true
            if (quoted) {
                if (c == '"') {
                    val next = reader.read()
                    if (next == '"'.code) cell.append('"') else { quoted = false; closedQuote = true; if (next >= 0) reader.unread(next) }
                } else cell.append(c)
            } else when {
                c == '"' && cell.isEmpty() && !closedQuote -> quoted = true
                c == delimiter -> { fields.add(cell.toString()); cell = StringBuilder(); closedQuote = false }
                c == '\n' || c == '\r' -> {
                    if (c == '\r') { val next = reader.read(); if (next >= 0 && next != '\n'.code) reader.unread(next) }
                    fields.add(cell.toString()); yield(fields); fields = mutableListOf(); cell = StringBuilder(); any = false; closedQuote = false
                }
                closedQuote && c == ' ' -> Unit
                closedQuote || c == '"' -> throw IOException("Неверная кавычка CSV; данные не интерпретированы")
                else -> cell.append(c)
            }
            if (cell.length > 2_000_000 || fields.size > 100_000) throw IOException("Одна строка/ячейка CSV превышает безопасный размер; файл не обрезался")
        }
    }
}
