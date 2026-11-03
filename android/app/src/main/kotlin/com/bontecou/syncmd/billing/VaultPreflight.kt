package com.bontecou.syncmd.billing

import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.services.git.RemoteReach
import com.bontecou.syncmd.services.github.GitHubVaultClient
import com.bontecou.syncmd.ui.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URI

/** What the free check needs to look at a repository the way the clone will. */
data class VaultTarget(val name: String, val url: String, val branch: String?, val credentials: Credentials, val gitHubToken: String?)

enum class VaultCheckKind { ACCESS, BRANCH, SIZE, LFS, OBSIDIAN }
enum class VaultCheckStatus { PENDING, RUNNING, PASSED, WARNING, FAILED, SKIPPED }
/** [detail] is null for the size row, which the screen formats from [VaultCheckState.sizeBytes]/[VaultCheckState.fileCount]. */
data class VaultCheckRow(val kind: VaultCheckKind, val status: VaultCheckStatus = VaultCheckStatus.PENDING, val detail: UiText? = null)

sealed interface VaultPhase {
    data object Checking : VaultPhase
    data object Ready : VaultPhase
    /** The remote could not be reached; [message] is the transport's own (credential-free) reason. */
    data class Unreachable(val message: UiText) : VaultPhase
    /** Readable but not writable, so pushes would be refused. */
    data object ReadOnly : VaultPhase
}

data class VaultCheckState(
    val rows: List<VaultCheckRow> = VaultCheckKind.entries.map { VaultCheckRow(it) },
    val phase: VaultPhase = VaultPhase.Checking,
    val branch: String? = null,
    val sizeBytes: Long? = null,
    val fileCount: Int? = null,
    val hasObsidianGit: Boolean = false,
) {
    val isBlocked: Boolean get() = phase is VaultPhase.Unreachable || phase == VaultPhase.ReadOnly
    fun row(kind: VaultCheckKind) = rows.first { it.kind == kind }
    internal fun with(kind: VaultCheckKind, status: VaultCheckStatus, detail: UiText? = null) =
        copy(rows = rows.map { if (it.kind == kind) VaultCheckRow(kind, status, detail) else it })
}

/**
 * The free vault check (port of iOS `VaultPreflight`): connection, branch, size, LFS and
 * Obsidian, from metadata only. Non-GitHub remotes stop after the connection; their
 * size, LFS and Obsidian rows are checked by the clone itself.
 */
