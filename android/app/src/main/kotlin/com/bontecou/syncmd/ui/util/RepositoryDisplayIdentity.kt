package com.bontecou.syncmd.ui.util

import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import java.net.URI

data class RepositoryDisplayIdentity(val name: String, val owner: String?)

/** Use saved catalog identity, never an app-private filesystem path as an owner. */
fun SavedRepository.displayIdentity(): RepositoryDisplayIdentity {
    fun ownerFromRemote(value: String?): String? {
        val source = value?.trim()?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: return null
        val remotePath = runCatching {
            val uri = URI(source)
            if (uri.scheme?.lowercase() in setOf("https", "http", "ssh") && !uri.host.isNullOrBlank()) uri.path
            else null
        }.getOrNull() ?: Regex("^[^/@:]+@[^/:]+:(.+)$").matchEntire(source)?.groupValues?.get(1)
        val parts = remotePath?.trim('/')?.split('/')?.takeIf { it.size >= 2 && it.none { part -> part.isBlank() || part == "." || part == ".." || part.any(Char::isISOControl) } }
        return parts?.dropLast(1)?.joinToString("/")
    }
    val legacy = path.takeIf { it.startsWith("github://") }?.removePrefix("github://")
    val aliasOwner = alias.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) }?.substringBefore('/')
    return RepositoryDisplayIdentity(alias.takeIf { it.isNotBlank() } ?: name.takeIf { it.isNotBlank() }
        ?: path.substringAfterLast('/'), ownerFromRemote(remoteUrl) ?: legacy?.substringBefore('/') ?: aliasOwner)
}
