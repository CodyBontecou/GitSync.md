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
    val avatarUrl: String?
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
    val stargazersCount: Int
)

// ─── Service ─────────────────────────────────────────────────────────────────

object GitHubApiService {

    private const val BASE = "https://api.github.com"

    /** Fetch the authenticated user's profile. */
    suspend fun fetchUser(token: String): GitHubUser = withContext(Dispatchers.IO) {
        val json = get("$BASE/user", token)
        GitHubUser(
            login = json.getString("login"),
            name = json.optString("name").ifEmpty { null },
            email = json.optString("email").ifEmpty { null },
            avatarUrl = json.optString("avatar_url").ifEmpty { null }
        )
    }

    /**
     * Fetch the authenticated user's repositories (sorted by last updated).
     * Pages through until < 100 results are returned.
     */
    suspend fun fetchRepos(token: String): List<GitHubRepo> = withContext(Dispatchers.IO) {
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
                    stargazersCount = j.optInt("stargazers_count", 0)
                )
            }
            if (arr.length() < 100) break
            page++
        }
        result
    }

    /** Fetch the user's primary email (may not be public on their profile). */
    suspend fun fetchPrimaryEmail(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val arr = getArray("$BASE/user/emails", token)
            // Prefer the primary flag; fall back to first entry
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                if (j.optBoolean("primary", false)) return@withContext j.getString("email")
            }
            if (arr.length() > 0) return@withContext arr.getJSONObject(0).getString("email")
        } catch (_: Exception) {
        }
        null
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun get(url: String, token: String): JSONObject {
        val conn = openConn(url, token)
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    private fun getArray(url: String, token: String): JSONArray {
        val conn = openConn(url, token)
        val text = conn.inputStream.bufferedReader().readText()
        // Handle empty / unexpected responses gracefully
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray()
    }

    private fun openConn(url: String, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        val status = conn.responseCode
        if (status == 401 || status == 403) {
            throw IllegalStateException("GitHub token is invalid or expired (HTTP $status)")
        }
        if (status !in 200..299) {
            throw IllegalStateException("GitHub API error: HTTP $status")
        }
        return conn
    }
}
