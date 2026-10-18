package com.example.openaichat

import org.json.JSONObject
import kotlin.math.abs

enum class JumpButtonTarget {
    LAST_MESSAGE_START,
    CHAT_BOTTOM
}

enum class ChatScrollMode {
    FOLLOW_LATEST,
    READING_LOCKED
}

data class ChatScrollAnchor(
    val messageId: String,
    val contentSignature: String = "",
    val position: Int,
    val topOffsetPx: Int,
    val mode: ChatScrollMode = ChatScrollMode.FOLLOW_LATEST,
    val distanceFromBottomPx: Int = 0,
    val itemHeightPx: Int = 0,
    val viewportWidthPx: Int = 0,
    val fontScale: Float = 1f,
    val charOffset: Int = 0
) {
    val isReadingLocked: Boolean
        get() = mode == ChatScrollMode.READING_LOCKED

    fun toJson(): JSONObject = JSONObject()
        .put("message_id", messageId.takeIf { it.length <= ChatScrollPolicy.MAX_SAVED_ANCHOR_ID_CHARS }.orEmpty())
        .put("content_signature", contentSignature.takeIf { it.length <= ChatScrollPolicy.MAX_SAVED_ANCHOR_SIGNATURE_CHARS }.orEmpty())
        .put("position", position)
        .put("top_offset_px", topOffsetPx)
        .put("mode", mode.name)
        .put("distance_from_bottom_px", distanceFromBottomPx)
        .put("item_height_px", itemHeightPx)
        .put("viewport_width_px", viewportWidthPx)
        .put("font_scale", fontScale.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 1.0)
        .put("char_offset", charOffset)

    companion object {
        fun fromJson(json: JSONObject): ChatScrollAnchor? {
            val messageId = JsonProtocol.string(json, "message_id")
                .takeIf { it.length <= ChatScrollPolicy.MAX_SAVED_ANCHOR_ID_CHARS }.orEmpty()
            val position = json.optInt("position", -1)
            if (messageId.isBlank() && position < 0) return null
            val rawMode = JsonProtocol.string(json, "mode")
            val mode = ChatScrollMode.entries.firstOrNull { it.name == rawMode }
                ?: ChatScrollMode.FOLLOW_LATEST
            val fontScale = json.optDouble("font_scale", 1.0).toFloat()
                .takeIf { it.isFinite() && it > 0f } ?: 1f
            return ChatScrollAnchor(
                messageId = messageId,
                contentSignature = JsonProtocol.string(json, "content_signature")
                    .takeIf { it.length <= ChatScrollPolicy.MAX_SAVED_ANCHOR_SIGNATURE_CHARS }.orEmpty(),
                position = position.coerceAtLeast(0),
                topOffsetPx = json.optInt("top_offset_px", 0),
                mode = mode,
                distanceFromBottomPx = json.optInt("distance_from_bottom_px", 0).coerceAtLeast(0),
                itemHeightPx = json.optInt("item_height_px", 0).coerceAtLeast(0),
                viewportWidthPx = json.optInt("viewport_width_px", 0).coerceAtLeast(0),
                fontScale = fontScale,
                charOffset = json.optInt("char_offset", 0).coerceAtLeast(0)
            )
        }
    }
}

/**
 * Decides whether a chat list should follow the newest item, where the
 * two-stage jump button should scroll, and how per-chat reading anchors
 * resolve across AsyncListDiffer commits, tall bubbles, and recreation.
 */
object ChatScrollPolicy {
    // Saved reading hints are disposable UI metadata, never the source of chat
    // content. Keep their complete JSON well below Android's shared Bundle limit.
    const val MAX_SAVED_ANCHORS = 128
    const val MAX_SAVED_ANCHOR_ID_CHARS = 256
    const val MAX_SAVED_ANCHOR_SIGNATURE_CHARS = 64
    const val MAX_SAVED_ANCHORS_JSON_CHARS = 65_536
    private const val MAX_SAVED_ANCHORS_JSON_DEPTH = 4

    fun isValidSavedChatId(chatId: String): Boolean =
        chatId.length <= MAX_SAVED_ANCHOR_ID_CHARS && chatId.isNotBlank()

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

    fun messageIdentity(index: Int, user: Boolean, message: JSONObject?): String {
        val role = if (user) "user" else "assistant"
        val requestId = message?.let { JsonProtocol.nonBlank(it, "request_id") }
        val responseId = message?.let { JsonProtocol.nonBlank(it, "response_id") }
        val token = requestId ?: responseId
        return if (token != null) {
            "chat-$index-$role-$token"
        } else {
            "chat-$index-$role"
        }
    }

    fun activeAssistantIdentity(requestId: String?, responseId: String?, index: Int): String {
        val token = requestId?.takeIf { it.isNotBlank() && it != "null" }
            ?: responseId?.takeIf { it.isNotBlank() && it != "null" }
        return if (token != null) {
            "chat-$index-assistant-$token"
        } else {
            "chat-$index-assistant"
        }
    }

