package com.bontecou.syncmd.services.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ─── Data models ────────────────────────────────────────────────────────────

data class GitHubUser(
    val login: String,
    val name: String?,
    val email: String?,
    val avatarUrl: String?,
    val id: Long? = null,
)

data class GitHubRepo(
    val id: Long,
    val name: String,
    val fullName: String,          // "owner/repo"
    val description: String?,
    val isPrivate: Boolean,
    val htmlUrl: String,
    val defaultBranch: String,
    val updatedAt: String?,
    val ownerLogin: String,
    val language: String?,
    val stargazersCount: Int,
    /** GitHub's repository size (KB, objects on disk); null when unknown (older cache). */
    val sizeKilobytes: Long? = null,
    /** Last push, ISO-8601; the picker's age falls back to [updatedAt]. */
    val pushedAt: String? = null,
)

/** An HTTP failure from the GitHub API; the UI maps [status] to localized copy. */
class GitHubApiException(val status: Int) : IllegalStateException(
    if (status == 401 || status == 403) "GitHub token is invalid or expired (HTTP $status)" else "GitHub API error: HTTP $status"
) {
    val isUnauthorized: Boolean get() = status == 401 || status == 403
}

/** What the free vault check learns about a GitHub repository before anything is cloned. */
data class GitHubRepoAccess(val canPush: Boolean?, val defaultBranch: String?, val sizeKilobytes: Long?, val isPrivate: Boolean? = null)

data class GitHubTreeSummary(val fileCount: Int, val hasObsidian: Boolean, val hasObsidianGit: Boolean,
    val gitattributesSha: String?, val truncated: Boolean)

/** Metadata-only reads for the vault check; nothing is written or downloaded beyond JSON. */
interface GitHubVaultClient {
    suspend fun repoAccess(token: String, owner: String, repo: String): GitHubRepoAccess
    /** Null for an empty repository or a branch GitHub cannot list. */
    suspend fun tree(token: String, owner: String, repo: String, branch: String): GitHubTreeSummary?
    suspend fun blobText(token: String, owner: String, repo: String, sha: String): String
}

// ─── Service ─────────────────────────────────────────────────────────────────

interface GitHubClient {
    suspend fun fetchUser(token: String): GitHubUser
    suspend fun fetchRepos(token: String): List<GitHubRepo>
    suspend fun fetchPrimaryEmail(token: String): String?
}

object GitHubApiService : GitHubClient, GitHubVaultClient {

    private const val BASE = "https://api.github.com"

    /** Fetch the authenticated user's profile. */
    override suspend fun fetchUser(token: String): GitHubUser = withContext(Dispatchers.IO) {
        val json = get("$BASE/user", token)
        GitHubUser(
            login = json.getString("login"),
            name = json.optString("name").ifEmpty { null },
            email = json.optString("email").ifEmpty { null },
            avatarUrl = json.optString("avatar_url").ifEmpty { null },
            id = json.getLong("id")
        )
    }

    /**
     * Fetch the authenticated user's repositories (sorted by last updated).
     * Pages through until < 100 results are returned.
     */
    override suspend fun fetchRepos(token: String): List<GitHubRepo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<GitHubRepo>()
        var page = 1
        while (true) {
            val url = "$BASE/user/repos" +
                    "?sort=updated&direction=desc&per_page=100&page=$page" +
                    "&affiliation=owner,collaborator,organization_member"
            val arr = getArray(url, token)
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                result += GitHubRepo(
                    id = j.getLong("id"),
                    name = j.getString("name"),
                    fullName = j.getString("full_name"),
                    description = j.optString("description").ifEmpty { null },
                    isPrivate = j.getBoolean("private"),
                    htmlUrl = j.getString("html_url"),
                    defaultBranch = j.getString("default_branch"),
                    updatedAt = j.optString("updated_at").ifEmpty { null },
                    ownerLogin = j.getJSONObject("owner").getString("login"),
                    language = j.optString("language").ifEmpty { null },
                    stargazersCount = j.optInt("stargazers_count", 0),
                    sizeKilobytes = j.optLong("size", -1).takeIf { it >= 0 },
                    pushedAt = j.optString("pushed_at").takeIf { it.isNotEmpty() && it != "null" },
                )
            }
            if (arr.length() < 100) break
            page++
        }
        result
    }

    /** Fetch the user's primary email (may not be public on their profile). */
    override suspend fun fetchPrimaryEmail(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val arr = getArray("$BASE/user/emails", token)
            // Prefer the primary flag; fall back to first entry
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                if (j.optBoolean("primary", false)) return@withContext j.getString("email")
            }
            if (arr.length() > 0) return@withContext arr.getJSONObject(0).getString("email")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) { }
        null
    }

    override suspend fun repoAccess(token: String, owner: String, repo: String): GitHubRepoAccess = withContext(Dispatchers.IO) {
        val json = get("$BASE/repos/${path(owner)}/${path(repo)}", token)
        GitHubRepoAccess(
            canPush = json.optJSONObject("permissions")?.takeIf { it.has("push") }?.getBoolean("push"),
            defaultBranch = json.optString("default_branch").ifEmpty { null },
            sizeKilobytes = json.optLong("size", -1).takeIf { it >= 0 },
            isPrivate = if (json.has("private")) json.optBoolean("private") else null,
        )
    }

    override suspend fun tree(token: String, owner: String, repo: String, branch: String): GitHubTreeSummary? = withContext(Dispatchers.IO) {
        val json = try { get("$BASE/repos/${path(owner)}/${path(repo)}/git/trees/${path(branch)}?recursive=1", token) }
            catch (e: GitHubApiException) { if (e.status == 404 || e.status == 409) return@withContext null else throw e }
        val entries = json.optJSONArray("tree") ?: JSONArray()
        var files = 0; var obsidian = false; var obsidianGit = false; var attributes: String? = null
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val name = entry.optString("path"); val type = entry.optString("type")
            if (type == "blob") files++
            if (name == ".obsidian" || name.startsWith(".obsidian/")) obsidian = true
            if (name.startsWith(".obsidian/plugins/obsidian-git/")) obsidianGit = true
            if (name == ".gitattributes" && type == "blob") attributes = entry.optString("sha").ifEmpty { null }
        }
        GitHubTreeSummary(files, obsidian, obsidianGit, attributes, json.optBoolean("truncated", false))
    }

    override suspend fun blobText(token: String, owner: String, repo: String, sha: String): String = withContext(Dispatchers.IO) {
        val json = get("$BASE/repos/${path(owner)}/${path(repo)}/git/blobs/${path(sha)}", token)
        String(java.util.Base64.getMimeDecoder().decode(json.getString("content")), Charsets.UTF_8)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun path(segment: String) = java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun get(url: String, token: String): JSONObject {
        val conn = openConn(url, token)
        return try { conn.inputStream.bufferedReader().use { JSONObject(it.readText()) } } finally { conn.disconnect() }
    }

    private fun getArray(url: String, token: String): JSONArray {
        val conn = openConn(url, token)
        val text = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
        // Handle empty / unexpected responses gracefully
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray()
    }

    private fun openConn(url: String, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        val status = conn.responseCode
        if (status !in 200..299) {
            conn.disconnect()
            throw GitHubApiException(status)
        }
        return conn
    }
}
