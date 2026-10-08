package com.example.openaichat

enum class AttachmentMenuAction(val title: String, val icon: String) {
    CAMERA("Камера", "📷"),
    PHOTO("Фото", "🖼"),
    FILES("Файлы", "📎")
}

/** Only attachment actions already implementable by this app are shown. */
object AttachmentMenuPolicy {
    val actions = listOf(
        AttachmentMenuAction.CAMERA,
        AttachmentMenuAction.PHOTO,
        AttachmentMenuAction.FILES
    )
}
