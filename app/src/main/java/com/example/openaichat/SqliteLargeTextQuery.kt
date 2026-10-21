package com.example.openaichat

import android.database.Cursor
import android.database.CursorWrapper
import android.database.sqlite.SQLiteDatabase
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/**
 * Internal TEXT/scalar reader: keeps CursorWindow rows small without shortening text.
 * Tables must have small, non-null keys and use Android's default UTF-8 database encoding.
 * Use and close the cursor on its creating thread. The read transaction prevents mixed
 * versions across byte slices; an existing caller transaction retains its ownership.
 */
internal object SqliteLargeTextQuery {
    private const val ROW_BYTES = 48 * 1024
    private const val CHUNK_BYTES = 32 * 1024
    private fun identifier(value: String): String {
        require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Expected an internal SQLite identifier" }
        return "\"$value\""
    }
    // SQLite can interpret an unknown double-quoted name as a string literal.
    // A qualified reference must resolve to a real column, including on empty tables.
    private fun column(quotedTable: String, value: String): String = "$quotedTable.${identifier(value)}"

    fun query(
        db: SQLiteDatabase, table: String, columns: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, groupBy: String?, having: String?, orderBy: String?,
        limit: String? = null, keyColumns: Array<String> = arrayOf("id")
    ): Cursor {
        require(groupBy == null && having == null) { "Large TEXT query needs individual keyed rows" }
        val quotedTable = identifier(table)
        val ownedTransaction = !db.inTransaction()
        if (ownedTransaction) db.beginTransactionNonExclusive()
        try {
            val fields = columns ?: db.rawQuery("PRAGMA table_info($quotedTable)", null).use { schema ->
                val names = mutableListOf<String>()
                while (schema.moveToNext()) names.add(schema.getString(schema.getColumnIndexOrThrow("name")))
                names.toTypedArray()
            }
            require(fields.isNotEmpty() && keyColumns.isNotEmpty())
            // Both TEXT byte totals and per-column overhead are bounded, without shortening data.
            val inlineBytes = minOf(4096, ROW_BYTES / (fields.size + keyColumns.size + 1)).coerceAtLeast(1)
            val projection = mutableListOf<String>()
            fields.forEach { field ->
                val name = column(quotedTable, field)
                projection.add("CASE WHEN typeof($name)='text' AND length(CAST($name AS BLOB))>$inlineBytes THEN NULL ELSE $name END AS ${identifier(field)}")
            }
            fields.forEachIndexed { index, field ->
                val name = column(quotedTable, field)
                projection.add("CASE WHEN typeof($name)='text' AND length(CAST($name AS BLOB))>$inlineBytes THEN length(CAST($name AS BLOB)) ELSE NULL END AS _astra_size_$index")
            }
            keyColumns.forEachIndexed { index, field -> projection.add("${column(quotedTable, field)} AS _astra_key_$index") }
            val cursor = db.query(table, projection.toTypedArray(), selection, selectionArgs, null, null, orderBy, limit)
            return LargeTextCursor(cursor, db, quotedTable, fields, keyColumns, ownedTransaction)
        } catch (error: Throwable) {
            if (ownedTransaction) db.endTransaction()
            throw error
        }
    }

    private class LargeTextCursor(
        private val source: Cursor, private val db: SQLiteDatabase, private val table: String,
        private val fields: Array<String>, private val keys: Array<String>,
        private val ownedTransaction: Boolean
    ) : CursorWrapper(source) {
        private var ended = false
        private var cachedPosition = -1
        private val values = mutableMapOf<Int, String>()
        private fun size(index: Int): Long? {
            require(index in fields.indices)
            val marker = fields.size + index
            return if (source.isNull(marker)) null else source.getLong(marker)
        }
        override fun getColumnCount(): Int = fields.size
        override fun getColumnNames(): Array<String> = fields.copyOf()
        override fun getColumnName(index: Int): String = fields[index]
        override fun getColumnIndex(name: String): Int = fields.indexOfFirst { it.equals(name.substringAfterLast('.'), ignoreCase = true) }
        override fun getColumnIndexOrThrow(name: String): Int = getColumnIndex(name).also {
            require(it >= 0) { "Unknown column: $name" }
        }
        override fun isNull(index: Int): Boolean = size(index) == null && source.isNull(index)
        override fun getType(index: Int): Int = if (size(index) != null) Cursor.FIELD_TYPE_STRING else source.getType(index)
        override fun getString(index: Int): String? {
            val length = size(index) ?: return source.getString(index)
            if (cachedPosition != source.position) { values.clear(); cachedPosition = source.position }
            return values.getOrPut(index) {
                val where = keys.joinToString(" AND ") { "${column(table, it)}=?" }
                val args = keys.indices.map { key ->
                    source.getString(fields.size * 2 + key) ?: throw IOException("A large TEXT row has no key")
                }.toTypedArray()
                val stream = TextBytes(db, table, column(table, fields[index]), where, args, length)
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                InputStreamReader(stream, decoder).use { it.readText() }
            }
        }
        override fun close() {
            if (ended) return
            ended = true
            values.clear()
            try { source.close() } finally {
                if (ownedTransaction) {
                    try { db.setTransactionSuccessful() } finally { db.endTransaction() }
                }
            }
        }
    }

    /** Byte slices preserve supplementary Unicode and embedded NUL, unlike TEXT substr offsets. */
    private class TextBytes(
        private val db: SQLiteDatabase, private val table: String, private val field: String,
        private val where: String, private val keyArgs: Array<String>, private val length: Long
    ) : InputStream() {
        private var offset = 0L
        private var chunk = ByteArray(0)
        private var index = 0
        private fun fill(): Boolean {
            if (index < chunk.size) return true
            if (offset >= length) return false
            if (Thread.currentThread().isInterrupted) throw IOException("SQLite text read cancelled")
            val count = minOf(CHUNK_BYTES.toLong(), length - offset).toInt()
            val args = arrayOf((offset + 1L).toString(), count.toString()) + keyArgs
            chunk = db.rawQuery("SELECT substr(CAST($field AS BLOB),?,?) FROM $table WHERE $where", args).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) throw IOException("Long TEXT row disappeared")
                cursor.getBlob(0)
            }
            if (chunk.size != count) throw IOException("Long TEXT row changed during reading")
            offset += chunk.size; index = 0
            return true
        }
        override fun read(): Int = if (fill()) chunk[index++].toInt() and 255 else -1
        override fun read(buffer: ByteArray, start: Int, count: Int): Int {
            require(start >= 0 && count >= 0 && start <= buffer.size - count)
            if (count == 0) return 0
            if (!fill()) return -1
            val copied = minOf(count, chunk.size - index)
            chunk.copyInto(buffer, start, index, index + copied)
            index += copied
            return copied
        }
        override fun close() { chunk = ByteArray(0) }
    }
}
