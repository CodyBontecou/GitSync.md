package com.bontecou.syncmd.services.github

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the repo picker learns about a repository from the GitHub contents API, before cloning:
 * whether it holds an Obsidian vault, whether its files are mostly Markdown, and whether it uses Git LFS.
 */
data class RepoNoteSignals(val obsidian: Boolean, val markdownFiles: Int, val otherFiles: Int, val lfs: Boolean) {
    /** "Looks like notes": an `.obsidian/` folder, or a tree that is mostly `.md`. */
    val looksLikeNotes: Boolean get() = obsidian || markdownFiles >= 2 && markdownFiles >= otherFiles
}

/** Picker reads beyond [GitHubClient]: starred repositories and metadata-only notes detection. */
object GitHubRepoInsights {
    private const val BASE = "https://api.github.com"
    private val MARKDOWN = setOf("md", "markdown", "mdx")

    /** Repositories the user starred (most recent first), up to 500. */
    suspend fun fetchStarred(token: String): List<GitHubRepo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<GitHubRepo>()
        var page = 1
        while (page <= 5) {
            val arr = JSONArray(read("$BASE/user/starred?per_page=100&page=$page", token))
            for (i in 0 until arr.length()) result += repo(arr.getJSONObject(i))
            if (arr.length() < 100) break
            page++
        }
        result
    }

    /** Lists the root (and, for a sparse root, a few folders) of [repo]'s default branch; downloads no file except `.gitattributes`. */
    suspend fun signals(token: String, repo: GitHubRepo): RepoNoteSignals = withContext(Dispatchers.IO) {
        val (owner, name) = repo.fullName.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val root = contents(token, owner, name, "") ?: return@withContext RepoNoteSignals(false, 0, 0, false)
        var obsidian = false; var markdown = 0; var other = 0; var attributes = false
        val folders = mutableListOf<String>()
        fun count(entries: JSONArray) {
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val entryName = entry.optString("name"); val type = entry.optString("type")
                if (type == "dir" && entryName == ".obsidian") obsidian = true
                if (entryName.startsWith('.')) { if (type == "file" && entryName == ".gitattributes") attributes = true; continue }
                when (type) {
                    "file" -> if (entryName.substringAfterLast('.', "").lowercase() in MARKDOWN) markdown++ else other++
                    "dir" -> folders += entry.optString("path")
                }
            }
        }
        count(root)
        // A vault often keeps its notes in folders; look one level down when the root says little.
        if (!obsidian && markdown + other < 4) folders.take(3).forEach { folder -> contents(token, owner, name, folder)?.let(::count) }
        val lfs = attributes && runCatching {
            val file = JSONObject(read("$BASE/repos/${seg(owner)}/${seg(name)}/contents/.gitattributes", token))
            String(java.util.Base64.getMimeDecoder().decode(file.optString("content")), Charsets.UTF_8).contains("filter=lfs")
        }.getOrDefault(false)
        RepoNoteSignals(obsidian, markdown, other, lfs)
    }

    private fun contents(token: String, owner: String, repo: String, path: String): JSONArray? {
        val suffix = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { seg(it) }
        val text = try { read("$BASE/repos/${seg(owner)}/${seg(repo)}/contents/$suffix", token) }
            catch (e: GitHubApiException) { if (e.status == 404) return null else throw e }
        return if (text.trimStart().startsWith("[")) JSONArray(text) else null
    }

    internal fun repo(j: JSONObject) = GitHubRepo(
        id = j.getLong("id"), name = j.getString("name"), fullName = j.getString("full_name"),
        description = j.optString("description").takeIf { it.isNotEmpty() && it != "null" },
        isPrivate = j.optBoolean("private"), htmlUrl = j.optString("html_url"), defaultBranch = j.optString("default_branch", "main"),
        updatedAt = j.optString("updated_at").ifEmpty { null }, ownerLogin = j.getJSONObject("owner").getString("login"),
        language = j.optString("language").takeIf { it.isNotEmpty() && it != "null" }, stargazersCount = j.optInt("stargazers_count", 0),
        sizeKilobytes = j.optLong("size", -1).takeIf { it >= 0 }, pushedAt = j.optString("pushed_at").takeIf { it.isNotEmpty() && it != "null" },
    )

    private fun seg(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun read(url: String, token: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        try {
            val status = conn.responseCode
            if (status !in 200..299) throw GitHubApiException(status)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
}

/** Notes detection results, kept until the repository is pushed again. */
class RepoNoteSignalCache(context: Context) {
    private val prefs = context.getSharedPreferences("repo_note_signals", Context.MODE_PRIVATE)
    private fun stamp(repo: GitHubRepo) = repo.pushedAt ?: repo.updatedAt ?: ""

    fun get(repo: GitHubRepo): RepoNoteSignals? = runCatching {
        val json = JSONObject(prefs.getString(repo.fullName, null) ?: return null)
        if (json.optString("stamp") != stamp(repo)) return null
        RepoNoteSignals(json.getBoolean("obsidian"), json.getInt("md"), json.getInt("other"), json.getBoolean("lfs"))
    }.getOrNull()

    fun put(repo: GitHubRepo, signals: RepoNoteSignals) {
        prefs.edit().putString(repo.fullName, JSONObject().put("stamp", stamp(repo)).put("obsidian", signals.obsidian)
            .put("md", signals.markdownFiles).put("other", signals.otherFiles).put("lfs", signals.lfs).toString()).apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
