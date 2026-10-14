package com.example.openaichat

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Foreground and Worker keep one success/failure entry per paid image. */
object GeneratedImageStore {
    fun save(filesDir: File, results: List<String>, onFailure: (Int, Exception) -> Unit): JSONArray {
        val out = JSONArray()
        val directory = File(filesDir, "generated_images")
        results.forEachIndexed { index, encoded ->
            try {
                if (encoded.length.toLong() > 72L * 1024L * 1024L) throw IOException("Изображение слишком большое")
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                if (bytes.size.toLong() > 50L * 1024L * 1024L) throw IOException("Изображение слишком большое")
                val file = AtomicGeneratedImageFile.write(directory, bytes)
                out.put(JSONObject().put("filename", file.name).put("local_path", file.absolutePath)
                    .put("mime_type", "image/png").put("size_bytes", file.length())
                    .put("source_index", index).put("state", "READY"))
            } catch (failure: Exception) {
                onFailure(index, failure)
                out.put(JSONObject().put("state", "FAILED").put("source_index", index)
                    .put("error", failure.message.orEmpty().take(300))
                    .put("filename", "astra_image_${index + 1}.png"))
            }
        }
        return out
    }
}
