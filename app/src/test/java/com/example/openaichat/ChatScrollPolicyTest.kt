package com.example.openaichat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollPolicyTest {
    @Test
    fun staysPinnedWhenAlreadyAtBottom() {
        assertTrue(ChatScrollPolicy.shouldScrollToLatest(itemCount = 100, lastVisiblePosition = 99))
    }

    @Test
    fun staysPinnedWhenOnlyTwoItemsAway() {
        assertTrue(ChatScrollPolicy.shouldScrollToLatest(itemCount = 100, lastVisiblePosition = 97))
    }

    @Test
    fun preservesReaderPositionWhenFarAboveBottom() {
        assertFalse(ChatScrollPolicy.shouldScrollToLatest(itemCount = 100, lastVisiblePosition = 80))
    }

    @Test
    fun forceAlwaysScrolls() {
        assertTrue(ChatScrollPolicy.shouldScrollToLatest(itemCount = 100, lastVisiblePosition = 20, force = true))
    }

    @Test
    fun invalidOrEmptyListDefaultsToScroll() {
        assertTrue(ChatScrollPolicy.shouldScrollToLatest(itemCount = 0, lastVisiblePosition = -1))
        assertTrue(ChatScrollPolicy.shouldScrollToLatest(itemCount = 100, lastVisiblePosition = -1))
    }
}
