package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.GitCommitLine
import com.bontecou.syncmd.services.git.RemoteMergeKind
import com.bontecou.syncmd.services.git.RemoteMergeOutcome
import com.bontecou.syncmd.services.git.RemoteMergePlan
import com.bontecou.syncmd.services.git.RemoteMergePush
import com.bontecou.syncmd.ui.components.GitDestination
import com.bontecou.syncmd.ui.components.GitHeroTitle
import com.bontecou.syncmd.ui.components.GitInsetBlock
import com.bontecou.syncmd.ui.components.GitKicker
import com.bontecou.syncmd.ui.components.GitSheetLabel
import com.bontecou.syncmd.ui.components.GitSideDot
import com.bontecou.syncmd.ui.components.gitMonoInline
import com.bontecou.syncmd.ui.components.gitRemoteDisplay
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SheetSurface
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import com.bontecou.syncmd.ui.viewmodels.RemoteMergeViewModel

/** The merge-with-remote route: the Remote screen with its merge sheet already open. */
@Composable
fun RemoteMergeScreen(path: String, onBack: () -> Unit, onNavigate: (GitDestination) -> Unit = {}) {
    GitRemoteScreen(path, vaultName = path.substringAfterLast('/'), onBack = onBack, onNavigate = onNavigate, initialSheet = RemoteSheet.MERGE)
}

/**
 * Merge with remote (Material 3 board GitRemote, overlays merge → confirmPush → merged / mergedLocal /
 * pushUnconfirmed). Opening fetches and reviews; the reviewed plan is what merges. Conflicts hand
 * over to the conflict screen.
 */
@Composable
fun RemoteMergeFlow(
    path: String,
    onClose: () -> Unit,
    onNavigate: (GitDestination) -> Unit,
    model: RemoteMergeViewModel = hiltViewModel(),
    workspaceVM: GitWorkspaceViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    val workspace by workspaceVM.state.collectAsState()
    var confirm by remember(state.plan) { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    LaunchedEffect(path) { model.open(path); reviewed = false }
    LaunchedEffect(state.path, state.busy) { if (state.path == path && !state.busy && !reviewed) { reviewed = true; model.review() } }
    DisposableEffect(model) { onDispose { model.cancelReview() } }
    LaunchedEffect(state.plan) { state.plan?.let { workspaceVM.loadRange(path, it.incoming, it.head) } }
    LaunchedEffect(state.outcome) {
        val outcome = state.outcome ?: return@LaunchedEffect
        if (outcome.kind == RemoteMergeKind.CONFLICTS) { onClose(); onNavigate(GitDestination.Conflicts) }
    }
    val close = { if (!state.mutating) { model.cancelReview(); onClose() } }
    val outcome = state.outcome
    if (outcome != null && outcome.kind != RemoteMergeKind.CONFLICTS) {
        M3ModalSheet(onDismissRequest = close, modifier = Modifier.testTag("remote-merge-result-sheet"),
            footer = {
                if (outcome.push == RemoteMergePush.UNCONFIRMED) M3Button(stringResource(R.string.git_m3_retry_push), { model.retryPush(outcome) },
                    Modifier.fillMaxWidth().testTag("remote-merge-retry"), size = M3ButtonSize.Hero, loading = state.busy, enabled = !state.busy)
                M3Button(stringResource(R.string.git_m3_return_to_git), close, Modifier.fillMaxWidth().padding(top = 6.dp).testTag("remote-merge-open-git"),
                    style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large, enabled = !state.mutating)
            }) { MergeResult(outcome, state.error?.asString()) }
        return
    }
    val plan = state.plan
    M3ModalSheet(
        onDismissRequest = close,
        modifier = Modifier.testTag("remote-merge-screen"),
        title = stringResource(R.string.remote_merge_title),
        titleMeta = plan?.let { "${it.upstream} → ${it.branch.removePrefix("refs/heads/")}" },
        footer = {
            M3Button(stringResource(R.string.git_m3_merge_and_push), { confirm = true }, Modifier.fillMaxWidth().testTag("remote-merge-publish"),
                size = M3ButtonSize.Hero, enabled = plan != null && !state.busy && (plan.ahead > 0 || plan.behind > 0))
            M3Button(stringResource(R.string.git_m3_merge_locally), { plan?.let { model.begin(it, false) } }, Modifier.fillMaxWidth().padding(top = 2.dp).testTag("remote-merge-local"),
                style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large, enabled = plan != null && !state.busy && plan.behind > 0,
                loading = state.mutating)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                M3TextButton(stringResource(R.string.git_m3_fetch_again), model::review, Modifier.weight(1f).testTag("remote-merge-review"), enabled = !state.busy)
                M3TextButton(stringResource(R.string.git_m3_cancel), close, Modifier.weight(1f).testTag("remote-merge-cancel-sheet"), enabled = !state.mutating)
            }
        },
    ) {
        MergeReview(plan, workspace.ranges[GitWorkspaceViewModel.rangeKey(plan?.incoming.orEmpty(), plan?.head)].orEmpty(),
            busy = state.busy, error = state.error?.asString())
    }
    if (confirm && plan != null) {
        M3Dialog(onDismissRequest = { confirm = false }, title = stringResource(R.string.git_m3_merge_push_title), modifier = Modifier.testTag("remote-merge-confirm-title"),
            content = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("remote-merge-confirm-scroll")) {
                    Text(gitMonoInline(stringResource(R.string.git_m3_merge_push_body), plan.branch.removePrefix("refs/heads/"), plan.incoming.take(7), plan.upstream),
                        style = M3Type.bodyMedium, color = M3.colors.onVar)
                    Text(stringResource(R.string.git_m3_push_to, gitRemoteDisplay(plan.pushAddress) ?: plan.pushAddress), Modifier.padding(top = 12.dp),
                        style = M3Type.meta, color = M3.colors.onVar)
                }
            }) {
            M3DialogAction(stringResource(R.string.git_m3_cancel), { confirm = false }, Modifier.testTag("remote-merge-cancel"))
            M3DialogAction(stringResource(R.string.git_m3_merge_and_push), { confirm = false; model.begin(plan, true) }, Modifier.testTag("remote-merge-confirm"), filled = true)
        }
    }
}

