package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.net.URI
import java.util.Locale

/** Scheme is part of an HTTP repository's credential identity. */
data class HttpRemote private constructor(val url: String, val host: String, val port: Int, val path: String, val scheme: String) {
    companion object {
        fun parse(value: String): HttpRemote {
            val uri = URI(value.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme in listOf("https", "http") && !uri.host.isNullOrBlank()) { "Enter an HTTP or HTTPS repository URL" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Enter credentials separately from the repository URL" }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port" }
            require(!uri.path.isNullOrBlank() && uri.path.trim('/').isNotBlank() && uri.path.split('/').none { it == "." || it == ".." }) { "Include the repository path in the URL" }
            val host = uri.host.lowercase(Locale.ROOT)
            val defaultPort = if (scheme == "https") 443 else 80
            val origin = URI(scheme, null, host, if (uri.port == defaultPort) -1 else uri.port, "", null, null).toASCIIString()
            val normalized = URI(origin + uri.rawPath.trimEnd('/')).toASCIIString()
            return HttpRemote(normalized, host, if (uri.port == -1) defaultPort else uri.port, URI(normalized).rawPath, requireNotNull(scheme))
        }
    }
}

/** Secure-only callers keep an explicit HTTPS boundary. */
data class HttpsRemote private constructor(val url: String, val host: String, val port: Int, val path: String) {
    companion object {
        fun parse(value: String): HttpsRemote {
            val endpoint = HttpRemote.parse(value)
            require(endpoint.scheme == "https") { "Enter an HTTPS repository URL" }
            return HttpsRemote(endpoint.url, endpoint.host, endpoint.port, endpoint.path)
        }
    }
}

/** A challenge for a redirect, different repository, host, port or scheme never gets a secret. */
class ScopedCredentialsProvider(private val url: String, private val credentials: Credentials, private val remoteName: String = "origin") : CredentialsProvider(), org.eclipse.jgit.api.TransportConfigCallback {
    init {
        RemoteAuthentication.requireSafeAddress(url)
        if (url.trim().startsWith("git:", true)) require(credentials == Credentials.None) { "Git native transport supports public access only" }
        if (url.trim().startsWith("file:", true)) {
            require(credentials == Credentials.None) { "Local file URLs use device folder access without network credentials" }
            RepositoryOperationCoordinator.requireAccess(FileRemote.parse(url).path)
        }
    }
    private val endpoint = runCatching { HttpRemote.parse(url) }.getOrNull()
    private val delegate = when (credentials) {
        is Credentials.Pat -> UsernamePasswordCredentialsProvider("x-access-token", credentials.token)
        is Credentials.Basic -> UsernamePasswordCredentialsProvider(credentials.username, credentials.password)
        Credentials.None -> null
        is Credentials.Ssh -> null
    }
    override fun configure(transport: org.eclipse.jgit.transport.Transport) {
        if (url.trim().startsWith("file:", true)) RepositoryOperationCoordinator.requireAccess(FileRemote.parse(url).path)
        if (transport is org.eclipse.jgit.transport.SshTransport) {
            val endpoint = SshRemote.parse(url)
            val key = credentials as? Credentials.Ssh ?: error("Choose an SSH private key for this repository")
            transport.sshSessionFactory = RepositorySshSessionFactory(endpoint, key, SshTrust.verifier())
        }
        if (transport is org.eclipse.jgit.transport.TransportHttp && endpoint != null) {
            transport.httpConnectionFactory = EndpointHttpConnectionFactory(url, transport.httpConnectionFactory)
        }
    }
    internal fun lfsRemoteName(): String = remoteName
    internal fun lfsConnection(): Pair<String, Credentials> = url to credentials
    override fun isInteractive() = false
    override fun supports(vararg items: CredentialItem): Boolean = delegate?.supports(*items) == true
    override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
        val challenge = runCatching { HttpRemote.parse(uri.toPrivateString()) }.getOrNull()
        if (endpoint == null || challenge == null || (endpoint.scheme != challenge.scheme || endpoint.host != challenge.host || endpoint.port != challenge.port || endpoint.path != challenge.path)) return false
        return delegate?.get(uri, *items) == true
    }
}

