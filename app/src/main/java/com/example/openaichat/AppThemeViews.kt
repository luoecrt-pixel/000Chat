package com.example.openaichat

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Shared chrome for app-owned controls; the system picker retains its Android theme. */
object AppThemeViews {
    private fun states(enabled: Int, disabled: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, enabled))

    fun surface(context: Context, fill: Int, border: Int, radius: Int = 12) = GradientDrawable().apply {
        val density = context.resources.displayMetrics.density
        cornerRadii = floatArrayOf(16f, 16f, radius.toFloat(), radius.toFloat(), 16f, 16f, radius.toFloat(), radius.toFloat())
            .map { it * density }.toFloatArray()
        setColor(fill)
        setStroke((density + .5f).toInt().coerceAtLeast(1), border)
    }

    fun button(view: TextView, theme: ThemeColors) {
        view.setTextColor(states(theme.textPrimary, theme.textSecondary))
        view.backgroundTintList = null
        view.background = RippleDrawable(ColorStateList.valueOf(theme.divider),
            surface(view.context, theme.buttonBg, theme.divider), null)
    }

    fun buttons(root: View, theme: ThemeColors) {
        if (root is Button && root !is CompoundButton) button(root, theme)
        if (root is ViewGroup) for (i in 0 until root.childCount) buttons(root.getChildAt(i), theme)
    }

    fun content(root: View, theme: ThemeColors) {
        when (root) {
            is EditText -> {
                val left = root.paddingLeft; val top = root.paddingTop
                val right = root.paddingRight; val bottom = root.paddingBottom
                val start = root.paddingStart; val end = root.paddingEnd
                val relativePadding = root.isPaddingRelative
                root.setTextColor(states(theme.textPrimary, theme.textSecondary))
                root.setHintTextColor(states(theme.hintText, theme.textSecondary))
                // A native dark underline or a background tint also recolors the input fill.
                // Keep a real EditText and let the drawable handle focus without replacing
                // focus listeners, its cursor, selection or IME/input configuration.
                root.backgroundTintList = null
                root.background = surface(root.context, theme.inputBg, theme.divider).apply {
                    val stroke = (root.resources.displayMetrics.density + .5f).toInt().coerceAtLeast(1)
                    setStroke(stroke, ColorStateList(
                        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(theme.divider, theme.accent, theme.divider)))
                }
                if (relativePadding) root.setPaddingRelative(start, top, end, bottom)
                else root.setPadding(left, top, right, bottom)
            }
            is CompoundButton -> {
                root.setTextColor(states(theme.textPrimary, theme.textSecondary))
                root.buttonTintList = states(theme.accent, theme.textSecondary)
            }
            is Button -> button(root, theme)
            is TextView -> {
                root.setTextColor(states(theme.textPrimary, theme.textSecondary))
                root.setLinkTextColor(theme.accent)
            }
            is Spinner -> {
                root.backgroundTintList = ColorStateList.valueOf(theme.accent)
                val adapter = root.adapter
                if (adapter != null && adapter !is ThemedSpinnerAdapter) {
                    val selected = root.selectedItemPosition
                    root.adapter = ThemedSpinnerAdapter(adapter, theme)
                    if (selected >= 0) root.setSelection(selected)
                }
            }
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) content(root.getChildAt(i), theme)
    }

    fun dialog(context: Context, theme: ThemeColors): AlertDialog.Builder = object : AlertDialog.Builder(
        ContextThemeWrapper(context, if (theme.isLight) android.R.style.Theme_Material_Light_Dialog_Alert
            else android.R.style.Theme_Material_Dialog_Alert)) {
        override fun show(): AlertDialog = super.show().also { applyChrome(it, context, theme) }
        override fun create(): AlertDialog = super.create().also { dialog ->
            val decor = dialog.window!!.decorView
            decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    applyChrome(dialog, context, theme)
                }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    private fun applyChrome(dialog: AlertDialog, context: Context, theme: ThemeColors) {
        dialog.window?.apply {
            setBackgroundDrawable(surface(context, theme.cardBg, theme.divider, 18))
            content(decorView, theme)
        }
    }

    private class ThemedSpinnerAdapter(private val source: SpinnerAdapter, private val theme: ThemeColors) :
        android.widget.BaseAdapter(), SpinnerAdapter {
        override fun getCount() = source.count
        override fun getItem(position: Int): Any? = source.getItem(position)
        override fun getItemId(position: Int) = source.getItemId(position)
        override fun hasStableIds() = source.hasStableIds()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            source.getView(position, convertView, parent).also { content(it, theme) }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            source.getDropDownView(position, convertView, parent).also {
                it.setBackgroundColor(theme.cardBg)
                content(it, theme)
            }
    }
}