    fun resolveAnchorPosition(anchor: ChatScrollAnchor, items: List<ChatBubbleItem>): Int {
        if (items.isEmpty()) return 0
        if (anchor.messageId.isNotBlank()) {
            val exactIndex = items.indexOfFirst { it.id == anchor.messageId }
            if (exactIndex >= 0) return exactIndex
        }
        if (anchor.contentSignature.isNotBlank()) {
            val boundedPos = anchor.position.coerceIn(0, items.lastIndex)
            if (items[boundedPos].contentSignature == anchor.contentSignature) {
                return boundedPos
            }
            val sigIndex = items.indexOfFirst { it.contentSignature == anchor.contentSignature }
            if (sigIndex >= 0) return sigIndex
        }
        return anchor.position.coerceIn(0, items.lastIndex)
    }

    fun resolveTopOffsetPx(
        anchor: ChatScrollAnchor,
        currentItemHeightPx: Int,
        currentViewportWidthPx: Int,
        currentFontScale: Float,
        charLineTopPx: Int? = null
    ): Int {
        if (anchor.topOffsetPx >= 0) return anchor.topOffsetPx
        val widthChanged = anchor.viewportWidthPx > 0 && currentViewportWidthPx > 0 &&
            anchor.viewportWidthPx != currentViewportWidthPx
        val fontChanged = anchor.fontScale > 0f && currentFontScale > 0f &&
            abs(anchor.fontScale - currentFontScale) > 0.01f
        if (!widthChanged && !fontChanged) {
            return anchor.topOffsetPx
        }
        if (charLineTopPx != null && charLineTopPx >= 0) {
            return -charLineTopPx
        }
        if (anchor.itemHeightPx > 0 && currentItemHeightPx > 0) {
            val scaled = (anchor.topOffsetPx.toLong() * currentItemHeightPx.toLong()) / anchor.itemHeightPx.toLong()
            return scaled.toInt()
        }
        return anchor.topOffsetPx
    }

    fun actualDistanceFromBottomPx(
        itemCount: Int,
        lastVisiblePosition: Int,
        viewportHeightPx: Int,
        paddingBottomPx: Int,
        lastItemBottomPx: Int?,
        lastItemBottomMarginPx: Int = 0,
        scrollbarRemainingPx: Int = 0,
        maxFollowDistancePx: Int = 64
    ): Int {
        if (itemCount <= 0 || viewportHeightPx <= 0) return 0
        val lastIndex = itemCount - 1
        val contentBottomPx = (viewportHeightPx - paddingBottomPx).coerceAtLeast(0)
        if (lastVisiblePosition >= lastIndex && lastItemBottomPx != null) {
            return (lastItemBottomPx + lastItemBottomMarginPx - contentBottomPx).coerceAtLeast(0)
        }
        if (lastVisiblePosition in 0 until lastIndex) {
            val minOffscreen = (lastIndex - lastVisiblePosition) * (maxFollowDistancePx + 1)
            return maxOf(scrollbarRemainingPx, minOffscreen)
        }
        return scrollbarRemainingPx.coerceAtLeast(0)
    }

    fun encodeAnchorsJson(
        anchors: Map<String, ChatScrollAnchor>,
        preferredChatId: String? = null
    ): String {
        val encoded = StringBuilder("{")
        var savedCount = 0
        fun appendAnchor(chatId: String, anchor: ChatScrollAnchor) {
            if (!isValidSavedChatId(chatId)) return
            val entry = JSONObject.quote(chatId) + ":" + anchor.toJson().toString()
            val commaChars = if (savedCount > 0) 1 else 0
            // Account for escaping and the final closing brace, rather than
            // estimating the bound from the unescaped source strings.
            if (encoded.length + commaChars + entry.length + 1 > MAX_SAVED_ANCHORS_JSON_CHARS) return
            if (savedCount > 0) encoded.append(',')
            encoded.append(entry)
            savedCount++
        }
        preferredChatId?.let { chatId -> anchors[chatId]?.let { appendAnchor(chatId, it) } }
        for ((chatId, anchor) in anchors) {
            if (savedCount >= MAX_SAVED_ANCHORS) break
            if (chatId != preferredChatId) appendAnchor(chatId, anchor)
        }
        return encoded.append('}').toString()
    }

    fun decodeAnchorsJson(raw: String?): Map<String, ChatScrollAnchor> {
        if (raw == null || raw.length > MAX_SAVED_ANCHORS_JSON_CHARS || raw.isBlank()) return emptyMap()
        if (!hasBoundedSavedAnchorJsonDepth(raw)) return emptyMap()
        val root = try { JSONObject(raw) } catch (_: Exception) { return emptyMap() }
        val out = linkedMapOf<String, ChatScrollAnchor>()
        val keys = root.keys()
        while (keys.hasNext() && out.size < MAX_SAVED_ANCHORS) {
            val chatId = keys.next()
            if (!isValidSavedChatId(chatId)) continue
            val obj = root.optJSONObject(chatId) ?: continue
            ChatScrollAnchor.fromJson(obj)?.let { out[chatId] = it }
        }
        return out
    }

    private fun hasBoundedSavedAnchorJsonDepth(raw: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in raw) {
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > MAX_SAVED_ANCHORS_JSON_DEPTH) return false
                    '}', ']' -> if (--depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString
    }
}
