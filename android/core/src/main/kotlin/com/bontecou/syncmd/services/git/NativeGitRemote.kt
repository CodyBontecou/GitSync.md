package com.bontecou.syncmd.services.git

import java.net.URI
import java.util.Locale

/** Git's native pack protocol has no credential exchange. */
data class NativeGitRemote private constructor(val url: String, val host: String, val port: Int) {
    companion object {
        fun parse(value: String): NativeGitRemote {
            val text = value.trim()
            require(text.length in 1..16384 && text.none { it.isISOControl() || it == '\\' }) { "Enter a valid Git repository URL" }
            val uri = URI(text)
            require(uri.scheme.equals("git", true) && !uri.host.isNullOrBlank()) { "Enter a git:// repository URL" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Git native transport supports public access only" }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid Git server port" }
            val path = uri.path.orEmpty()
            require(path.trim('/').isNotBlank() && path.split('/').none { it == "." || it == ".." } &&
                path.none { it.isISOControl() || it == '\\' }) { "Include a repository path without traversal or control characters" }
            val host = uri.host.lowercase(Locale.ROOT)
            val port = if (uri.port == -1) 9418 else uri.port
            val origin = URI("git", null, host, if (port == 9418) -1 else port, "", null, null).toASCIIString()
            return NativeGitRemote(URI(origin + uri.rawPath.trimEnd('/')).toASCIIString(), host, port)
        }
    }
}
