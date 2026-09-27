package com.example.openaichat

import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubToolExecutor(
    private val secureStore: SecureStore,
    private val allowedRepository: () -> String,
    private val allowedBranches: () -> Set<String>,
    private val confirmWrite: (toolName: String, arguments: JSONObject) -> Boolean,
    private val auditWrite: (toolName: String, arguments: JSONObject) -> Unit
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun execute(name: String, arguments: JSONObject): String = try {
        when (name) {
            "get_pull_request" -> getPullRequest(arguments)
            "list_pull_requests" -> listPullRequests(arguments)
            "list_issues" -> listIssues(arguments)
            "get_workflow_runs" -> getWorkflowRuns(arguments)
            "get_workflow_run_jobs" -> getWorkflowRunJobs(arguments)
            "get_file" -> getFile(arguments)
            "list_directory" -> listDirectory(arguments)
            "create_issue_comment" -> createIssueComment(arguments)
            "submit_pull_request_review" -> submitPullRequestReview(arguments)
            "merge_pull_request" -> mergePullRequest(arguments)
            "create_or_update_file" -> createOrUpdateFile(arguments)
            "trigger_workflow_dispatch" -> triggerWorkflowDispatch(arguments)
            else -> errorResult("Unknown GitHub tool: $name")
        }
    } catch (e: Exception) {
        errorResult(e.message ?: e.javaClass.simpleName)
    }

    private fun token(): String =
        secureStore.getGitHubToken().takeIf { it.isNotBlank() }
            ?: throw IOException("GitHub PAT is not configured")

    private fun repo(a: JSONObject): Pair<String, String> {
        val owner = a.optString("owner").trim()
        val name = a.optString("repo").trim()
        if (owner.isBlank() || name.isBlank()) throw IOException("owner/repo are required")
        if ("$owner/$name" != allowedRepository()) throw IOException("Repository is outside the configured allow-list")
        return owner to name
    }

    private fun branch(value: String) {
        if (value.isBlank() || value !in allowedBranches()) {
            throw IOException("Branch '$value' is outside the configured allow-list")
        }
    }

    private fun url(owner: String, repo: String, path: String) =
        "https://api.github.com/repos/$owner/$repo/$path"

    private fun request(url: String): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer ${token()}")
        .addHeader("Accept", "application/vnd.github+json")
        .addHeader("X-GitHub-Api-Version", "2022-11-28")

    private fun call(request: Request): Pair<Int, String> {
        var attempt = 0
        var code = 0
        var body = ""
        while (attempt < 3) {
            httpClient.newCall(request).execute().use { httpResponse ->
                code = httpResponse.code
                body = httpResponse.body?.string().orEmpty()
                if (code != 403 && code != 429) return code to body
                val retryMs = httpResponse.header("Retry-After")?.toLongOrNull()
                    ?.coerceIn(0L, 10L)?.times(1000L) ?: 750L * (attempt + 1)
                Thread.sleep(retryMs)
            }
            attempt++
        }
        return code to body
    }

    private fun ok(result: Pair<Int, String>): String {
        if (result.first !in 200..299) {
            throw IOException("GitHub HTTP ${result.first}: ${result.second.take(1600)}")
        }
        return result.second
    }

    private fun getPullRequest(a: JSONObject): String {
        val (owner, repo) = repo(a)
        return json(ok(call(request(url(owner, repo, "pulls/${a.getInt("pull_number")}")).get().build())))
    }

    private fun listPullRequests(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val state = a.getString("state")
        val path = "pulls?state=${Uri.encode(state)}&per_page=20"
        return json(ok(call(request(url(owner, repo, path)).get().build())))
    }

    private fun listIssues(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val state = a.getString("state")
        val path = "issues?state=${Uri.encode(state)}&per_page=50"
        return json(ok(call(request(url(owner, repo, path)).get().build())))
    }

    private fun getWorkflowRuns(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val branchName = a.getString("branch")
        branch(branchName)
        val path = "actions/runs?branch=${Uri.encode(branchName)}&per_page=20"
        return json(ok(call(request(url(owner, repo, path)).get().build())))
    }

    private fun getWorkflowRunJobs(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val path = "actions/runs/${a.getLong("run_id")}/jobs?per_page=100"
        return json(ok(call(request(url(owner, repo, path)).get().build())))
    }

    private fun getFile(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val path = a.getString("path").trim()
        if (path.isBlank() || path.contains("..")) throw IOException("Invalid path")
        val ref = a.optString("ref").trim()
        if (ref.isNotBlank()) branch(ref)
        val suffix = if (ref.isBlank()) "" else "?ref=${Uri.encode(ref)}"
        val raw = ok(call(request(url(owner, repo, "contents/${Uri.encode(path, "/")}$suffix")).get().build()))
        val obj = JSONObject(raw)
        val encoded = obj.optString("content").replace("\\n", "").replace("\n", "")
        val decoded = try { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "" }
        return JSONObject().put("ok", true).put("path", path).put("sha", obj.optString("sha"))
            .put("content", decoded.take(40000)).put("truncated", decoded.length > 40000).toString()
    }

    private fun listDirectory(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val path = a.getString("path").trim().removePrefix("/")
        if (path.contains("..")) throw IOException("Invalid path")
        val ref = a.optString("ref").trim()
        if (ref.isNotBlank()) branch(ref)
        val suffix = if (ref.isBlank()) "" else "?ref=${Uri.encode(ref)}"
        val raw = ok(call(request(url(owner, repo, "contents/${Uri.encode(path, "/")}$suffix")).get().build()))
        val entries = try { JSONArray(raw) } catch (_: Exception) { JSONArray().put(JSONObject(raw)) }
        return JSONObject().put("ok", true).put("entries", entries).toString()
    }

    private fun createIssueComment(a: JSONObject): String {
        val (owner, repo) = repo(a)
        requireConfirmation("create_issue_comment", a)
        auditWrite("create_issue_comment", sanitized(a))
        val path = "issues/${a.getInt("issue_number")}/comments"
        val raw = ok(call(request(url(owner, repo, path)).post(
            JSONObject().put("body", a.getString("body")).toString().toRequestBody(JSON)
        ).build()))
        return json(raw)
    }

    private fun submitPullRequestReview(a: JSONObject): String {
        val (owner, repo) = repo(a)
        requireConfirmation("submit_pull_request_review", a)
        val body = JSONObject().put("event", a.getString("event"))
        a.optString("body").takeIf { it.isNotBlank() }?.let { body.put("body", it) }
        auditWrite("submit_pull_request_review", sanitized(a))
        val raw = ok(call(request(url(owner, repo, "pulls/${a.getInt("pull_number")}/reviews")).post(body.toString().toRequestBody(JSON)).build()))
        return json(raw)
    }

    private fun mergePullRequest(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val number = a.getInt("pull_number")
        val pr = JSONObject(ok(call(request(url(owner, repo, "pulls/$number")).get().build())))
        val base = pr.optJSONObject("base")?.optString("ref").orEmpty()
        branch(base)
        requireConfirmation("merge_pull_request", a)
        auditWrite("merge_pull_request", sanitized(a).put("base_branch", base))
        val raw = ok(call(request(url(owner, repo, "pulls/$number/merge")).put(
            JSONObject().put("merge_method", a.getString("merge_method")).toString().toRequestBody(JSON)
        ).build()))
        return json(raw)
    }

    private fun createOrUpdateFile(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val path = a.getString("path").trim().removePrefix("/")
        val branchName = a.getString("branch").trim()
        val content = a.getString("content_base64")
        branch(branchName)
        if (path.isBlank() || path.contains("..")) throw IOException("Invalid path")
        var sha = a.optString("sha").trim().takeIf { it.isNotBlank() }
        if (sha == null) {
            val lookup = call(request(url(owner, repo, "contents/${Uri.encode(path, "/")}?ref=${Uri.encode(branchName)}")).get().build())
            if (lookup.first in 200..299) sha = JSONObject(lookup.second).optString("sha").takeIf { it.isNotBlank() }
            else if (lookup.first != 404) throw IOException("GitHub HTTP ${lookup.first}: ${lookup.second.take(1600)}")
        }
        requireConfirmation("create_or_update_file", a)
        val body = JSONObject().put("message", a.getString("message")).put("content", content).put("branch", branchName)
        sha?.let { body.put("sha", it) }
        auditWrite("create_or_update_file", sanitized(a).put("resolved_sha", sha ?: JSONObject.NULL).put("content_length_base64", content.length))
        return json(ok(call(request(url(owner, repo, "contents/${Uri.encode(path, "/")}")).put(body.toString().toRequestBody(JSON)).build())))
    }

    private fun triggerWorkflowDispatch(a: JSONObject): String {
        val (owner, repo) = repo(a)
        val workflowId = a.getString("workflow_id").trim()
        val ref = a.getString("ref").trim()
        branch(ref)
        requireConfirmation("trigger_workflow_dispatch", a)
        val body = JSONObject().put("ref", ref)
        a.optJSONObject("inputs")?.let { body.put("inputs", it) }
        auditWrite("trigger_workflow_dispatch", sanitized(a))
        val raw = ok(call(request(url(owner, repo, "actions/workflows/${Uri.encode(workflowId)}/dispatches")).post(body.toString().toRequestBody(JSON)).build()))
        return if (raw.isBlank()) JSONObject().put("ok", true).toString() else json(raw)
    }

    private fun requireConfirmation(name: String, args: JSONObject) {
        if (!confirmWrite(name, args)) throw IOException("User declined or confirmation is unavailable")
    }

    private fun sanitized(input: JSONObject): JSONObject = JSONObject(input.toString()).apply {
        remove("body")
        remove("content_base64")
        remove("inputs")
    }

    private fun json(raw: String): String {
        val data: Any = try { JSONObject(raw) } catch (_: Exception) {
            try { JSONArray(raw) } catch (_: Exception) { raw }
        }
        return JSONObject().put("ok", true).put("data", data).toString()
    }

    private fun errorResult(message: String) = JSONObject().put("ok", false).put("error", message).toString()
    private val JSON = "application/json".toMediaType()
}
