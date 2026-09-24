package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ActiveResponseStateTest {
    @Test
    fun nextGenerationInvalidatesEarlierCallbacks() {
        val store = ActiveResponseStateStore()
        val first = store.nextGeneration()
        val second = store.nextGeneration()

        assertEquals(1L, first)
        assertEquals(2L, second)
        assertFalse(store.state.isCurrent(first, activityVisible = true, finishing = false))
        assertTrue(store.state.isCurrent(second, activityVisible = true, finishing = false))
    }

    @Test
    fun currentRequestRequiresVisibleNonFinishingActivity() {
        val store = ActiveResponseStateStore()
        val generation = store.nextGeneration()

        assertTrue(store.state.isCurrent(generation, activityVisible = true, finishing = false))
        assertFalse(store.state.isCurrent(generation, activityVisible = false, finishing = false))
        assertFalse(store.state.isCurrent(generation, activityVisible = true, finishing = true))
    }

    @Test
    fun stopRecordsOldGenerationAndInvalidatesIt() {
        val store = ActiveResponseStateStore()
        val generation = store.nextGeneration()

        val stopped = store.markUserStop()

        assertEquals(generation, stopped)
        assertEquals(generation + 1L, store.state.generation)
        assertTrue(store.state.wasUserStopped(generation))
        assertFalse(store.state.isCurrent(generation, activityVisible = true, finishing = false))
        assertTrue(store.state.isCurrent(generation + 1L, activityVisible = true, finishing = false))
    }

    @Test
    fun stoppedGenerationRemainsInvalidatedAfterStartingAnotherRequest() {
        val store = ActiveResponseStateStore()
        val first = store.nextGeneration()
        store.markUserStop()
        val second = store.nextGeneration()

        assertTrue(store.state.wasUserStopped(first))
        assertFalse(store.state.wasUserStopped(second))
    }

    @Test
    fun laterStopDoesNotForgetEarlierStoppedGeneration() {
        val store = ActiveResponseStateStore()
        val first = store.nextGeneration()
        store.markUserStop()
        val second = store.nextGeneration()
        store.markUserStop()
        val third = store.nextGeneration()

        assertTrue(store.state.wasUserStopped(first))
        assertTrue(store.state.wasUserStopped(second))
        store.clear()
        assertTrue(store.state.wasUserStopped(first))
        assertTrue(store.state.wasUserStopped(second))
        assertFalse(store.state.wasUserStopped(third))
    }

    @Test
    fun stateSnapshotIsVolatileForCrossThreadReaders() {
        val field = ActiveResponseStateStore::class.java.getDeclaredField("state")

        assertTrue(Modifier.isVolatile(field.modifiers))
    }

    @Test
    fun backgroundResumeUiSnapshotMustMatchGenerationChatAndAssistant() {
        val store = ActiveResponseStateStore()
        val firstGeneration = store.nextGeneration()
        val snapshot = BackgroundResponseUiSnapshot("chat-a", firstGeneration, "assistant-a")

        assertTrue(store.state.isCurrent(snapshot, "chat-a", "assistant-a", true, false))

        store.nextGeneration()
        assertFalse(store.state.isCurrent(snapshot, "chat-a", "assistant-a", true, false))
        assertFalse(store.state.isCurrent(snapshot, "chat-b", "assistant-a", true, false))
        assertFalse(store.state.isCurrent(snapshot, "chat-a", "assistant-b", true, false))
        assertFalse(store.state.isCurrent(snapshot, "chat-a", "assistant-a", false, false))
    }

    @Test
    fun responseAndAssistantIdsCanBeClearedIndependently() {
        val store = ActiveResponseStateStore()
        store.setResponseId("resp_1")
        store.setAssistantItemId("assistant_1")

        assertEquals("resp_1", store.state.responseId)
        assertEquals("assistant_1", store.state.assistantItemId)

        store.clearAssistantItem()
        assertNull(store.state.assistantItemId)
        assertEquals("resp_1", store.state.responseId)

        store.clear()
        assertNull(store.state.responseId)
        assertNull(store.state.assistantItemId)
        assertNull(store.state.userStopInvalidatedThroughGeneration)
    }
}
