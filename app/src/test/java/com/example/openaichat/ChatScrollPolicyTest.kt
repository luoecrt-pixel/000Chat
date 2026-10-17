package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun anchorJsonCodecPreservesPerChatReadingStateAcrossSavedInstanceState() {
        val anchors = linkedMapOf(
            "chat-a" to ChatScrollAnchor(
                messageId = "chat-3-assistant-req_1",
                contentSignature = "sig-a",
                position = 3,
                topOffsetPx = -640,
                mode = ChatScrollMode.READING_LOCKED,
                distanceFromBottomPx = 1120,
                itemHeightPx = 2400,
                viewportWidthPx = 1080,
                fontScale = 1.15f,
                charOffset = 420
            ),
            "chat-b" to ChatScrollAnchor(
                messageId = "chat-1-user-req_2",
                contentSignature = "sig-b",
                position = 1,
                topOffsetPx = 0,
                mode = ChatScrollMode.FOLLOW_LATEST,
                distanceFromBottomPx = 0,
                itemHeightPx = 180,
                viewportWidthPx = 1080,
                fontScale = 1.0f,
                charOffset = 0
            )
        )
        val encoded = ChatScrollPolicy.encodeAnchorsJson(anchors)
        val decoded = ChatScrollPolicy.decodeAnchorsJson(encoded)
        assertEquals(anchors, decoded)
        assertTrue(decoded.getValue("chat-a").isReadingLocked)
        assertFalse(decoded.getValue("chat-b").isReadingLocked)
        assertTrue(ChatScrollPolicy.decodeAnchorsJson(null).isEmpty())
        assertTrue(ChatScrollPolicy.decodeAnchorsJson("not-json").isEmpty())
        assertNull(ChatScrollAnchor.fromJson(JSONObject()))
    }

    @Test
    fun messageIdentityMatchesActiveAndCommittedAssistantItems() {
        val activeId = ChatScrollPolicy.activeAssistantIdentity("req_42", null, 5)
        val committedMessage = JSONObject().put("role", "assistant").put("request_id", "req_42").put("text", "Answer")
        val committedId = ChatScrollPolicy.messageIdentity(5, false, committedMessage)
        assertEquals(activeId, committedId)
        assertEquals("chat-5-assistant-req_42", committedId)
    }

    @Test
    fun resolveAnchorPositionPrefersExactIdThenSignatureThenBoundedPosition() {
        val m0 = JSONObject().put("role", "user").put("request_id", "r0").put("text", "Q0")
        val m1 = JSONObject().put("role", "assistant").put("request_id", "r0").put("text", "A0")
        val m2 = JSONObject().put("role", "user").put("request_id", "r1").put("text", "Q1")
        val items = listOf(
            ChatBubbleItem.create(ChatScrollPolicy.messageIdentity(0, true, m0), "Ты", "Q0", true, message = m0, chatIndex = 0),
            ChatBubbleItem.create(ChatScrollPolicy.messageIdentity(1, false, m1), "GPT", "A0", false, message = m1, chatIndex = 1),
            ChatBubbleItem.create(ChatScrollPolicy.messageIdentity(2, true, m2), "Ты", "Q1", true, message = m2, chatIndex = 2)
        )
        val byId = ChatScrollAnchor(messageId = items[1].id, contentSignature = "other", position = 0, topOffsetPx = -200)
        assertEquals(1, ChatScrollPolicy.resolveAnchorPosition(byId, items))

        val bySignature = ChatScrollAnchor(messageId = "stale-id", contentSignature = items[2].contentSignature, position = 0, topOffsetPx = -50)
        assertEquals(2, ChatScrollPolicy.resolveAnchorPosition(bySignature, items))

        val byFallback = ChatScrollAnchor(messageId = "missing", contentSignature = "missing", position = 99, topOffsetPx = 0)
        assertEquals(2, ChatScrollPolicy.resolveAnchorPosition(byFallback, items))
    }

    @Test
    fun resolveTopOffsetKeepsExactOffsetOnBackgroundDeltaAndAdaptsToResizeOrRotation() {
        val anchor = ChatScrollAnchor(
            messageId = "chat-1-assistant-r1",
            position = 1,
            topOffsetPx = -600,
            mode = ChatScrollMode.READING_LOCKED,
            distanceFromBottomPx = 900,
            itemHeightPx = 1800,
            viewportWidthPx = 1080,
            fontScale = 1.0f,
            charOffset = 350
        )
        // Same viewport width and font scale (e.g., background delta grew the bubble from 1800px to 2600px):
        // topOffsetPx must stay at -600px so the visible text does not jump.
        assertEquals(
            -600,
            ChatScrollPolicy.resolveTopOffsetPx(
                anchor = anchor,
                currentItemHeightPx = 2600,
                currentViewportWidthPx = 1080,
                currentFontScale = 1.0f,
                charLineTopPx = 720
            )
        )
        // Font scale changed (1.0 -> 1.3): prefers exact character line top when available.
        assertEquals(
            -780,
            ChatScrollPolicy.resolveTopOffsetPx(
                anchor = anchor,
                currentItemHeightPx = 2340,
                currentViewportWidthPx = 1080,
                currentFontScale = 1.3f,
                charLineTopPx = 780
            )
        )
        // Viewport width changed (rotation 1080 -> 1920) without line layout fallback: scales proportionally.
        assertEquals(
            -400,
            ChatScrollPolicy.resolveTopOffsetPx(
                anchor = anchor,
                currentItemHeightPx = 1200,
                currentViewportWidthPx = 1920,
                currentFontScale = 1.0f,
                charLineTopPx = null
            )
        )
    }

    @Test
    fun actualDistanceFromBottomUsesRealLastItemBottomAndHandlesOffscreenTail() {
        // Middle of tall last item: bottom at 2100px in an 800px viewport (paddingBottom=12, margin=8) -> 2108 - 788 = 1320px.
        assertEquals(
            1320,
            ChatScrollPolicy.actualDistanceFromBottomPx(
                itemCount = 10,
                lastVisiblePosition = 9,
                viewportHeightPx = 800,
                paddingBottomPx = 12,
                lastItemBottomPx = 2100,
                lastItemBottomMarginPx = 8,
                scrollbarRemainingPx = 0,
                maxFollowDistancePx = 64
            )
        )
        // At the very bottom of the last item -> 0px.
        assertEquals(
            0,
            ChatScrollPolicy.actualDistanceFromBottomPx(
                itemCount = 10,
                lastVisiblePosition = 9,
                viewportHeightPx = 800,
                paddingBottomPx = 12,
                lastItemBottomPx = 780,
                lastItemBottomMarginPx = 8,
                scrollbarRemainingPx = 0,
                maxFollowDistancePx = 64
            )
        )
        // Last item not visible at all (lastVisiblePosition = 6 of 10), even if scrollbarRemainingPx is 0 in Robolectric.
        assertTrue(
            ChatScrollPolicy.actualDistanceFromBottomPx(
                itemCount = 10,
                lastVisiblePosition = 6,
                viewportHeightPx = 800,
                paddingBottomPx = 12,
                lastItemBottomPx = null,
                scrollbarRemainingPx = 0,
                maxFollowDistancePx = 64
            ) > 64
        )
    }
}
