package com.example.openaichat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.util.UUID

object OpenRouterAttachmentPreflight {
    // https://platform.claude.com/docs/en/build-with-claude/vision (verified 2026-10-10):
    // 10 MB base64 direct Anthropic; 5 MB base64 Bedrock/Google Cloud. Route is chosen by OpenRouter.
    // Auto protects partner routes; explicitly pinned Anthropic retains its larger direct limit.
    private const val PARTNER_MAX_ENCODED = 5_000_000L
    fun encodedBytes(bytes: Long): Long = ((bytes + 2L) / 3L) * 4L
    fun verifySources(attachments: List<OpenRouterLocalAttachment>) {
        attachments.forEach { a ->
            val file = File(a.path)
            if (!file.isFile) throw IOException("Локальное вложение недоступно: ${a.name}")
            if (a.sha256.isNotBlank()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Проверка файла отменена")
                        val n = input.read(buffer); if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != a.sha256) throw IOException("Изменились байты вложения: ${a.name}. Проверьте исходный файл.")
            }
        }
    }
    fun validate(model: String, attachments: List<OpenRouterLocalAttachment>, route: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO) {
        val inline = attachments.filter { it.kind == "image" || it.kind == "pdf" }
        if (OpenRouterClaudeRoute.isClaude(model)) {
            val edge = if (attachments.count { it.kind == "image" } > 20) 2000 else 8000
            inline.filter { it.kind == "image" }.forEach { a ->
                val wire = File(a.wirePath ?: a.path)
                validateImageBytes(model, a.name, wire.length(), route)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(wire.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Изображение нельзя декодировать: ${a.name}")
                if (bounds.outWidth > edge || bounds.outHeight > edge) throw IOException("Размер изображения ${bounds.outWidth}×${bounds.outHeight} превышает предел Claude ($edge пикселей на сторону для этого запроса); исходник сохранён. Можно выбрать совместимую копию.")
            }
        }
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        // Wire Base64 is emitted from disk with fixed buffers; total encoded bytes are not retained.
        // This is an early low-memory diagnostic, not a guarantee against every device OOM.
        if (inline.isNotEmpty() && available < 4L * 1024 * 1024) throw IOException("Недостаточно оперативной памяти даже для потоковой передачи вложений. История и исходники сохранены.")
    }
    /** Explicitly selected transport copy. The user's original and chat preview remain unchanged. */
    fun compatible(a: OpenRouterLocalAttachment, maxEdge: Int = 2576): OpenRouterLocalAttachment {
        if (a.kind != "image") return a
        val original = File(a.wirePath ?: a.path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Не удалось прочитать размеры фото")
        if (encodedBytes(original.length()) <= PARTNER_MAX_ENCODED && bounds.outWidth <= maxEdge && bounds.outHeight <= maxEdge) return a
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxEdge) sample *= 2
        val bitmap = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.let { ImageOrientation.upright(it, original.path) }
            ?: throw IOException("Не удалось подготовить совместимую копию фото")
        val copy = PreparedAttachmentFiles.derivative(original.parentFile ?: throw IOException("Нет каталога изображения"), ".jpg")
        try {
            for (quality in listOf(95, 90, 85, 80, 70, 60, 50)) {
                copy.outputStream().use { out -> if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("Не удалось сохранить копию") }
                if (encodedBytes(copy.length()) <= PARTNER_MAX_ENCODED) return a.copy(wirePath = copy.path, wireMime = "image/jpeg")
            }
            throw IOException("Совместимая копия не помещается в предел провайдера; исходник сохранён")
        } catch (e: Exception) { copy.delete(); throw e } finally { bitmap.recycle() }
    }
    fun validateImageBytes(model: String, name: String, sourceBytes: Long, route: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO) {
        require(sourceBytes >= 0 && sourceBytes <= Long.MAX_VALUE / 4 - 2) { "Invalid image size" }
        if (!OpenRouterClaudeRoute.isClaude(model)) return
        val encoded = encodedBytes(sourceBytes)
        if (encoded > route.imageBase64Limit) throw IOException(
            "Изображение $name занимает $encoded байт после Base64; маршрут «${route.label}» допускает ${route.imageBase64Limit} байт. " +
                "Исходник сохранён. " + if (route == OpenRouterClaudeRoute.AUTO)
                "Выберите прямой Anthropic (до 10 МБ Base64, без резервного провайдера) или совместимую копию в настройках файлов."
                else "Выберите совместимую копию в настройках файлов или другой провайдер.")
    }
    fun validateRequestBytes(model: String, bytes: Long, route: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO) {
        // https://platform.claude.com/docs/en/api/overview#request-size-limits (2026-10-10).
        // Native Messages: 32 MB; partners have lower limits (Bedrock 20 / Google 30 MB).
        // OpenRouter's wire envelope differs, so this does not prove every upstream route fits.
        require(bytes >= 0) { "Invalid request size" }
        if (OpenRouterClaudeRoute.isClaude(model) && bytes > route.requestByteLimit)
            throw IOException("Запрос Claude занимает $bytes байт вместе с историей, вложениями и результатами инструментов; маршрут «${route.label}» допускает ${route.requestByteLimit} байт. " +
                (if (route == OpenRouterClaudeRoute.AUTO) "Можно выбрать прямой Anthropic (32 МБ, без резервного провайдера). " else "") +
                "Уберите выбранные вложения из контекста или начните новый чат. История и исходники сохранены; ничего не удалено автоматически.")
    }
}
