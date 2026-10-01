package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterBackgroundOwnershipPolicyTest {
    @Test
    fun sameChatAndGenerationUsesCurrentComposer() {
        assertEquals(
            OpenRouterAttachmentCompletionTarget.CURRENT_COMPOSER,
            OpenRouterBackgroundOwnershipPolicy.decide("chat-a", "chat-a", 7L, 7L, true, false)
        )
    }

    @Test
    fun navigationUsesSourceChatPersistence() {
        assertEquals(
            OpenRouterAttachmentCompletionTarget.SOURCE_CHAT,
            OpenRouterBackgroundOwnershipPolicy.decide("chat-a", "chat-b", 7L, 7L, true, false)
        )
    }

    @Test
    fun generationChangeDoesNotMutateCurrentComposer() {
        assertEquals(
            OpenRouterAttachmentCompletionTarget.SOURCE_CHAT,
            OpenRouterBackgroundOwnershipPolicy.decide("chat-a", "chat-a", 7L, 8L, true, false)
        )
    }

    @Test
    fun destroyUsesSourceChatPersistenceAndExplicitCancelDiscards() {
        assertEquals(
            OpenRouterAttachmentCompletionTarget.SOURCE_CHAT,
            OpenRouterBackgroundOwnershipPolicy.decide("chat-a", "chat-a", 7L, 7L, false, false)
        )
        assertEquals(
            OpenRouterAttachmentCompletionTarget.DISCARD,
            OpenRouterBackgroundOwnershipPolicy.decide("chat-a", "chat-a", 7L, 7L, true, true)
        )
    }
}
