package com.example.openaichat

import org.json.JSONObject
import java.io.IOException

object AttachmentHistoryPolicy {
    fun validate(message: JSONObject) {
        val ids = message.optJSONArray("file_ids")
        val paths = message.optJSONArray("file_paths")
        if (ids != null) for (i in 0 until ids.length()) {
            val id = ids.opt(i) as? String
            if (id.isNullOrBlank() || id == "null") continue
            val path = paths?.opt(i) as? String
            if (path.isNullOrBlank() || path == "null") {
                throw IOException("В истории есть файл без локальной копии. " +
                    "Восстановите исходное вложение перед восстановлением контекста или сменой провайдера.")
            }
        }
    }
}
