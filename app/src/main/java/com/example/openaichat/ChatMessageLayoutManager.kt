package com.example.openaichat

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Selecting a response must not scroll its entire (possibly very tall) bubble into view. */
open class ChatMessageLayoutManager(context: Context) : LinearLayoutManager(context) {
    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?
    ): Boolean {
        // RecyclerView still propagates focus normally. Only its automatic focus rectangle
        // scroll is skipped; explicit scrolling, keyboard focus and editable IME fields remain.
        if (focused is TextView && focused.isInTouchMode && focused.isTextSelectable &&
            !focused.onCheckIsTextEditor()) return true
        return super.onRequestChildFocus(parent, state, child, focused)
    }
}