class VaultPreflight(
    private val listRefs: (String, Credentials) -> RemoteReach,
    private val github: GitHubVaultClient,
    private val pause: suspend () -> Unit = { delay(220) },
) {
    fun run(target: VaultTarget): Flow<VaultCheckState> = flow {
        var state = VaultCheckState()
        suspend fun set(next: VaultCheckState) { state = next; emit(next) }
        fun skipped(kind: VaultCheckKind) = UiText.res(R.string.paywall_check_on_clone).let { state.with(kind, VaultCheckStatus.SKIPPED, it) }

        set(state.with(VaultCheckKind.ACCESS, VaultCheckStatus.RUNNING))
        var branch = target.branch?.takeIf { it.isNotBlank() }
        when (val reach = listRefs(target.url, target.credentials)) {
            is RemoteReach.Reachable -> {
                if (branch != null && reach.branches.isNotEmpty() && branch !in reach.branches) {
                    set(state.with(VaultCheckKind.ACCESS, VaultCheckStatus.PASSED, UiText.res(R.string.paywall_check_connected))
                        .with(VaultCheckKind.BRANCH, VaultCheckStatus.FAILED, UiText.res(R.string.paywall_check_branch_missing, branch)))
                    VaultCheckKind.entries.drop(2).forEach { set(state.with(it, VaultCheckStatus.SKIPPED)) }
                    set(state.copy(phase = VaultPhase.Unreachable(UiText.res(R.string.paywall_check_branch_missing_detail, branch))))
                    return@flow
                }
                branch = branch ?: reach.defaultBranch
                set(state.with(VaultCheckKind.ACCESS, VaultCheckStatus.PASSED, UiText.res(R.string.paywall_check_connected)).copy(branch = branch))
            }
            RemoteReach.HostKeyUnconfirmed ->
                set(state.with(VaultCheckKind.ACCESS, VaultCheckStatus.PASSED, UiText.res(R.string.paywall_check_confirm_key)))
            is RemoteReach.Unreachable -> {
                set(state.with(VaultCheckKind.ACCESS, VaultCheckStatus.FAILED, UiText.res(R.string.paywall_check_cant_connect)))
                VaultCheckKind.entries.drop(1).forEach { set(state.with(it, VaultCheckStatus.SKIPPED)) }
                set(state.copy(phase = VaultPhase.Unreachable(unreachableReason(reach.cause))))
                return@flow
            }
        }
        pause()

        val repo = gitHubRepository(target.url)
        val token = target.gitHubToken
        if (repo == null || token == null) {
            set(state.with(VaultCheckKind.BRANCH, VaultCheckStatus.PASSED, branch?.let(UiText::Raw) ?: UiText.Raw("HEAD")))
            set(skipped(VaultCheckKind.SIZE)); set(skipped(VaultCheckKind.LFS)); set(skipped(VaultCheckKind.OBSIDIAN))
            set(state.copy(phase = VaultPhase.Ready))
            return@flow
        }
        val (owner, name) = repo

        var readOnly = false
        val access = runCatching { github.repoAccess(token, owner, name) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        if (access?.canPush == true) set(state.with(VaultCheckKind.ACCESS, VaultCheckStatus.PASSED, UiText.res(R.string.paywall_check_read_write)))
        if (access?.canPush == false) { readOnly = true; set(state.with(VaultCheckKind.ACCESS, VaultCheckStatus.FAILED, UiText.res(R.string.paywall_check_read_only))) }
        branch = branch ?: access?.defaultBranch
        set(state.with(VaultCheckKind.BRANCH, VaultCheckStatus.PASSED, UiText.Raw(branch ?: "HEAD")).copy(branch = branch))
        pause()

        set(state.with(VaultCheckKind.SIZE, VaultCheckStatus.RUNNING))
        val tree = branch?.let { ref -> runCatching { github.tree(token, owner, name, ref) }.onFailure { if (it is CancellationException) throw it }.getOrNull() }
        val bytes = access?.sizeKilobytes?.times(1024)
        set(when {
            bytes == null && tree == null -> state.with(VaultCheckKind.SIZE, VaultCheckStatus.SKIPPED, UiText.res(R.string.paywall_check_on_clone))
            else -> state.with(VaultCheckKind.SIZE, VaultCheckStatus.PASSED).copy(sizeBytes = bytes, fileCount = tree?.fileCount)
        })
        pause()

        if (tree == null) {
            set(skipped(VaultCheckKind.LFS)); set(skipped(VaultCheckKind.OBSIDIAN))
        } else {
            set(state.with(VaultCheckKind.LFS, VaultCheckStatus.RUNNING))
            val lfs = tree.gitattributesSha?.let { sha -> runCatching { github.blobText(token, owner, name, sha) }.onFailure { if (it is CancellationException) throw it }.getOrNull() }
                ?.contains("filter=lfs")
            set(when (lfs) {
                true -> state.with(VaultCheckKind.LFS, VaultCheckStatus.PASSED, UiText.res(R.string.paywall_check_lfs_used))
                false -> state.with(VaultCheckKind.LFS, VaultCheckStatus.PASSED, UiText.res(R.string.paywall_check_lfs_none))
                null -> if (tree.gitattributesSha == null) state.with(VaultCheckKind.LFS, VaultCheckStatus.PASSED, UiText.res(R.string.paywall_check_lfs_none))
                    else skipped(VaultCheckKind.LFS)
            })
            pause()
            set(if (tree.hasObsidian) state.with(VaultCheckKind.OBSIDIAN, VaultCheckStatus.PASSED, UiText.Raw(".obsidian/")).copy(hasObsidianGit = tree.hasObsidianGit)
                else state.with(VaultCheckKind.OBSIDIAN, VaultCheckStatus.WARNING, UiText.res(R.string.paywall_check_no_obsidian)))
        }
        set(state.copy(phase = if (readOnly) VaultPhase.ReadOnly else VaultPhase.Ready))
    }

    companion object {
        /** owner/repo of an https://github.com remote, or null for anything else. */
        fun gitHubRepository(url: String): Pair<String, String>? = runCatching {
            val remote = GitRemote.parse(url)
            if (!remote.isHttps || remote.host != "github.com" || remote.port != 443) return null
            val parts = URI(remote.url).path.trim('/').removeSuffix(".git").split('/')
            if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0] to parts[1] else null
        }.getOrNull()

        /** A short, credential-free reason; never the server's response or the address with userinfo. */
        internal fun unreachableReason(cause: Throwable): UiText {
            val text = generateSequence(cause) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
            return UiText.res(when {
                "not authorized" in text || "authentication" in text || "401" in text || "403" in text -> R.string.paywall_unreachable_auth
                "not found" in text || "404" in text || "repository not found" in text -> R.string.paywall_unreachable_not_found
                else -> R.string.paywall_unreachable_network
            })
        }
    }
}
