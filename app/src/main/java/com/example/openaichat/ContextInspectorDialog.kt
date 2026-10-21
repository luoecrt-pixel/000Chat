package com.example.openaichat

import android.app.AlertDialog
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView

object ContextInspectorDialog {
    fun show(context: Context, theme: ThemeColors, snapshot: ContextStatusSnapshot): AlertDialog {
        val text = TextView(context).apply {
            this.text = snapshot.details()
            textSize = 13f
            setTextColor(theme.textPrimary)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
            tag = "context-inspector-details"
        }
        return AppThemeViews.dialog(context, theme).setTitle("Контекст следующего запроса")
            .setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton("Готово", null).show()
    }
}
