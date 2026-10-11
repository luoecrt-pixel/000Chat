package com.example.openaichat

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Original wire bytes + file metadata + credential/project boundary; no model-specific ID reuse. */
internal class OpenAiFileUploadCache(private val store: SharedPreferences) {
    companion object {
        // Protect only preference transactions; hashing and network work use per-file locks.
        private val lock = Any()
        private const val GENERATION = "__generation"
        private const val REVISION = "__revision:"
    }
    fun getOrUpload(file: File, name: String, mime: String, credential: String,
                    active: () -> Boolean, purpose: String = if (mime.startsWith("image/")) "vision" else "user_data",
                    upload: () -> String): String {
        if (!file.isFile) throw IOException("Файл для анализа недоступен: $name")
        fun digest(): String {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    if (!active()) throw IOException("Подготовка файла отменена")
                    val count = input.read(bytes); if (count < 0) break
                    sha.update(bytes, 0, count)
                }
            }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        val before = digest()
        val key = StreamingJsonRequestBody.sha256(JSONObject().put("bytes", before).put("name", name)
            .put("mime", mime).put("credential", StreamingJsonRequestBody.sha256(credential))
            .put("purpose", purpose))
        return KeyedFileLocks.with("openai-upload:$key", active) {
            val generation = synchronized(lock) {
                store.getString(key, null)?.takeIf(::isOpenAiFileId)?.let { return@with it }
                store.getLong(GENERATION, 0L) to store.getLong(REVISION + key, 0L)
            }
            val id = upload()
            if (!isOpenAiFileId(id) || digest() != before) throw IOException("Загрузка файла не подтверждена или исходные байты изменились")
            synchronized(lock) {
                // A concurrent explicit reset/invalidation must not repopulate old cache state.
                // A failure to persist the cache does not discard a successfully uploaded file.
                if (store.getLong(GENERATION, 0L) == generation.first && store.getLong(REVISION + key, 0L) == generation.second) {
                    store.edit().putString(key, id).commit()
                }
            }
            id
        }
    }
    fun invalidate(fileId: String) = synchronized(lock) {
        val editor = store.edit()
        store.all.filterValues { it == fileId }.keys.forEach {
            editor.remove(it).putLong(REVISION + it, store.getLong(REVISION + it, 0L) + 1L)
        }
        editor.commit()
    }
    fun clear() = synchronized(lock) {
        store.edit().clear().putLong(GENERATION, store.getLong(GENERATION, 0L) + 1L).commit()
    }
    fun knownIds(): Set<String> = synchronized(lock) {
        store.all.values.filterIsInstance<String>().filter(::isOpenAiFileId).toSet()
    }
}
