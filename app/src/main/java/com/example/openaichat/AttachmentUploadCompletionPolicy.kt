package com.example.openaichat

enum class AttachmentUploadCompletionTarget {
    DISCARD,
    CURRENT_COMPOSER,
    SOURCE_CHAT
}

object AttachmentUploadCompletionPolicy {
    fun decide(
        sourceChatId: String,
        currentChatId: String,
        cancelled: Boolean
    ): AttachmentUploadCompletionTarget =
        when {
            cancelled -> AttachmentUploadCompletionTarget.DISCARD
            sourceChatId == currentChatId -> AttachmentUploadCompletionTarget.CURRENT_COMPOSER
            else -> AttachmentUploadCompletionTarget.SOURCE_CHAT
        }
}
