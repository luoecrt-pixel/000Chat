package com.example.openaichat

data class BackgroundResponseUiOwnershipState(
    val chatId: String,
    val generation: Long,
    val assistantItemId: String?,
    val activityVisible: Boolean,
    val finishing: Boolean
)

object BackgroundResponseUiOwnershipPolicy {
    fun shouldApply(
        snapshot: BackgroundResponseUiSnapshot,
        current: BackgroundResponseUiOwnershipState
    ): Boolean =
        current.activityVisible &&
            !current.finishing &&
            snapshot.chatId == current.chatId &&
            snapshot.generation == current.generation &&
            snapshot.assistantItemId == current.assistantItemId
}
