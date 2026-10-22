package com.example.openaichat

import android.app.AlertDialog
import android.content.Context

object InteractiveSandboxDialog {
    /** Called only from the explicit launch action. Creating a dialog does not start JavaScript. */
    fun create(context: Context, source: String, closed: (AlertDialog) -> Unit): AlertDialog {
        val theme = AppThemePalette.byId(context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark"))
        val sandbox = InteractiveSandboxView(context, source, theme)
        val dialog = AppThemeViews.dialog(context, theme).setTitle("Офлайн-песочница")
            .setView(sandbox).setPositiveButton("Закрыть", null).create()
        dialog.setOnShowListener { sandbox.start() }
        dialog.setOnDismissListener { sandbox.close(); closed(dialog) }
        return dialog
    }
}
