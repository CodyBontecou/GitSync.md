package com.bontecou.syncmd.services.push

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.URIish
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

data class PushSyncHint(val repository: String, val branch: String, val hint: String, val head: String?) {
    fun data() = buildMap { put("type", "gitsync.push.v1"); put("repo", repository); put("branch", branch); put("hint", hint); head?.let { put("head", it) } }
}
data class PushInstallation(val id: Long, val login: String, val organization: Boolean, val allRepositories: Boolean,
    val htmlURL: String, val suspended: Boolean, val connectedAt: Long)
data class PushLinkStart(val state: String, val url: String)

object PushSyncProtocol {
    fun hint(data: Map<String, String>): PushSyncHint {
        require(data.size <= 8 && data.all { (key, value) -> key.length <= 32 && value.length <= 4096 })
        require(data["type"] == "gitsync.push.v1")
        val repo = repositoryName(requireNotNull(data["repo"]))
        val branch = requireNotNull(data["branch"])
        require(branch.toByteArray(Charsets.UTF_8).size in 1..255 && Repository.isValidRefName("refs/heads/$branch") && branch.none { it.isISOControl() })
        val id = requireNotNull(data["hint"]).also { require(it.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
        val head = data["head"]?.also { require(it.matches(Regex("(?:[A-Fa-f0-9]{40}|[A-Fa-f0-9]{64})"))) }?.lowercase(Locale.ROOT)
        return PushSyncHint(repo, branch, id, head)
    }
    fun repositoryName(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")) && value.split('/').none { it in setOf(".", "..") })
        return value.lowercase(Locale.ROOT)
    }
    /** Remote identity only: embedded secrets, self-hosted origins and local URLs never enroll. */
    fun repositoryForRemote(value: String?): String? = runCatching {
        require(value != null && value.length <= 16384)
        val uri = URIish(value)
        val host = uri.host?.lowercase(Locale.ROOT)
        require(uri.pass == null)
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> {
                val parsed = URI(value); require(host == "github.com" && parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null && parsed.port in setOf(-1, 443))
            }
            null, "ssh" -> require(host in setOf("github.com", "ssh.github.com") && uri.user == "git")
            else -> error("Unsupported remote")
        }
        repositoryName(uri.path.orEmpty().trim('/').removeSuffix(".git"))
    }.getOrNull()
    fun registration(token: String, secret: String, repositories: List<String>, debug: Boolean): String {
        require(token.matches(Regex("[A-Za-z0-9_.:-]{32,4096}")) && secret.matches(Regex("[A-Za-z0-9-]{8,64}")))
        val names = repositories.map(::repositoryName).distinct().sorted(); require(names.size <= 200)
        return JsonObject().apply {
            addProperty("platform", "android"); addProperty("token", token); addProperty("deviceSecret", secret)
            addProperty("environment", if (debug) "development" else "production")
            add("repos", com.google.gson.JsonArray().apply { names.forEach { add(it) } })
        }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= 16384) }
    }
    fun existingAccount(value: String): String = value.trim().removePrefix("@").also {
        require(it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,98}[A-Za-z0-9])?")))
    }
    fun linkStart(raw: String, existingInstallation: Boolean = false): PushLinkStart {
        val json = json(raw); require(json["ok"]?.isJsonPrimitive == true && json["ok"].asJsonPrimitive.isBoolean && json["ok"].asBoolean)
        val state = string(json, "state").also { require(it.matches(Regex("[A-Za-z0-9_-]{32,128}"))) }
        val url = string(json, "url"); val uri = URI(url)
        require(url.length <= 4096 && uri.scheme == "https" && uri.host == "github.com" && uri.rawUserInfo == null && uri.port in setOf(-1, 443) && uri.rawFragment == null)
        val params = query(uri)
        if (existingInstallation) {
            require(uri.rawPath == "/login/oauth/authorize" && params.keys == setOf("client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "prompt"))
            require(params["state"] == state && params["client_id"]?.matches(Regex("[A-Za-z0-9._-]{8,128}")) == true)
            require(params["redirect_uri"] == "https://syncmd-push.costream.workers.dev/v1/github-app/oauth/callback")
            require(params["code_challenge"]?.matches(Regex("[A-Za-z0-9_-]{43}")) == true && params["code_challenge_method"] == "S256" && params["prompt"] == "select_account")
        } else require(uri.rawPath.matches(Regex("/apps/[a-z0-9-]+/installations/new")) && params == mapOf("state" to state))
        return PushLinkStart(state, url)
    }
    /** null means connected; only bounded codes leave the callback parser. */
    fun callback(raw: String, expectedState: String): String? {
        val uri = URI(raw)
        require(raw.length <= 4096 && uri.scheme == "syncmd" && uri.rawAuthority == "github-app" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null)
        val params = query(uri); require(params["state"] == expectedState)
        return when (params["result"]) {
            "connected" -> { require(params.keys == setOf("state", "result")); null }
            "error" -> { require(params.keys == setOf("state", "result", "error")); requireNotNull(params["error"]).also {
                require(it in setOf("cancelled", "authorization_failed", "account_owner_required", "verification_failed"))
            } }
            else -> error("Invalid callback")
        }
    }
    fun installations(raw: String): List<PushInstallation> {
        val json = json(raw); require(json["ok"]?.asJsonPrimitive?.isBoolean == true && json["ok"].asBoolean)
        val array = json["installations"].also { require(it.isJsonArray && it.asJsonArray.size() <= 50) }.asJsonArray
        val seen = mutableSetOf<Long>()
        return array.map {
            val value = it.asJsonObject
            val id = number(value, "id"); require(id > 0 && seen.add(id))
            val login = string(value, "accountLogin").also { require(it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,98}[A-Za-z0-9])?"))) }
            val type = string(value, "accountType").also { require(it in setOf("User", "Organization")) }
            val selection = string(value, "repositorySelection").also { require(it in setOf("all", "selected")) }
            val status = string(value, "status").also { require(it in setOf("active", "suspended")) }
            val connected = number(value, "connectedAt").also { require(it >= 0) }
            val html = string(value, "htmlURL"); val uri = URI(html)
            require(uri.scheme == "https" && uri.host == "github.com" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in setOf(-1, 443) &&
                uri.rawPath in setOf("/settings/installations/$id", "/organizations/$login/settings/installations/$id"))
            PushInstallation(id, login, type == "Organization", selection == "all", html, status == "suspended", connected)
        }
    }
    private fun number(json: JsonObject, key: String): Long = json[key].also { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) }.asBigDecimal.longValueExact()
    private fun string(json: JsonObject, key: String): String = json[key].also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
    private fun json(raw: String): JsonObject { require(raw.length <= 65536); return JsonParser.parseString(raw).asJsonObject }
    private fun query(uri: URI): Map<String, String> = linkedMapOf<String, String>().apply {
        uri.rawQuery?.split('&')?.forEach { pair ->
            require('=' in pair); val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('='), "UTF-8"); require(key !in this); put(key, value)
        }
    }
}
