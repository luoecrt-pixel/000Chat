package com.example.openaichat

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Never leave half-written copies and never treat cancellation as a READY attachment. */
object BoundedAttachmentCopy {
    const val MAX_INPUT_BYTES = 25L * 1024 * 1024

    fun copy(
        source: InputStream,
        target: File,
        maxBytes: Long = MAX_INPUT_BYTES,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted }
    ): Long {
        require(maxBytes > 0)
        var total = 0L
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (!shouldContinue()) throw IOException("Подготовка вложения отменена")
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (total > maxBytes - n) throw IOException(
                        "Вложение превышает лимит " + (maxBytes / 1024 / 1024) + " МБ; оригинал не изменён"
                    )
                    output.write(buffer, 0, n)
                    total += n
                }
            }
            return total
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }
}