object RemoteAuthentication {
    /** A raw URL must never supply credentials around the endpoint-bound provider. */
    fun requireSafeAddress(url: String) {
        val safe = runCatching {
            val text = url.trim()
            when {
                text.startsWith("https:", true) || text.startsWith("http:", true) -> HttpRemote.parse(text)
                text.startsWith("ssh:", true) || text.startsWith("git+ssh:", true) -> SshRemote.parse(text)
                text.startsWith("git:", true) -> NativeGitRemote.parse(text)
                text.startsWith("file:", true) -> FileRemote.parse(text)
                else -> {
                    val uri = URIish(text)
                    when {
                        uri.scheme == null && uri.host != null -> SshRemote.parse(text)
                        uri.scheme == "file" -> require(uri.user == null && uri.pass == null)
                        uri.scheme == null && uri.host == null -> require(!text.contains("://") && uri.user == null && uri.pass == null)
                        else -> error("Unsupported transport")
                    }
                }
            }
        }.isSuccess
        require(safe) { "Review the remote connection. Use a complete HTTP, HTTPS, SSH, git:// or local file:/// address with credentials stored separately." }
    }
    fun isValidBranch(branch: String) = Repository.isValidRefName("refs/heads/$branch")
    fun isValidTag(tag: String) = Repository.isValidRefName("refs/tags/$tag")
    fun provider(repository: Repository, remote: String, push: Boolean = false,
        token: (() -> String?)? = null, resolver: ((String, String) -> Credentials?)? = null): ScopedCredentialsProvider {
        val config = repository.config
        val pushUrls = if (push) config.getStringList("remote", remote, "pushurl").toList() else emptyList()
        val urls = pushUrls.ifEmpty { config.getStringList("remote", remote, "url").toList() }
        require(urls.size == 1) { "Configure one remote URL before authenticating" }
        val url = urls.single()
        requireSafeAddress(url)
        val credentials = resolver?.invoke(repository.workTree.canonicalPath, url) ?: run {
            val endpoint = runCatching { HttpsRemote.parse(url) }.getOrNull()
            if (endpoint?.host == "github.com" && endpoint.port == 443) token?.invoke()?.takeIf { it.isNotBlank() }?.let(Credentials::Pat) ?: Credentials.None
            else Credentials.None
        }
        return ScopedCredentialsProvider(url, credentials, remote)
    }
}

/** Checks every request, including requests using an already-cached Authorization header after a redirect. */
class EndpointHttpConnectionFactory(url: String, private val delegate: org.eclipse.jgit.transport.http.HttpConnectionFactory) : org.eclipse.jgit.transport.http.HttpConnectionFactory {
    private val endpoint = HttpRemote.parse(url)
    private fun validate(url: java.net.URL) {
        val uri = url.toURI()
        val port = if (url.port == -1) (if (url.protocol == "https") 443 else 80) else url.port
        if (url.protocol != endpoint.scheme || !url.host.equals(endpoint.host, true) || port != endpoint.port || uri.userInfo != null ||
            uri.path.split('/').any { it == "." || it == ".." } || !(uri.rawPath == endpoint.path || uri.rawPath.startsWith(endpoint.path + "/"))) {
            throw java.io.IOException("The server redirected outside the configured repository. Use its final repository URL.")
        }
    }
    override fun create(url: java.net.URL): org.eclipse.jgit.transport.http.HttpConnection { validate(url); return delegate.create(url) }
    override fun create(url: java.net.URL, proxy: java.net.Proxy): org.eclipse.jgit.transport.http.HttpConnection { validate(url); return delegate.create(url, proxy) }
}
