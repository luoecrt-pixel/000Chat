package com.example.openaichat

enum class AttachmentMenuAction(val title: String, val icon: String) {
    CAMERA("Камера", "📷"),
    PHOTO("Фото", "🖼"),
    FILES("Файлы", "📎")
}

/** Only the three supported attachment actions are shown; unrelated Claude features are not imitated. */
object AttachmentMenuPolicy {
    val actions = listOf(
        AttachmentMenuAction.CAMERA,
        AttachmentMenuAction.PHOTO,
        AttachmentMenuAction.FILES
    )
}