@Composable
private fun MergeReview(plan: RemoteMergePlan?, incoming: List<GitCommitLine>, busy: Boolean, error: String?) {
    val c = M3.colors
    if (plan == null) {
        Text(stringResource(R.string.git_m3_merge_reviewing), Modifier.padding(top = 4.dp), style = M3Type.bodyMedium, color = c.onVar)
        if (busy) M3IndeterminateProgressBar(Modifier.padding(top = 16.dp))
        error?.let { M3Note(it, Modifier.padding(top = 12.dp).testTag("remote-merge-error"), tone = M3NoteTone.Warn) }
        return
    }
    Text(stringResource(when {
        plan.ahead > 0 && plan.behind > 0 -> R.string.git_m3_merge_both_body
        plan.behind > 0 -> R.string.git_m3_merge_ff_body
        plan.ahead > 0 -> R.string.git_m3_merge_push_only_body
        else -> R.string.git_m3_merge_current_body
    }), Modifier.padding(top = 4.dp), style = M3Type.bodyMedium, color = c.onVar)
    GitSheetLabel(stringResource(R.string.git_m3_reviewed_plan))
    GitInsetBlock(Modifier.testTag("remote-merge-plan")) {
        val branch = plan.branch.removePrefix("refs/heads/")
        Text(stringResource(R.string.remote_merge_destination, branch), style = M3Type.metaStrong.copy(fontSize = M3Type.code.fontSize), color = c.on)
        Text(stringResource(R.string.remote_merge_upstream, plan.upstream), style = M3Type.code, color = c.on)
        Text(stringResource(R.string.remote_merge_counts, plan.ahead, plan.behind), style = M3Type.meta, color = c.onVar)
        Text(stringResource(R.string.remote_merge_local_oid, plan.head.take(12)), style = M3Type.meta, color = c.ahead)
        Text(stringResource(R.string.remote_merge_incoming_oid, plan.incoming.take(12)), style = M3Type.meta, color = c.behind)
        Text(stringResource(R.string.git_m3_fetch_and_push, gitRemoteDisplay(plan.fetchAddress) ?: plan.fetchAddress), style = M3Type.meta, color = c.onVar)
        plan.author?.let { Text(stringResource(R.string.git_m3_merge_author, it.first), style = M3Type.meta, color = c.onVar) }
    }
    incoming.take(6).forEach { line ->
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GitSideDot(c.behind, ring = true, size = 10.dp)
            Text(line.title, Modifier.weight(1f), style = M3Type.bodyMedium, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(line.shortId, line.author).joinToString(" · "), style = M3Type.meta, color = c.onVar)
        }
    }
    if (busy) M3IndeterminateProgressBar(Modifier.padding(top = 16.dp), description = stringResource(R.string.remote_merge_in_flight))
    error?.let { M3Note(it, Modifier.padding(top = 12.dp).testTag("remote-merge-error"), tone = M3NoteTone.Warn) }
}

