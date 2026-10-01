package com.example.openaichat

enum class OpenRouterAttachmentCompletionTarget {
    CURRENT_COMPOSER,
    SOURCE_CHAT,
    DISCARD
}

object OpenRouterBackgroundOwnershipPolicy {
    fun decide(
        sourceChatId: String,
        currentChatId: String,
        sourceGeneration: Long,
        currentGeneration: Long,
        activityVisible: Boolean,
        cancelled: Boolean
    ): OpenRouterAttachmentCompletionTarget = when {
        cancelled -> OpenRouterAttachmentCompletionTarget.DISCARD
        activityVisible && sourceChatId == currentChatId && sourceGeneration == currentGeneration ->
            OpenRouterAttachmentCompletionTarget.CURRENT_COMPOSER
        sourceChatId.isNotBlank() -> OpenRouterAttachmentCompletionTarget.SOURCE_CHAT
        else -> OpenRouterAttachmentCompletionTarget.DISCARD
    }
}
