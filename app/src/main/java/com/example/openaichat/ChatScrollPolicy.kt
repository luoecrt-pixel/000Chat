package com.example.openaichat

/**
 * Decides whether a chat list should follow the newest item.
 * The policy is pure so boundary cases can be covered without Android UI tests.
 */
object ChatScrollPolicy {
    fun shouldScrollToLatest(
        itemCount: Int,
        lastVisiblePosition: Int,
        force: Boolean = false,
        nearBottomItemThreshold: Int = 2
    ): Boolean {
        if (force || itemCount <= 0 || lastVisiblePosition < 0) return true
        if (lastVisiblePosition >= itemCount - 1) return true
        return itemCount - 1 - lastVisiblePosition <= nearBottomItemThreshold.coerceAtLeast(0)
    }
}
