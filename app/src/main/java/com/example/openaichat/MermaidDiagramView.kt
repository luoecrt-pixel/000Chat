package com.example.openaichat

import android.content.Context
import android.widget.TextView

/** Model diagram text is data for the pinned renderer, never executable JavaScript. */
class MermaidDiagramView(context: Context, source: String) : ScientificMathText(context,
    TextView(context).apply {
        text = source; setTextIsSelectable(true)
        setTextColor(AppThemePalette.byId(context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark")).textPrimary)
    }, autoPreview = true, document = { ScientificRendererDocument.mermaid(source) })
