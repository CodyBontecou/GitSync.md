package com.bontecou.syncmd.services.git

import org.eclipse.jgit.transport.URIish
import java.net.IDN
import java.net.URI
import java.util.Locale

/** SSH paths retain their absolute/relative meaning; SCP and ssh:// are not blindly interchanged. */
data class SshRemote private constructor(val url: String, val host: String, val port: Int, val user: String, val path: String) {
    companion object {
        fun parse(value: String): SshRemote {
            val supplied = value.trim()
            val text = if (supplied.startsWith("git+ssh://", true)) "ssh://" + supplied.substring("git+ssh://".length) else supplied
            require(text.length in 1..16384 && text.none { it.isISOControl() || it == '\\' || it == '?' || it == '#' }) { "Enter an SSH repository address without query parameters or control characters" }
            // JGit 5's SCP parser splits bracketed IPv6 at the first colon.
            val ipv6Scp = Regex("^([^@/:]+)@\\[([^]]+)]:(.+)$").matchEntire(text)
            val parsed = if (ipv6Scp != null) URIish().setUser(ipv6Scp.groupValues[1])
                .setHost(ipv6Scp.groupValues[2]).setPath(ipv6Scp.groupValues[3]) else URIish(text)
            require(parsed.scheme == null || parsed.scheme.equals("ssh", true)) { "Use ssh:// or user@host:path for SSH" }
            require(parsed.pass == null) { "Enter the SSH passphrase separately from the address" }
            if (parsed.scheme != null) {
                val uri = URI(text)
                require(uri.rawUserInfo?.contains(':') != true && uri.rawQuery == null && uri.rawFragment == null) { "Enter the SSH passphrase separately from the address" }
            }
            val user = parsed.user ?: error("Include an SSH username, such as git@host:owner/repo.git")
            require(user.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"))) { "Enter a valid SSH username" }
            val rawHost = parsed.host?.removeSurrounding("[", "]") ?: error("Include the SSH server hostname")
            val host = if (':' in rawHost) {
                require(URI("ssh://[$rawHost]").host != null) { "Enter a valid IPv6 hostname" }; rawHost.lowercase(Locale.ROOT)
            } else IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            require(host.isNotBlank()) { "Include the SSH server hostname" }
            require(parsed.port == -1 || parsed.port in 1..65535) { "Invalid SSH port" }
            // URIish strips the slash before /~ paths. Keep the reviewed URI
            // path here; the pack-command bridge applies Git's tilde convention.
            val path = (if (parsed.scheme != null) URI(text).path else parsed.path) ?: error("Include the repository path")
            require(path.isNotBlank() && path !in listOf("/", "~", "~/") && path.split('/').none { it == "." || it == ".." } && path.none { it.isISOControl() }) { "Include a repository path without traversal or control characters" }
            val port = if (parsed.port == -1) 22 else parsed.port
            val authority = if (':' in host) "[$host]" else host
            // JGit cannot open bracketed SCP IPv6 directly. Git's /~/ URI
            // convention preserves a home-relative path when expressing it as URI.
            val canonicalPath = if (ipv6Scp != null && !path.startsWith('/')) {
                if (path.startsWith('~')) "/$path" else "/~/$path"
            } else path
            val canonical = if (parsed.scheme == null && ipv6Scp == null) "$user@$authority:$path"
                else URI("ssh", user, host, if (port == 22) -1 else port, canonicalPath, null, null).toASCIIString()
            return SshRemote(canonical, host, port, user, canonicalPath)
        }
    }
}

data class GitRemote(val url: String, val host: String, val port: Int, val isSsh: Boolean, val isNativeGit: Boolean = false, val isLocalFile: Boolean = false, val isPlainHttp: Boolean = false) {
    val isHttps: Boolean get() = !isSsh && !isNativeGit && !isLocalFile && !isPlainHttp
    val isHttpTransport: Boolean get() = isHttps || isPlainHttp
    val usesNoNetworkCredentials: Boolean get() = isNativeGit || isLocalFile
    companion object {
        /** Shorthand is an explicit form input, never an interpretation of a stored relative filesystem remote. */
        fun parseInput(value: String): GitRemote = try {
            val text = value.trim()
            require(text.length in 1..16384) { "Enter a repository URL or owner/repo" }
            val parts = text.removeSuffix("/").split('/')
            val shorthand = parts.size == 2 && !text.startsWith('/') && parts.all { part ->
                part.isNotBlank() && part.none { it.isWhitespace() || it.isISOControl() || it in "\\:@?#%" }
            }
            if (shorthand) {
                val (owner, name) = parts
                require(owner !in listOf(".", "..") && name.removeSuffix(".git") !in listOf("", ".", "..")) { "Enter a GitHub owner and repository name" }
                val target = "/$owner/${if (name.endsWith(".git")) name else "$name.git"}"
                parse(URI("https", null, "github.com", -1, target, null, null).toASCIIString())
            } else parse(text)
        } catch (_: java.net.URISyntaxException) {
            // URI/URIish diagnostics otherwise repeat the supplied address, including malformed inline credentials.
            throw IllegalArgumentException("Enter a valid repository URL or owner/repo without inline credentials")
        }
        fun parse(value: String): GitRemote = if (value.trim().startsWith("https://", true) || value.trim().startsWith("http://", true)) HttpRemote.parse(value).let { GitRemote(it.url, it.host, it.port, false, isPlainHttp = it.scheme == "http") }
            else if (value.trim().startsWith("git://", true)) NativeGitRemote.parse(value).let { GitRemote(it.url, it.host, it.port, false, true) }
            else if (value.trim().startsWith("file:", true)) FileRemote.parse(value).let { GitRemote(it.url, "", -1, false, false, true) }
            else SshRemote.parse(value).let { GitRemote(it.url, it.host, it.port, true) }
    }
}
