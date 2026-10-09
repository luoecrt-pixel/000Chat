package com.example.openaichat

import org.json.JSONObject
import java.util.Locale

/** Show confirmed provider totals, never estimates or assumed zero charges. */
object RequestCostFormatter {
    fun text(source: JSONObject?): String {
        val row = source ?: return "Расход неизвестен"
        if (row.optString("cost_kind") != "provider_reported") return "Расход неизвестен"
        return reportedText(row.opt("cost_usd")?.toString())
    }

    fun reportedText(raw: String?): String {
        val amount = raw?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return "Расход неизвестен"
        return when {
            amount > 0.0 && amount < 0.0001 -> "Расход < $0.0001"
            else -> "Расход $%.4f".format(Locale.US, amount)
        }
    }
}