/** Result hero inside the sheet: okc when merged, tc when the push wasn't confirmed. */
@Composable
private fun MergeResult(outcome: RemoteMergeOutcome, error: String?) {
    val c = M3.colors
    val unconfirmed = outcome.push == RemoteMergePush.UNCONFIRMED
    val (bg, ink) = if (unconfirmed) c.tc to c.onTc else c.okc to c.onOkc
    com.bontecou.syncmd.ui.components.GitToneHero(bg, ink, inset = false) {
        GitKicker(stringResource(when {
            outcome.kind == RemoteMergeKind.UP_TO_DATE -> R.string.git_m3_result_current_kicker
            unconfirmed -> R.string.git_m3_result_unconfirmed_kicker
            else -> R.string.git_m3_result_merged_kicker
        }), modifier = Modifier.testTag("remote-merge-result"))
        GitHeroTitle(stringResource(when {
            outcome.kind == RemoteMergeKind.UP_TO_DATE -> R.string.git_m3_result_current
            unconfirmed -> R.string.git_m3_result_unconfirmed
            else -> R.string.git_m3_result_merged
        }))
        if (outcome.kind == RemoteMergeKind.MERGED) Text(stringResource(R.string.git_m3_result_saved_locally), Modifier.padding(top = 8.dp), style = M3Type.bodyMedium)
        Text(stringResource(when (outcome.push) {
            RemoteMergePush.CONFIRMED -> R.string.git_m3_result_push_confirmed
            RemoteMergePush.UNCONFIRMED -> R.string.git_m3_result_push_unconfirmed
            else -> R.string.git_m3_result_local_only
        }), Modifier.padding(top = 2.dp).testTag("remote-merge-push-result"), style = M3Type.bodyMedium)
        Text(stringResource(R.string.remote_merge_local_oid, outcome.head.take(12)), Modifier.padding(top = 8.dp), style = M3Type.meta)
        outcome.notice?.let { Text(it, Modifier.padding(top = 8.dp), style = M3Type.bodyMedium) }
    }
    error?.let { M3Note(it, Modifier.padding(top = 12.dp).testTag("remote-merge-error"), tone = M3NoteTone.Warn) }
}

@Preview(name = "Merge result · unconfirmed · light")
@Composable
private fun MergeResultPreview() = SyncMdTheme(darkTheme = false) {
    M3SheetSurface { MergeResult(RemoteMergeOutcome(RemoteMergeKind.MERGED, "6d2e7f1b0a93ff", RemoteMergePush.UNCONFIRMED), null) }
}

@Preview(name = "Merge result · merged · dark")
@Composable
private fun MergeResultDarkPreview() = SyncMdTheme(darkTheme = true) {
    M3SheetSurface { MergeResult(RemoteMergeOutcome(RemoteMergeKind.MERGED, "6d2e7f1b0a93ff", RemoteMergePush.CONFIRMED), null) }
}
