package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.GitWorkspaceSummary
import com.bontecou.syncmd.ui.components.GitActionRow
import com.bontecou.syncmd.ui.components.GitDestination
import com.bontecou.syncmd.ui.components.GitHeroMono
import com.bontecou.syncmd.ui.components.GitHeroTitle
import com.bontecou.syncmd.ui.components.GitKicker
import com.bontecou.syncmd.ui.components.GitPage
import com.bontecou.syncmd.ui.components.GitStatePill
import com.bontecou.syncmd.ui.components.GitToneHero
import com.bontecou.syncmd.ui.components.gitAgo
import com.bontecou.syncmd.ui.components.gitIsGitHub
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import com.bontecou.syncmd.ui.viewmodels.PullViewModel

/** Which sheet the Remote screen opens with. */
enum class RemoteSheet { NONE, MERGE, UPSTREAM }

/**
 * Remote (Material 3 board GitRemote): a hero that says how this phone and the remote relate (both
 * moved / yours to push / theirs to pull / in sync / no upstream) with the action that fits, the
 * Update list (Fetch, Pull, Merge with remote, Pull with rebase) and Tracking (upstream, origin).
 */
@Composable
fun GitRemoteScreen(
    path: String,
    vaultName: String,
    onBack: () -> Unit,
    onNavigate: (GitDestination) -> Unit,
    initialSheet: RemoteSheet = RemoteSheet.NONE,
    pullVM: PullViewModel = hiltViewModel(),
    diffVM: DiffViewModel = hiltViewModel(),
    workspaceVM: GitWorkspaceViewModel = hiltViewModel(),
) {
    LaunchedEffect(path) { pullVM.setRepositoryPath(path); diffVM.setRepositoryPath(path); workspaceVM.load(path) }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(path) { pullVM.loadStatus(); workspaceVM.load(path) }
    val workspace by workspaceVM.state.collectAsState()
    val summary = workspace.summary?.takeIf { workspace.path == path }
    val pullBusy by pullVM.isLoading.collectAsState()
    val pullSelection by pullVM.selection.collectAsState()
    val pullContext = pullSelection?.takeIf { it.path == path }
    val pullError = pullVM.errorText.collectAsState().value?.asString()
    val attention by pullVM.attention.collectAsState()
    val pushing by diffVM.isPushing.collectAsState()
    val pushError = diffVM.errorMessage.collectAsState().value?.asString()
    val operation by diffVM.operationRevision.collectAsState()
    LaunchedEffect(pullBusy, pushing, operation) { if (!pullBusy && !pushing) workspaceVM.load(path) }
    var sheet by rememberSaveable(path) { mutableStateOf(initialSheet) }
    val context = LocalContext.current
    val branch = summary?.branch ?: "—"
    val ahead = summary?.ahead ?: 0
    val behind = summary?.behind ?: 0
    val busy = pullBusy || pushing
    val push = { summary?.branch?.let { diffVM.pushCurrentBranch(it) }; Unit }
    val pull = { pullContext?.let(pullVM::executePull); Unit }

    GitPage(
        title = stringResource(R.string.git_m3_remote),
        subtitle = stringResource(R.string.git_m3_subtitle, vaultName, branch),
        onBack = onBack,
        modifier = Modifier.testTag("git-remote-screen"),
        scrollTag = "git-remote",
    ) {
        RemoteHero(summary, ahead, behind, busy,
            fetched = summary?.fetchedAt?.let { gitAgo(context, it) },
            onMerge = { sheet = RemoteSheet.MERGE }, onRebase = { onNavigate(GitDestination.Rebase) },
            onPush = push, onPull = pull, onUpstream = { sheet = RemoteSheet.UPSTREAM })

        attention?.takeIf { pullContext != null }?.let { notice ->
            M3Note(notice, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("pull-lfs-attention")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3TextButton(stringResource(R.string.ui_open_git_lfs_f7aff155), { onNavigate(GitDestination.LargeFiles) })
                    M3TextButton(stringResource(R.string.ui_dismiss_48845bff), { pullVM.clearAttention(pullContext) })
                }
            }
        }

        val hasRemote = summary?.remoteName != null
        M3Group(stringResource(R.string.git_m3_update)) {
            GitActionRow(stringResource(R.string.git_fetch), R.drawable.ic_m3_cloud_download, Modifier.testTag("git-fetch"),
                subtitle = stringResource(R.string.git_m3_fetch_sub), value = summary?.fetchedAt?.let { gitAgo(context, it) },
                enabled = hasRemote && pullContext != null && !busy, onClick = { pullContext?.let(pullVM::fetch) })
            val diverged = ahead > 0 && behind > 0
            GitActionRow(stringResource(R.string.pull_screen), R.drawable.ic_m3_arrow_downward, Modifier.testTag("git-pull"), divider = true,
                subtitle = stringResource(if (diverged) R.string.git_m3_pull_sub_blocked else R.string.git_m3_pull_sub),
                subtitleColor = if (diverged) M3.colors.error else M3.colors.onVar,
                enabled = hasRemote && pullContext != null && !busy && !diverged, onClick = pull)
            GitActionRow(stringResource(R.string.remote_merge_title), R.drawable.ic_m3_merge, Modifier.testTag("open-remote-merge"), divider = true,
                subtitle = stringResource(R.string.git_m3_merge_sub), chevron = true, enabled = hasRemote, onClick = { sheet = RemoteSheet.MERGE })
            GitActionRow(stringResource(R.string.ui_pull_with_rebase_6d25f1e0), R.drawable.ic_m3_arrow_upward, Modifier.testTag("open-rebase"), divider = true,
                subtitle = stringResource(R.string.git_m3_rebase_sub), chevron = true, onClick = { onNavigate(GitDestination.Rebase) })
        }

        M3Group(stringResource(R.string.tide_merge_upstream_section)) {
            GitActionRow(stringResource(R.string.upstream_title), R.drawable.ic_m3_branch, Modifier.testTag("git-upstream"),
                subtitle = stringResource(R.string.git_m3_pulls_from, branch), subtitleMono = true, chevron = true,
                trailing = {
                    val upstream = summary?.upstream
                    if (upstream != null) GitStatePill(upstream, M3.colors.okc, M3.colors.onOkc)
                    else GitStatePill(stringResource(R.string.git_m3_not_set), M3.colors.tc, M3.colors.onTc)
                },
                onClick = { sheet = RemoteSheet.UPSTREAM })
            summary?.remoteName?.let { remote ->
                GitActionRow(remote, R.drawable.ic_m3_link, divider = true,
                    subtitle = summary.remoteUrl?.replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^@/]*@)?"), ""),
                    subtitleMono = true)
                Text(stringResource(R.string.git_m3_remote_access, stringResource(remoteAccess(summary.remoteUrl))),
                    Modifier.padding(start = 60.dp, bottom = 12.dp), style = M3Type.caption, color = M3.colors.onVar)
            }
        }
    }

    when (sheet) {
        RemoteSheet.MERGE -> RemoteMergeFlow(path, onClose = { sheet = RemoteSheet.NONE; workspaceVM.load(path) }, onNavigate = onNavigate)
        RemoteSheet.UPSTREAM -> UpstreamSheet(path, onDismiss = { sheet = RemoteSheet.NONE }, onSaved = { sheet = RemoteSheet.NONE; workspaceVM.load(path) })
        RemoteSheet.NONE -> Unit
    }
    val error = pullError.takeIf { pullContext != null } ?: pushError
    if (error != null) {
        val clear = { pullVM.clearError(pullContext); diffVM.clearError() }
        M3Dialog(onDismissRequest = clear, title = stringResource(R.string.tide_git_git_operation), icon = R.drawable.ic_m3_warning, iconTint = M3.colors.error,
            content = { Text(error, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("git-operation-message"), style = M3Type.bodyMedium) }) {
            M3DialogAction(stringResource(R.string.ui_ok_565339bc), clear)
        }
    }
}

