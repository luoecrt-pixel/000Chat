package com.example.openaichat

import org.json.JSONArray

/** Workflow gate for the exact immutable PR head; missing or skipped checks are not success. */
object GitHubMergeCheckPolicy {
    fun required(files: List<String>): Set<String> {
        require(files.isNotEmpty() && files.size <= 100) { "Complete PR file list required" }
        if (files.all { it.endsWith(".md", ignoreCase = true) }) return setOf("Verify handoff")
        return buildSet {
            add("Build Android APK")
            add("Build Android Release")
            if (files.any { it == "NEXT_AGENT_HANDOFF.md" || it.startsWith("tools/") }) {
                add("Verify handoff")
            }
        }
    }

    fun check(headSha: String, requiredNames: Set<String>, runs: JSONArray): String {
        val verified = mutableListOf<String>()
        for (name in requiredNames) {
            val event = if (name == "Verify handoff") "pull_request" else "push"
            var latestId = -1L
            var latestStatus = ""
            var latestConclusion = ""
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                if (run.optString("head_sha") != headSha ||
                    run.optString("name") != name ||
                    run.optString("event") != event
                ) continue
                val id = run.optLong("id", -1)
                if (id <= latestId) continue
                latestId = id
                latestStatus = run.optString("status")
                latestConclusion = run.optString("conclusion")
            }
            require(latestId > 0 && latestStatus == "completed" && latestConclusion == "success") {
                "$name on head $headSha: expected successful $event run, latest was $latestId ($latestStatus/$latestConclusion)"
            }
            verified.add("$name #$latestId")
        }
        return verified.joinToString("; ")
    }
}
