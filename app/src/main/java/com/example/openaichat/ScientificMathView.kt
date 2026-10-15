package com.example.openaichat

import android.content.Context
import android.widget.TextView

/** Uses only the pinned offline KaTeX renderer; the complete source remains selectable. */
class ScientificMathView(context: Context, source: String) : ScientificMathText(context,
    TextView(context).apply {
        text = source; setTextIsSelectable(true)
        setTextColor(AppThemePalette.byId(context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark")).textPrimary)
    }, autoPreview = true, document = {
        ScientificRendererDocument.math(listOf(MathDelimiterScanner.Formula(0, source.length, source, source, true)))
    })
