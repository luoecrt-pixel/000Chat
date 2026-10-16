package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Raw provider snapshots are keyed by response ID. Aggregate usage is never a response snapshot. */
object OpenAiUsageLedger {
    const val FIELD = "openai_usage_ledger"
    private val terminalStatuses = setOf("completed", "cancelled", "failed", "incomplete")

    fun empty(): JSONObject = JSONObject().put("version", 1).put("responses", JSONObject())

    /** Old checkpoints have an unattributable total; retain it rather than inventing round usages. */
    fun fromCheckpoint(checkpoint: JSONObject?): JSONObject {
        if (checkpoint == null) return empty()
        checkpoint.optJSONObject(FIELD)?.let { existing ->
            if (total(existing) != null || (checkpoint.optJSONObject("usage")?.length() ?: 0) == 0) return merge(null, existing)
        }
        val finished = checkpoint.optString("phase") == "finished"
        val finalResponse = checkpoint.optJSONObject("final_response")
        val aggregate = if (finished) finalResponse?.optJSONObject("usage") ?: checkpoint.optJSONObject("usage")
            else checkpoint.optJSONObject("usage")
        val ledger = empty()
        val valid = usageCopy(aggregate)
        if (valid.length() > 0) {
            ledger.put("legacy_usage", valid)
            val covered = linkedSetOf<String>()
            JsonProtocol.nonBlank(checkpoint, "parent_id")?.let(covered::add)
            if (finished) {
                finalResponse?.let { JsonProtocol.nonBlank(it, "id") }?.let(covered::add)
                JsonProtocol.nonBlank(checkpoint, "response_id")?.let(covered::add)
            }
            ledger.put("legacy_covered_ids", JSONArray(covered.toList()))
        }
        return ledger
    }

    /** A marked response already carries a ledger; its public usage field is a total, never raw. */
    fun resolve(checkpoint: JSONObject?, response: JSONObject?): JSONObject {
        val base = fromCheckpoint(checkpoint)
        if (response == null) return base
        response.optJSONObject(FIELD)?.let { return merge(base, it) }
        return observe(base, JsonProtocol.nonBlank(response, "id") ?: JsonProtocol.nonBlank(response, "response_id"),
            response.optJSONObject("usage"), JsonProtocol.nonBlank(response, "status"))
    }

    /** Partial snapshots cannot replace a terminal observation. Missing fields never erase known data. */
    fun observe(ledger: JSONObject?, responseId: String?, usage: JSONObject?, status: String?): JSONObject {
        val out = merge(null, ledger)
        if (responseId.isNullOrBlank()) return out
        val valid = usageCopy(usage)
        if (valid.length() == 0 || coveredIds(out).contains(responseId)) return out
        val responses = out.getJSONObject("responses")
        val previous = responses.optJSONObject(responseId)
        val terminal = status in terminalStatuses
        if (previous?.optBoolean("terminal") == true && !terminal) return out
        val previousUsage = previous?.optJSONObject("usage")
        responses.put(responseId, JSONObject().put("terminal", terminal || previous?.optBoolean("terminal") == true)
            .put("usage", combineFields(previousUsage, valid, maximum = !terminal)))
        return out
    }

    fun merge(existing: JSONObject?, incoming: JSONObject?): JSONObject {
        val out = empty()
        val legacyA = usageCopy(existing?.optJSONObject("legacy_usage"))
        val legacyB = usageCopy(incoming?.optJSONObject("legacy_usage"))
        // The same legacy base can travel through response, checkpoint and accounting. Never add it twice.
        val legacy = combineFields(legacyA, legacyB, maximum = true)
        if (legacy.length() > 0) out.put("legacy_usage", legacy)
        val covered = coveredIds(existing) + coveredIds(incoming)
        if (covered.isNotEmpty()) out.put("legacy_covered_ids", JSONArray(covered.toList().sorted()))
        fun copyEntries(source: JSONObject?) {
            val entries = source?.optJSONObject("responses") ?: return
            for (id in entries.keys().asSequence().toList().sorted()) {
                if (id in covered) continue
                val entry = entries.optJSONObject(id) ?: continue
                val valid = usageCopy(entry.optJSONObject("usage"))
                if (valid.length() == 0) continue
                val target = out.getJSONObject("responses")
                val previous = target.optJSONObject(id)
                val terminal = entry.optBoolean("terminal", false)
                if (previous?.optBoolean("terminal") == true && !terminal) continue
                target.put(id, JSONObject().put("terminal", terminal || previous?.optBoolean("terminal") == true)
                    .put("usage", combineFields(previous?.optJSONObject("usage"), valid, maximum = !terminal)))
            }
        }
        copyEntries(existing)
        copyEntries(incoming)
        return out
    }

    fun total(ledger: JSONObject?): JSONObject? {
        if (ledger == null) return null
        var result = usageCopy(ledger.optJSONObject("legacy_usage"))
        val entries = ledger.optJSONObject("responses")
        val covered = coveredIds(ledger)
        if (entries != null) for (id in entries.keys().asSequence().toList().sorted()) {
            if (id !in covered) result = add(result, entries.optJSONObject(id)?.optJSONObject("usage"))
        }
        return result.takeIf { it.length() > 0 }
    }

    fun attach(response: JSONObject, ledger: JSONObject): JSONObject = response.apply {
        val normalized = merge(null, ledger)
        total(normalized)?.let { put("usage", it) }
        put(FIELD, normalized)
    }

    private fun coveredIds(ledger: JSONObject?): Set<String> {
        val ids = ledger?.optJSONArray("legacy_covered_ids") ?: return emptySet()
        return (0 until ids.length()).mapNotNull { JsonProtocol.text(ids, it)?.takeIf(String::isNotBlank) }.toSet()
    }

    private fun usageCopy(source: JSONObject?): JSONObject = JSONObject().apply {
        if (source == null) return@apply
        source.keys().forEach { key ->
            when (val value = source.opt(key)) {
                is JSONObject -> usageCopy(value).takeIf { it.length() > 0 }?.let { put(key, it) }
                else -> value?.toString()?.toLongOrNull()?.takeIf { it >= 0L }?.let { put(key, it) }
            }
        }
    }

    private fun combineFields(first: JSONObject?, second: JSONObject?, maximum: Boolean): JSONObject {
        val out = usageCopy(first)
        val incoming = usageCopy(second)
        incoming.keys().forEach { key -> when (val value = incoming.opt(key)) {
            is JSONObject -> out.put(key, combineFields(out.optJSONObject(key), value, maximum))
            is Number -> out.put(key, if (maximum) maxOf(out.optLong(key, 0L), value.toLong()) else value.toLong())
        } }
        return out
    }

    private fun add(first: JSONObject, second: JSONObject?): JSONObject = usageCopy(first).apply {
        usageCopy(second).let { incoming -> incoming.keys().forEach { key -> when (val value = incoming.opt(key)) {
            is JSONObject -> put(key, add(optJSONObject(key) ?: JSONObject(), value))
            is Number -> {
                val old = optLong(key, 0L)
                val amount = value.toLong()
                put(key, if (Long.MAX_VALUE - old < amount) Long.MAX_VALUE else old + amount)
            }
        } } }
    }
}
