package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import org.eclipse.jgit.api.Git

/** What a remote reports about itself before anything is cloned (`git ls-remote`). */
sealed interface RemoteReach {
    /** [defaultBranch] is HEAD's branch, or null for an empty remote or one that hides HEAD. */
    data class Reachable(val defaultBranch: String?, val branches: Set<String>) : RemoteReach
    /** The SSH server's host key still needs the user's review, which the clone flow asks for. */
    data object HostKeyUnconfirmed : RemoteReach
    data class Unreachable(val cause: Throwable) : RemoteReach
}

object RemoteRefs {
    /** Lists refs with the same credentials and transport the clone will use; writes nothing. */
    fun list(url: String, credentials: Credentials, timeoutSeconds: Int = 30): RemoteReach = try {
        val auth = ScopedCredentialsProvider(url, credentials)
        val refs = Git.lsRemoteRepository().setRemote(url).setTimeout(timeoutSeconds)
            .setCredentialsProvider(auth).setTransportConfigCallback(auth).callAsMap()
        val branches = refs.keys.filter { it.startsWith(HEADS) }.map { it.removePrefix(HEADS) }.toSet()
        val head = refs["HEAD"]
        val symbolic = head?.takeIf { it.isSymbolic }?.target?.name?.removePrefix(HEADS)
        // Servers without symref support: pick the branch HEAD points at, preferring main/master.
        val matched = head?.objectId?.let { id -> refs.filterKeys { it.startsWith(HEADS) }.filterValues { it.objectId == id }.keys
            .map { it.removePrefix(HEADS) }.sortedBy { if (it == "main" || it == "master") 0 else 1 }.firstOrNull() }
        RemoteReach.Reachable(symbolic ?: matched, branches)
    } catch (e: Exception) {
        if (generateSequence<Throwable>(e) { it.cause }.any { it is SshTrustRequired }) RemoteReach.HostKeyUnconfirmed
        else RemoteReach.Unreachable(e)
    }

    private const val HEADS = "refs/heads/"
}
