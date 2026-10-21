package com.example.openaichat

import android.content.ContentValues
import android.database.Cursor
import android.database.CursorWindow
import android.database.CursorWrapper
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SqliteLargeTextQueryTest {
    @Test fun absentProjectionFailsRatherThanReturningItsQuotedNameOnEmptyAndPopulatedTables() {
        for (populated in listOf(false, true)) SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE data(id INTEGER PRIMARY KEY, payload TEXT)")
            if (populated) db.execSQL("INSERT INTO data VALUES(1,'original')")
            assertThrows("Missing projection must fail; populated=$populated", SQLiteException::class.java) {
                SqliteLargeTextQuery.query(db, "data", arrayOf("missing"),
                    null, null, null, null, null).use { it.moveToFirst() }
            }
            assertFalse(db.inTransaction())
            db.execSQL("INSERT INTO data VALUES(2,'still usable')")
        }
    }

    @Test fun absentKeyFailsEvenWithoutLargeTextOnEmptyAndPopulatedTables() {
        for (populated in listOf(false, true)) SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE data(id INTEGER PRIMARY KEY, payload TEXT)")
            if (populated) db.execSQL("INSERT INTO data VALUES(1,'short')")
            assertThrows("Missing key must fail; populated=$populated", SQLiteException::class.java) {
                SqliteLargeTextQuery.query(db, "data", arrayOf("payload"),
                    null, null, null, null, null, keyColumns = arrayOf("missing_key")).use { it.moveToFirst() }
            }
            assertFalse(db.inTransaction())
            db.execSQL("INSERT INTO data VALUES(2,'still usable')")
        }
    }

    @Test fun qualifiedRowIdAndSchemaProjectionPreserveLargeTextAndColumnNames() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE data(payload TEXT, note TEXT)")
            val text = "rowid😀\u0000данные".repeat(10000)
            db.execSQL("INSERT INTO data VALUES(?,?)", arrayOf(text, "note"))
            SqliteLargeTextQuery.query(db, "data", null, "rowid=?", arrayOf("1"),
                null, null, "rowid", keyColumns = arrayOf("rowid")).use { cursor ->
                assertEquals(listOf("payload", "note"), cursor.columnNames.toList())
                assertEquals(0, cursor.getColumnIndex("data.payload"))
                assertTrue(cursor.moveToFirst())
                assertEquals(text, cursor.getString(0))
                assertEquals("note", cursor.getString(1))
            }
            assertFalse(db.inTransaction())
        }
    }

    @Test fun completeUnicodeAndNulTextFitsSmallWindowAndKeepsColumnContract() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE data(id INTEGER PRIMARY KEY, payload TEXT, note TEXT, number INTEGER)")
            val text = "Философия😀\u0000科学\n".repeat(140000)
            db.insertOrThrow("data", null, ContentValues().apply {
                put("id", 1); put("payload", text); putNull("note"); put("number", 7)
            })
            db.insertOrThrow("data", null, ContentValues().apply {
                put("id", 2); put("payload", "short"); put("note", "next"); put("number", 8)
            })
            SqliteLargeTextQuery.query(db, "data", arrayOf("payload", "note", "number"),
                null, null, null, null, "id").use { c ->
                ((c as CursorWrapper).wrappedCursor as SQLiteCursor).window = CursorWindow("small", 128L * 1024)
                assertEquals(3, c.columnCount); assertEquals(listOf("payload", "note", "number"), c.columnNames.toList())
                assertEquals(0, c.getColumnIndex("PAYLOAD")); assertEquals(-1, c.getColumnIndex("_astra_key_0"))
                assertTrue(c.moveToFirst()); assertFalse(c.isNull(0)); assertTrue(c.isNull(1))
                assertEquals(Cursor.FIELD_TYPE_STRING, c.getType(0)); assertEquals(7, c.getInt(2))
                assertEquals(text, c.getString(0)); assertEquals(text, c.getString(0))
                assertTrue(c.moveToNext()); assertEquals("short", c.getString(0)); assertEquals("next", c.getString(1))
            }
            assertFalse(db.inTransaction())
        }
    }

    @Test fun compositeKeysAndNestedTransactionRemainOwnedByCaller() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE data(chat TEXT, request TEXT, payload TEXT, PRIMARY KEY(chat,request))")
            val text = "signed-block".repeat(20000)
            db.execSQL("INSERT INTO data VALUES(?,?,?)", arrayOf("c", "r", text))
            db.beginTransaction()
            try {
                SqliteLargeTextQuery.query(db, "data", arrayOf("payload"), "chat=? AND request=?",
                    arrayOf("c", "r"), null, null, null, keyColumns = arrayOf("chat", "request")).use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(text, c.getString(0))
                }
                assertTrue(db.inTransaction())
                db.execSQL("UPDATE data SET payload='must roll back'")
            } finally { db.endTransaction() }
            db.rawQuery("SELECT payload FROM data", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(text, c.getString(0))
            }
        }
    }

    @Test fun invalidProjectionDoesNotLeaveTransactionOpen() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE data(id INTEGER PRIMARY KEY, payload TEXT)")
            try {
                SqliteLargeTextQuery.query(db, "data", arrayOf("payload; DROP TABLE data"),
                    null, null, null, null, null).use { fail("Unsafe identifier accepted") }
            } catch (_: IllegalArgumentException) { }
            assertFalse(db.inTransaction())
            db.execSQL("INSERT INTO data VALUES(1,'still usable')")
        }
    }
}
