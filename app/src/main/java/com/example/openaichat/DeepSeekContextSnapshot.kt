package com.example.openaichat

import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

object DeepSeekContextSnapshot {
    const val MAX_REQUEST_BYTES = 48L * 1024 * 1024
    const val MAX_INLINE_IMAGE_BYTES = BoundedAttachmentCopy.MAX_INPUT_BYTES
    const val MAX_IMAGES_PER_REQUEST = 600
    const val MAX_OUTPUT_TOKENS = 393_216
    private const val JSON_MODE_INSTRUCTION = "Ответь строго валидным JSON-объектом (json)."
    data class ThinkingConfig(val enabled: Boolean, val effort: String?)

    fun supportsVision(model: String) = model.trim().removePrefix("~") == "deepseek-flash"

    fun resolveThinking(selectedEffort: String?): ThinkingConfig = when (val effort = selectedEffort?.trim()?.lowercase().orEmpty()) {
        "none" -> ThinkingConfig(false, null)
        "", "auto", "high" -> ThinkingConfig(true, "high")
        "low", "max" -> ThinkingConfig(true, effort)
        else -> throw IOException("DeepSeek не поддерживает выбранный effort '$effort'. Выберите none, low, high или max; черновик сохранён.")
    }

    fun validateMaxTokens(maxTokens: Int?) {
        if (maxTokens != null && maxTokens !in 1..MAX_OUTPUT_TOKENS)
            throw IOException("Max output tokens DeepSeek должен быть в диапазоне 1..$MAX_OUTPUT_TOKENS; черновик сохранён.")
    }

    fun validateResponseFormat(responseFormat: JSONObject?): JSONObject? {
        if (responseFormat == null) return null
        val type = JsonProtocol.text(responseFormat, "type")
        if (type !in setOf("text", "json_object")) throw IOException("DeepSeek поддерживает только text и json_object; черновик сохранён.")
        // Unknown settings must not silently disappear between binding and HTTP.
        if (responseFormat.keys().asSequence().any { it != "type" })
            throw IOException("Неподдерживаемые параметры формата ответа DeepSeek; черновик сохранён.")
        return JSONObject().put("type", type)
    }

    fun validateAttachments(model: String, attachments: List<OpenRouterLocalAttachment>) {
        attachments.firstOrNull { it.kind == "pdf" }?.let {
            throw IOException("Прямой DeepSeek API не принимает бинарные PDF (${it.name}); исходный файл и черновик сохранены.")
        }
        val images = attachments.filter { it.kind == "image" }
        if (images.isNotEmpty() && !supportsVision(model)) throw IOException("Модель $model не поддерживает изображения; выберите DeepSeek Flash. Исходники сохранены.")
        if (images.size > MAX_IMAGES_PER_REQUEST) throw IOException("Превышен лимит $MAX_IMAGES_PER_REQUEST изображений DeepSeek на запрос.")
        val maxEdge = if (images.size >= 15) 4096 else 8192
        images.forEach { image ->
            val wire = File(image.wirePath ?: image.path)
            if (!wire.isFile || wire.length() <= 0L) throw IOException("Локальное изображение недоступно: ${image.name}")
            if (wire.length() > MAX_INLINE_IMAGE_BYTES) throw IOException("Изображение ${image.name} превышает локальный лимит 25 МиБ.")
            if (OpenRouterAttachmentStore.detectImageMime(wire) == null) throw IOException("Повреждённое изображение: ${image.name}")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(wire.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Не удалось прочитать размеры: ${image.name}")
            if (bounds.outWidth > maxEdge || bounds.outHeight > maxEdge)
                throw IOException("Разрешение ${image.name} превышает $maxEdge пикселей на сторону при ${images.size} изображениях. Исходник сохранён; можно выбрать совместимую копию JPEG.")
        }
    }

    fun validateRequestBytes(bytes: Long) {
        require(bytes >= 0)
        if (bytes > MAX_REQUEST_BYTES) throw IOException("Запрос DeepSeek превышает лимит 48 МиБ; история и исходники сохранены.")
    }

    fun toContentBlock(a: OpenRouterLocalAttachment): JSONObject = when (a.kind) {
        "text", "document", "table" -> JSONObject().put("type", "text").put("text", LocalFileTools.descriptor(a).toString())
        "image" -> JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", OpenRouterAttachmentStore.streamingDataUrl(a)))
        else -> throw IOException("Формат ${a.name} (${a.kind}) не поддерживается прямым DeepSeek API.")
    }

    fun systemContent(instructions: GenerationInstructions, github: Boolean, repo: String, branches: String,
        jsonObjectMode: Boolean = false): String? {
        val base = OpenRouterContextSnapshot.systemContent(instructions, github, repo, branches)
        return if (!jsonObjectMode) base else if (base.isNullOrBlank()) JSON_MODE_INSTRUCTION else "$base\n\n$JSON_MODE_INSTRUCTION"
    }

    fun build(history: List<JSONObject>, system: String?, byMessage: List<List<OpenRouterLocalAttachment>>,
        expectedBinding: String, protocolPrefix: ((JSONObject, Boolean) -> Unit)? = null,
        wirePart: (JSONObject, OpenRouterLocalAttachment, JSONObject) -> Unit = { _, _, _ -> }): JSONArray {
        require(history.size == byMessage.size && expectedBinding.isNotBlank())
        val result = JSONArray()
        if (!system.isNullOrBlank()) result.put(JSONObject().put("role", "system").put("content", system))
        val blocks = mutableMapOf<String, JSONObject>()
        var downgraded = false
        history.forEachIndexed { index, message ->
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Подготовка отменена")
            val role = JsonProtocol.text(message, "role")
            if (role !in setOf("user", "assistant")) return@forEachIndexed
            val text = JsonProtocol.string(message, "text")
            val trace = message.optJSONArray("tool_trace")
            val traceText = if (role == "assistant" && trace != null) (0 until trace.length()).mapNotNull { i ->
                trace.optJSONObject(i)?.let { "${it.optString("name").take(100)}: ${it.optString("status").take(60)}" }
            }.takeIf { it.isNotEmpty() }?.joinToString("; ", "\n\n[Действия: ", "]").orEmpty() else ""
            val fallback = text + traceText
            if (role == "assistant") message.optJSONObject(DeepSeekProtocolCodec.FIELD)?.let { protocol ->
                val appended = DeepSeekProtocolCodec.append(result, message, expectedBinding, fallback)
                protocolPrefix?.invoke(protocol, appended)
                if (appended) return@forEachIndexed
                downgraded = true
            }
            val attachments = byMessage[index]
            val item = JSONObject().put("role", role)
            if (attachments.isEmpty()) {
                if (fallback.isBlank()) return@forEachIndexed
                item.put("content", fallback)
            } else {
                val content = JSONArray()
                if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
                attachments.forEach { attachment ->
                    val part = JsonProtocol.objectCopy(blocks.getOrPut(attachment.id) { toContentBlock(attachment) })
                    content.put(part); wirePart(message, attachment, part)
                }
                item.put("content", content)
            }
            if (downgraded) { DeepSeekProtocolCodec.markDowngradedFallback(item); downgraded = false }
            result.put(item)
        }
        if (downgraded && result.length() > 0) result.optJSONObject(result.length() - 1)?.let(DeepSeekProtocolCodec::markDowngradedFallback)
        return result
    }
}
