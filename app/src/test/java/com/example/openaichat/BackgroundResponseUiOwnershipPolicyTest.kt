package com.example.openaichat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundResponseUiOwnershipPolicyTest {
    private val snapshot = BackgroundResponseUiSnapshot(
        chatId = "chat-a",
        generation = 7L,
        assistantItemId = "assistant-a"
    )

    private fun current(
        chatId: String = "chat-a",
        generation: Long = 7L,
        assistantItemId: String? = "assistant-a",
        activityVisible: Boolean = true,
        finishing: Boolean = false
    ) = BackgroundResponseUiOwnershipState(
        chatId = chatId,
        generation = generation,
        assistantItemId = assistantItemId,
        activityVisible = activityVisible,
        finishing = finishing
    )

    @Test
    fun acceptsExactCurrentOwnership() {
        assertTrue(
            BackgroundResponseUiOwnershipPolicy.shouldApply(snapshot, current())
        )
    }

    @Test
    fun rejectsChangedChatGenerationOrAssistantItem() {
        assertFalse(
            BackgroundResponseUiOwnershipPolicy.shouldApply(
                snapshot,
                current(chatId = "chat-b")
            )
        )
        assertFalse(
            BackgroundResponseUiOwnershipPolicy.shouldApply(
                snapshot,
                current(generation = 8L)
            )
        )
        assertFalse(
            BackgroundResponseUiOwnershipPolicy.shouldApply(
                snapshot,
                current(assistantItemId = "assistant-b")
            )
        )
    }

    @Test
    fun rejectsInvisibleOrFinishingActivity() {
        assertFalse(
            BackgroundResponseUiOwnershipPolicy.shouldApply(
                snapshot,
                current(activityVisible = false)
            )
        )
        assertFalse(
            BackgroundResponseUiOwnershipPolicy.shouldApply(
                snapshot,
                current(finishing = true)
            )
        )
    }

    @Test
    fun missingAssistantItemCannotOwnSnapshot() {
        assertFalse(
            BackgroundResponseUiOwnershipPolicy.shouldApply(
                snapshot,
                current(assistantItemId = null)
            )
        )
    }
}
