package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentUploadCompletionPolicyTest {
    @Test
    fun cancelledUploadIsDiscardedEvenWhenChatIsStillCurrent() {
        assertEquals(
            AttachmentUploadCompletionTarget.DISCARD,
            AttachmentUploadCompletionPolicy.decide(
                sourceChatId = "chat-a",
                currentChatId = "chat-a",
                cancelled = true
            )
        )
    }

    @Test
    fun completedUploadTargetsCurrentComposerWhenChatDidNotChange() {
        assertEquals(
            AttachmentUploadCompletionTarget.CURRENT_COMPOSER,
            AttachmentUploadCompletionPolicy.decide(
                sourceChatId = "chat-a",
                currentChatId = "chat-a",
                cancelled = false
            )
        )
    }

    @Test
    fun completedUploadTargetsSourceChatAfterChatSwitch() {
        assertEquals(
            AttachmentUploadCompletionTarget.SOURCE_CHAT,
            AttachmentUploadCompletionPolicy.decide(
                sourceChatId = "chat-a",
                currentChatId = "chat-b",
                cancelled = false
            )
        )
    }

    @Test
    fun blankSourceChatNeverSilentlyBecomesCurrentComposer() {
        assertEquals(
            AttachmentUploadCompletionTarget.SOURCE_CHAT,
            AttachmentUploadCompletionPolicy.decide(
                sourceChatId = "",
                currentChatId = "chat-a",
                cancelled = false
            )
        )
    }
}
