package com.example.openaichat

enum class JumpButtonTarget {
    LAST_MESSAGE_START,
    CHAT_BOTTOM
}

/**
 * Decides whether a chat list should follow the newest item and where the
 * two-stage jump button should scroll.
 */
object ChatScrollPolicy {
    fun shouldScrollToLatest(
        itemCount: Int,
        lastVisiblePosition: Int,
        force: Boolean = false,
        nearBottomItemThreshold: Int = 2,
        pixelDistanceFromBottom: Int? = null,
        maxFollowDistancePx: Int = 180,
        userScrollLocked: Boolean = false
    ): Boolean {
        if (force) return true
        if (userScrollLocked) return false
        if (itemCount <= 0 || lastVisiblePosition < 0) return true
        if (pixelDistanceFromBottom != null) {
            return pixelDistanceFromBottom <= maxFollowDistancePx.coerceAtLeast(0)
        }
        if (lastVisiblePosition >= itemCount - 1) return true
        return itemCount - 1 - lastVisiblePosition <= nearBottomItemThreshold.coerceAtLeast(0)
    }

    /**
     * Two-stage scroll button policy (Task 3):
     * - If the viewport is above the start of the last message, jump to the
     *   start (top) of the last message so the user can read it from line 1.
     * - If the viewport is already at or below the start of the last message,
     *   jump to the very bottom of the chat.
     */
    fun jumpTarget(
        itemCount: Int,
        firstVisiblePosition: Int,
        lastItemTopPx: Int?,
        topThresholdPx: Int = 32
    ): JumpButtonTarget {
        if (itemCount <= 0) return JumpButtonTarget.CHAT_BOTTOM
        val lastIndex = itemCount - 1
        if (firstVisiblePosition < lastIndex) {
            if (lastItemTopPx == null || lastItemTopPx > topThresholdPx) {
                return JumpButtonTarget.LAST_MESSAGE_START
            }
        }
        return JumpButtonTarget.CHAT_BOTTOM
    }
}