private fun remoteAccess(url: String?): Int = when {
    url == null -> R.string.git_m3_access_https
    url.startsWith("file:") || url.startsWith("/") -> R.string.git_m3_access_local
    url.startsWith("ssh://") || Regex("^[^@/]+@[^:/]+:").containsMatchIn(url) -> R.string.git_m3_access_ssh
    gitIsGitHub(url) -> R.string.git_m3_access_github
    else -> R.string.git_m3_access_https
}

@Composable
private fun RemoteHero(
    summary: GitWorkspaceSummary?,
    ahead: Int,
    behind: Int,
    busy: Boolean,
    fetched: String?,
    onMerge: () -> Unit,
    onRebase: () -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onUpstream: () -> Unit,
) {
    val c = M3.colors
    GitToneHero(c.inv, c.onInv, Modifier.testTag("git-remote-hero")) {
        val noUpstream = summary != null && summary.upstream == null
        val (kicker, headline) = when {
            summary == null -> R.string.git_m3_remote to R.string.git_m3_remote_reading
            noUpstream -> R.string.git_m3_no_upstream_kicker to R.string.git_m3_no_upstream_headline
            ahead > 0 && behind > 0 -> R.string.git_m3_both_moved to R.string.git_m3_both_moved_headline
            ahead > 0 -> R.string.git_m3_ready_to_push to R.string.git_m3_ready_to_push_headline
            behind > 0 -> R.string.git_m3_new_on_remote to R.string.git_m3_new_on_remote_headline
            else -> R.string.git_m3_in_sync to R.string.git_m3_in_sync_headline
        }
        GitKicker(stringResource(kicker), color = c.invAhead)
        GitHeroTitle(stringResource(headline))
        if (summary != null && !noUpstream) GitHeroMono(buildAnnotatedString {
            withStyle(SpanStyle(color = c.invAhead, fontWeight = FontWeight.SemiBold)) { append(stringResource(R.string.git_m3_yours, ahead)) }
            append(" · ")
            withStyle(SpanStyle(color = c.invBehind, fontWeight = FontWeight.SemiBold)) { append(stringResource(R.string.git_m3_from_remote, behind)) }
            append(" · "); append(fetched?.let { stringResource(R.string.git_m3_fetched, it) } ?: stringResource(R.string.git_m3_never_fetched))
        })
        val body = when {
            summary == null -> null
            noUpstream -> stringResource(R.string.git_m3_no_upstream_body)
            ahead > 0 && behind > 0 -> stringResource(R.string.git_m3_both_moved_body, ahead)
            ahead > 0 -> stringResource(R.string.git_m3_ready_to_push_body, ahead)
            behind > 0 -> stringResource(R.string.git_m3_new_on_remote_body, summary.branch ?: "")
            else -> null
        }
        body?.let { Text(it, Modifier.padding(top = 10.dp), style = M3Type.bodyMedium, color = c.invMeta) }
        when {
            summary == null -> Unit
            noUpstream -> M3Button(stringResource(R.string.git_m3_set_upstream), onUpstream, Modifier.fillMaxWidth().padding(top = 20.dp),
                style = M3ButtonStyle.OnHero, size = M3ButtonSize.Large, icon = R.drawable.ic_m3_branch, enabled = !busy)
            ahead > 0 && behind > 0 -> Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                M3Button(stringResource(R.string.git_m3_merge), onMerge, Modifier.weight(1f).testTag("git-remote-merge"), style = M3ButtonStyle.OnHero,
                    size = M3ButtonSize.Large, icon = R.drawable.ic_m3_merge, enabled = !busy)
                M3Button(stringResource(R.string.git_m3_rebase), onRebase, Modifier.weight(1f).testTag("git-remote-rebase"), style = M3ButtonStyle.OutlinedOnHero,
                    size = M3ButtonSize.Large, icon = R.drawable.ic_m3_arrow_upward, enabled = !busy)
            }
            ahead > 0 -> M3Button(stringResource(R.string.git_m3_push_n, ahead), onPush, Modifier.fillMaxWidth().padding(top = 20.dp).testTag("git-remote-push"),
                style = M3ButtonStyle.OnHero, size = M3ButtonSize.Large, icon = R.drawable.ic_m3_arrow_upward, enabled = !busy, loading = busy)
            behind > 0 -> M3Button(stringResource(R.string.git_m3_pull_n, behind), onPull, Modifier.fillMaxWidth().padding(top = 20.dp).testTag("git-remote-pull"),
                style = M3ButtonStyle.OnHero, size = M3ButtonSize.Large, icon = R.drawable.ic_m3_arrow_downward, enabled = !busy, loading = busy)
            else -> Unit
        }
    }
}
