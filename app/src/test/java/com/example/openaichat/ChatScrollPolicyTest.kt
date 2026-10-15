package com.example.openaichat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    @Test
    fun tallLastMessageDoesNotPullReaderToBottomUntilWithinPixelThreshold() {
        assertFalse(ChatScrollPolicy.shouldScrollToLatest(100, 99,
            pixelDistanceFromBottom = 500, maxFollowDistancePx = 64))
        assertFalse(ChatScrollPolicy.shouldScrollToLatest(100, 99,
            pixelDistanceFromBottom = 65, maxFollowDistancePx = 64))
        assertTrue(ChatScrollPolicy.shouldScrollToLatest(100, 99,
            pixelDistanceFromBottom = 64, maxFollowDistancePx = 64))
    }

    @Test
    fun explicitScrollLockPreventsFollowingButForceOverridesIt() {
        assertFalse(ChatScrollPolicy.shouldScrollToLatest(100, 99,
            pixelDistanceFromBottom = 0, userScrollLocked = true))
        assertTrue(ChatScrollPolicy.shouldScrollToLatest(100, 20, force = true,
            pixelDistanceFromBottom = 500, userScrollLocked = true))
    }

    @Test
    fun jumpButtonTargetsLastMessageStartThenBottomAfterFirstJump() {
        assertEquals(JumpButtonTarget.LAST_MESSAGE_START,
            ChatScrollPolicy.jumpTarget(10, firstVisiblePosition = 3, lastItemTopPx = null))
        assertEquals(JumpButtonTarget.LAST_MESSAGE_START,
            ChatScrollPolicy.jumpTarget(10, firstVisiblePosition = 8, lastItemTopPx = 400))
        assertEquals(JumpButtonTarget.CHAT_BOTTOM,
            ChatScrollPolicy.jumpTarget(10, firstVisiblePosition = 9, lastItemTopPx = 0))
        assertEquals(JumpButtonTarget.CHAT_BOTTOM,
            ChatScrollPolicy.jumpTarget(10, firstVisiblePosition = 9, lastItemTopPx = -500))
    }

    @Test
    fun jumpTargetRespectsTopThresholdAndHandlesEmptyChat() {
        assertEquals(JumpButtonTarget.LAST_MESSAGE_START,
            ChatScrollPolicy.jumpTarget(10, 8, lastItemTopPx = 29, topThresholdPx = 28))
        assertEquals(JumpButtonTarget.CHAT_BOTTOM,
            ChatScrollPolicy.jumpTarget(10, 8, lastItemTopPx = 28, topThresholdPx = 28))
        assertEquals(JumpButtonTarget.CHAT_BOTTOM,
            ChatScrollPolicy.jumpTarget(0, -1, lastItemTopPx = null))
    }
}
