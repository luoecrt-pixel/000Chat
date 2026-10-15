package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Paths still owned by persisted chat messages or saved (including inactive) composers.
 * A wire representation belongs to the attachment just as its original and text copy do.
 */
object LocalAttachmentReferences {
    fun openRouterPaths(manifest: JSONArray?): Set<String> {
        val result = linkedSetOf<String>()
        if (manifest == null) return result
        for (i in 0 until manifest.length()) {
            val item = manifest.optJSONObject(i) ?: continue
            for (key in listOf("path", "text_path", "wire_path")) {
                JsonProtocol.nonBlank(item, key)?.let(result::add)
            }
        }
        return result
    }

    fun composerPaths(json: JSONObject): Set<String> {
        val result = linkedSetOf<String>()
        json.optJSONArray("file_paths")?.let { paths ->
            for (i in 0 until paths.length()) {
                JsonProtocol.string(paths, i).takeIf { it.isNotBlank() }?.let(result::add)
            }
        }
        JsonProtocol.string(json, "image_path").takeIf { it.isNotBlank() }?.let(result::add)
        result.addAll(openRouterPaths(json.optJSONArray("openrouter_attachments")))
        result.addAll(openRouterPaths(json.optJSONArray("pending_attachments")))
        return result
    }

    fun allComposerPaths(preferences: Map<String, *>): Set<String> {
        val result = linkedSetOf<String>()
        for ((key, value) in preferences) {
            if (!key.startsWith("composer_state_") || value !is String) continue
            val json = try { JSONObject(value) } catch (_: Exception) { continue }
            result.addAll(composerPaths(json))
        }
        return result
    }

    /**
     * File ownership is checked separately by the caller before deletion.
     * Compare both raw and canonical paths so Android symlink aliases (/data/data
     * vs /data/user/0) never cause a referenced attachment to be treated as an orphan.
     * Fail closed if canonical resolution fails.
     */
    fun isOrphanOldEnough(
        path: String,
        lastModifiedMs: Long,
        nowMs: Long,
        referencedPaths: Set<String>,
        retentionMs: Long = 7L * 24 * 60 * 60 * 1000
    ): Boolean {
        if (path.isBlank() || lastModifiedMs <= 0L || nowMs < lastModifiedMs) return false
        if (nowMs - lastModifiedMs < retentionMs) return false
        if (path in referencedPaths) return false
        val candidateCanonical = try {
            File(path).canonicalPath
        } catch (_: Exception) {
            return false
        }
        if (candidateCanonical in referencedPaths) return false
        for (referenced in referencedPaths) {
            if (referenced.isBlank()) continue
            val refCanonical = try {
                File(referenced).canonicalPath
            } catch (_: Exception) {
                return false
            }
            if (candidateCanonical == refCanonical) return false
        }
        return true
    }
}
