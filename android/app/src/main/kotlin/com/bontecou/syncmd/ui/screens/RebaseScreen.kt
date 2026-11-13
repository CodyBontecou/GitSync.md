package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.services.git.GitCommitLine
import com.bontecou.syncmd.services.git.RebasePlan
import com.bontecou.syncmd.services.git.RebaseProblem
import com.bontecou.syncmd.ui.components.ConflictExcerpts
import com.bontecou.syncmd.ui.components.ConflictFilenameChoices
import com.bontecou.syncmd.ui.components.GitHeroTitle
import com.bontecou.syncmd.ui.components.GitKicker
import com.bontecou.syncmd.ui.components.GitPage
import com.bontecou.syncmd.ui.components.GitSideDot
import com.bontecou.syncmd.ui.components.GitStatePill
import com.bontecou.syncmd.ui.components.GitToneHero
import com.bontecou.syncmd.ui.components.GitWorkVersionCard
import com.bontecou.syncmd.ui.components.RebaseAdditionalBlockedPaths
import com.bontecou.syncmd.ui.components.gitAge
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import com.bontecou.syncmd.ui.viewmodels.RebaseViewModel

private enum class RebasePhase { INITIAL, REVIEW, CONFLICTS, BLOCKED, READY, DONE, ABORTED }
private enum class RebaseAsk { BEGIN, SKIP, ABORT }

/**
 * Pull with rebase (Material 3 board GitRebase, phases review / conflicts / blocked / done / aborted):
 * a state hero with the action that fits — rebase onto the reviewed upstream, continue (only once
 * every file is staged), skip or abort behind confirmations, push the result — then the plan's
 * commits, the stopped files with Use local commit / Use upstream / Edit resolution, or the paths in
 * the way of a blocked checkout.
 */
@Composable
fun RebaseScreen(
    path: String,
    onBack: () -> Unit,
    model: RebaseViewModel = hiltViewModel(),
    workspaceVM: GitWorkspaceViewModel = hiltViewModel(),
    diffVM: DiffViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsState()
    val workspace by workspaceVM.state.collectAsState()
    val errorText = state.error?.let { detail ->
        state.problem?.let { problem ->
            stringResource(when (problem) {
                RebaseProblem.ACTIVE_OPERATION -> R.string.rebase_problem_active_operation
                RebaseProblem.LOCAL_BRANCH_REQUIRED -> R.string.rebase_problem_local_branch
                RebaseProblem.LOCAL_CHANGES -> R.string.rebase_problem_local_changes
                RebaseProblem.UPSTREAM_REQUIRED -> R.string.rebase_problem_upstream
            })
        } ?: detail.asString()
    }
    var ask by remember { mutableStateOf<RebaseAsk?>(null) }
    var discardDraft by remember { mutableStateOf(false) }
    var lastPlan by remember { mutableStateOf<RebasePlan?>(null) }
    var staged by rememberSaveable(path) { mutableStateOf(mapOf<String, Int>()) }
    com.bontecou.syncmd.ui.util.RepositoryResumeEffect(path) { model.open(path) }
    LaunchedEffect(path) { model.open(path); workspaceVM.load(path); diffVM.setRepositoryPath(path) }
    LaunchedEffect(state.plan) { state.plan?.let { lastPlan = it; workspaceVM.loadRange(path, it.head, it.upstreamHead); workspaceVM.loadRange(path, it.upstreamHead, it.head) } }
    LaunchedEffect(state.session?.revision, state.status) { workspaceVM.loadRebase(path); workspaceVM.load(path); if (state.session == null) staged = emptyMap() }
    val pushing by diffVM.isPushing.collectAsState()
    LaunchedEffect(pushing) { if (!pushing) workspaceVM.load(path) }
    BackHandler(state.mutating) {}

    val session = state.session
    val phase = when {
        session != null && session.conflicts.isNotEmpty() -> RebasePhase.CONFLICTS
        session != null && session.checkoutBlocked -> RebasePhase.BLOCKED
        session != null -> RebasePhase.READY
        state.plan != null -> RebasePhase.REVIEW
        state.status in setOf("OK", "FAST_FORWARD", "UP_TO_DATE") -> RebasePhase.DONE
        state.status == "ABORTED" -> RebasePhase.ABORTED
        else -> RebasePhase.INITIAL
    }
    val summary = workspace.summary?.takeIf { workspace.path == path }
    val branch = session?.branch?.removePrefix("refs/heads/") ?: summary?.branch ?: state.plan?.branch?.removePrefix("refs/heads/") ?: "—"
    fun closeEditor() {
        val originalPath = state.selected?.let { it.theirs?.path ?: it.filePath }
        if (state.draft != state.selected?.incomingContent || state.resolutionPath != originalPath) discardDraft = true else model.closeEditor()
    }

    GitPage(
        title = stringResource(R.string.ui_pull_with_rebase_6d25f1e0),
        subtitle = stringResource(R.string.git_m3_subtitle, path.substringAfterLast('/'), branch),
        onBack = { if (!state.mutating) onBack() },
        modifier = Modifier.testTag("rebase-screen"),
        scrollTag = "rebase-scroll",
        actions = { M3IconButton(R.drawable.ic_m3_refresh, stringResource(R.string.ui_refresh_rebase_state_39c06a2c), model::refresh, enabled = !state.busy) },
    ) {
        RebaseHero(phase, state, branch, workspace.rebase, lastPlan, summary?.ahead, summary?.behind, summary?.head?.shortId, pushing,
            onBegin = { ask = RebaseAsk.BEGIN }, onReview = model::review, onContinue = model::continueRebase,
            onSkip = { ask = RebaseAsk.SKIP }, onAbort = { ask = RebaseAsk.ABORT },
            onPush = { summary?.branch?.let { diffVM.pushCurrentBranch(it) } }, onBackToRemote = onBack)
        if (state.busy) M3IndeterminateProgressBar(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        state.message?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("rebase-attention"), tone = M3NoteTone.Ahead) }
        errorText?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("rebase-error"), tone = M3NoteTone.Warn) }
        diffVM.errorMessage.collectAsState().value?.asString()?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Warn) }

        when (phase) {
            RebasePhase.REVIEW -> state.plan?.let { plan ->
                val c = M3.colors
                Text(stringResource(R.string.git_m3_rebase_review_body, plan.upstream), Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
                    style = M3Type.bodyMedium, color = c.onVar)
                CommitGroup(stringResource(R.string.git_m3_rebase_yours, plan.ahead), workspace.ranges[GitWorkspaceViewModel.rangeKey(plan.head, plan.upstreamHead)].orEmpty(), c.ahead, false)
                CommitGroup(stringResource(R.string.git_m3_rebase_theirs, plan.upstream, plan.behind), workspace.ranges[GitWorkspaceViewModel.rangeKey(plan.upstreamHead, plan.head)].orEmpty(), c.behind, true)
                M3Button(stringResource(R.string.git_m3_fetch_review_again), model::review, Modifier.padding(start = 16.dp, top = 16.dp).testTag("rebase-review-again"),
                    style = M3ButtonStyle.Outlined, size = M3ButtonSize.Medium, icon = R.drawable.ic_m3_cloud_download, enabled = !state.busy)
            }
            RebasePhase.CONFLICTS, RebasePhase.READY -> session?.let { s ->
                val c = M3.colors
                M3Note(buildAnnotatedString {
                    val template = stringResource(R.string.git_m3_rebase_sides_note)
                    val upstream = stringResource(R.string.ui_upstream_94adc696); val replayed = stringResource(R.string.git_m3_replayed_commit)
                    val a = template.indexOf("%1\$s"); val b = template.indexOf("%2\$s")
                    if (a < 0 || b < 0 || b < a) append(template.replace("%1\$s", upstream).replace("%2\$s", replayed)) else {
                        append(template.substring(0, a)); withStyle(SpanStyle(color = c.behind, fontWeight = FontWeight.SemiBold)) { append(upstream) }
                        append(template.substring(a + 4, b)); withStyle(SpanStyle(color = c.ahead, fontWeight = FontWeight.SemiBold)) { append(replayed) }
                        append(template.substring(b + 4))
                    }
                }, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Muted)
                val total = s.conflicts.size + staged.size
                M3SectionLabel(stringResource(R.string.git_m3_rebase_conflicts, staged.size, total))
                s.conflicts.forEach { conflict ->
                    RebaseConflictCard(conflict, open = true, stagedNote = null, stopped = workspace.rebase?.stopped, onto = workspace.rebase?.onto, busy = state.busy,
                        onLocal = { staged = staged + (conflict.filePath to 0); model.resolve(conflict.filePath, ConflictResolutionStrategy.THEIRS) },
                        onUpstream = { staged = staged + (conflict.filePath to 1); model.resolve(conflict.filePath, ConflictResolutionStrategy.OURS) },
                        onEdit = { model.edit(conflict) })
                }
                staged.filterKeys { key -> s.conflicts.none { it.filePath == key } }.forEach { (file, how) ->
                    RebaseStagedCard(file, stringResource(when (how) { 0 -> R.string.git_m3_kept_local; 1 -> R.string.git_m3_kept_upstream; else -> R.string.git_m3_kept_edit }))
                }
                if (s.conflicts.isEmpty()) M3Note(stringResource(R.string.ui_all_conflicts_are_staged_continue_replayin_b86b2a5a),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Ok)
            }
            RebasePhase.BLOCKED -> session?.let { s ->
                val c = M3.colors
                Text(stringResource(if (s.canRetryCheckout) R.string.git_m3_rebase_blocked_body else R.string.rebase_checkout_review_required),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).testTag("rebase-checkout-blocked"), style = M3Type.bodyMedium, color = c.onVar)
                if (s.checkoutPaths.isNotEmpty()) {
                    M3SectionLabel(stringResource(R.string.git_m3_in_the_way))
                    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                        s.checkoutPaths.forEachIndexed { index, blocked ->
                            Row(Modifier.fillMaxWidth().m3TopDivider(index > 0, c.outlineVar).heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                M3Icon(R.drawable.ic_m3_lock, tint = c.onVar)
                                Text(blocked, Modifier.weight(1f), style = M3Type.code, color = c.on)
                            }
                        }
                    }
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) { RebaseAdditionalBlockedPaths(s.checkoutPathCount - s.checkoutPaths.size) }
                M3Button(stringResource(R.string.git_m3_retry_blocked), model::retryCheckout, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .testTag("rebase-retry-checkout"), size = M3ButtonSize.Large, enabled = !state.busy && s.canRetryCheckout)
            }
            else -> Text(stringResource(R.string.ui_replays_local_commits_on_the_configured_up_709bdce6), Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
                style = M3Type.bodyMedium, color = M3.colors.onVar)
        }
    }

    ask?.let { action ->
        val danger = action != RebaseAsk.BEGIN
        val stopped = workspace.rebase?.stopped
        M3Dialog(onDismissRequest = { ask = null }, title = stringResource(when (action) {
            RebaseAsk.BEGIN -> R.string.git_m3_rebase_begin_title; RebaseAsk.SKIP -> R.string.git_m3_rebase_skip_title; RebaseAsk.ABORT -> R.string.git_m3_rebase_abort_title
        }), modifier = Modifier.testTag("rebase-confirm-title"), icon = if (danger) R.drawable.ic_m3_warning else null, iconTint = M3.colors.error,
            text = when (action) {
                RebaseAsk.BEGIN -> pluralStringResource(R.plurals.git_m3_rebase_begin_body, state.plan?.ahead ?: 0, state.plan?.ahead ?: 0)
                RebaseAsk.SKIP -> stopped?.let { stringResource(R.string.git_m3_rebase_skip_body, "${it.shortId} ${it.title}") } ?: stringResource(R.string.rebase_skip_body)
                RebaseAsk.ABORT -> stringResource(R.string.git_m3_rebase_abort_body, branch, session?.originalHead?.take(7) ?: "—")
            }) {
            M3DialogAction(stringResource(R.string.publication_cancel), { ask = null })
            M3DialogAction(stringResource(when (action) {
                RebaseAsk.BEGIN -> R.string.git_m3_rebase; RebaseAsk.SKIP -> R.string.git_m3_skip_commit; RebaseAsk.ABORT -> R.string.ui_abort_rebase_fd5cfc32
            }), {
                ask = null
                when (action) { RebaseAsk.BEGIN -> model.begin(); RebaseAsk.SKIP -> model.skip(); RebaseAsk.ABORT -> model.abort() }
            }, Modifier.testTag("rebase-confirm"), destructive = danger, filled = true)
        }
    }

    state.selected?.let { conflict ->
        M3ModalSheet(onDismissRequest = { if (!state.busy) closeEditor() }, title = stringResource(R.string.ui_resolve_1_s_94accfee, conflict.filePath),
            footer = {
                M3Button(stringResource(R.string.ui_resolve_and_stage_ebded3a7), {
                    staged = staged + (conflict.filePath to 2); model.resolve(conflict.filePath, ConflictResolutionStrategy.MANUAL)
                }, Modifier.fillMaxWidth().testTag("rebase-resolve-manual"), size = M3ButtonSize.Hero, enabled = !state.busy, loading = state.busy)
                M3TextButton(stringResource(R.string.publication_cancel), ::closeEditor, Modifier.fillMaxWidth(), enabled = !state.busy)
            }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConflictFilenameChoices(conflict, state.resolutionPath, !state.busy, model::resolutionPath,
                    oursLabel = R.string.ui_upstream_94adc696, theirsLabel = R.string.ui_replayed_local_commit_cb0e9ca0, oursIsYours = false)
                GitWorkVersionCard(yours = true, label = stringResource(R.string.ui_replayed_local_commit_cb0e9ca0),
                    content = if (conflict.hasIncomingVersion) conflict.incomingContent else null,
                    deletedNote = stringResource(R.string.ui_the_replayed_local_commit_deletes_this_fil_ab00656d),
                    blobByteCount = conflict.incomingByteCount, textAvailable = !conflict.isBinaryOrTooLarge)
                GitWorkVersionCard(yours = false, label = stringResource(R.string.ui_upstream_94adc696),
                    content = if (conflict.hasCurrentVersion) conflict.currentContent else null,
                    deletedNote = stringResource(R.string.ui_upstream_deleted_this_file_40765116),
                    blobByteCount = conflict.currentByteCount, textAvailable = !conflict.isBinaryOrTooLarge)
                M3TextField(state.draft, model::draft, stringResource(R.string.ui_resolved_contents_55ceff37),
                    Modifier.heightIn(min = 120.dp).testTag("rebase-resolution"), mono = true, singleLine = false, minLines = 4, enabled = !state.busy)
                errorText?.let { Text(it, style = M3Type.bodyMedium, color = M3.colors.error) }
            }
        }
    }
    if (discardDraft) M3Dialog(onDismissRequest = { discardDraft = false }, title = stringResource(R.string.ui_discard_resolution_draft_47c16148),
        icon = R.drawable.ic_m3_warning, iconTint = M3.colors.error, text = stringResource(R.string.ui_your_unsubmitted_text_will_be_discarded_th_15717285)) {
        M3DialogAction(stringResource(R.string.ui_keep_editing_e76fd2ad), { discardDraft = false })
        M3DialogAction(stringResource(R.string.ui_discard_draft_cb30147e), { discardDraft = false; model.closeEditor() }, destructive = true, filled = true)
    }
}

@Composable
private fun RebaseHero(
    phase: RebasePhase,
    state: RebaseViewModel.State,
    branch: String,
    progress: com.bontecou.syncmd.services.git.GitRebaseProgress?,
    lastPlan: RebasePlan?,
    ahead: Int?,
    behind: Int?,
    headShort: String?,
    pushing: Boolean,
    onBegin: () -> Unit,
    onReview: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onAbort: () -> Unit,
    onPush: () -> Unit,
    onBackToRemote: () -> Unit,
) {
    val c = M3.colors
    val (bg, ink, kickerInk) = when (phase) {
        RebasePhase.INITIAL, RebasePhase.REVIEW -> Triple(c.inv, c.onInv, c.invAhead)
        RebasePhase.CONFLICTS -> Triple(c.tc, c.onTc, c.onTc)
        RebasePhase.BLOCKED -> Triple(c.ec, c.onEc, c.onEc)
        RebasePhase.READY, RebasePhase.DONE -> Triple(c.okc, c.onOkc, c.onOkc)
        RebasePhase.ABORTED -> Triple(c.cHighest, c.on, c.onVar)
    }
    val session = state.session
    val busy = state.busy
    GitToneHero(bg, ink, Modifier.testTag("rebase-hero")) {
        GitKicker(stringResource(when (phase) {
            RebasePhase.INITIAL -> R.string.ui_pull_with_rebase_6d25f1e0
            RebasePhase.REVIEW -> R.string.git_m3_both_moved
            RebasePhase.CONFLICTS, RebasePhase.BLOCKED, RebasePhase.READY -> R.string.ui_rebase_in_progress_3ecd9c42
            RebasePhase.DONE -> R.string.git_m3_rebase
            RebasePhase.ABORTED -> R.string.git_m3_rebase_aborted
        }), color = kickerInk, modifier = if (session != null) Modifier.testTag("rebase-active") else Modifier)
        val plan = state.plan
        GitHeroTitle(when (phase) {
            RebasePhase.INITIAL -> stringResource(R.string.git_m3_rebase_initial)
            RebasePhase.REVIEW -> pluralStringResource(R.plurals.git_m3_rebase_put_on_top, plan?.ahead ?: 0, plan?.ahead ?: 0)
            RebasePhase.CONFLICTS -> stringResource(R.string.git_m3_rebase_conflicts_headline)
            RebasePhase.BLOCKED -> stringResource(R.string.git_m3_rebase_blocked_headline)
            RebasePhase.READY -> stringResource(R.string.tide_merge_rebase_ready_headline)
            RebasePhase.DONE -> stringResource(R.string.git_m3_rebase_done_headline)
            RebasePhase.ABORTED -> stringResource(R.string.git_m3_rebase_aborted_headline)
        })
        val meta = when (phase) {
            RebasePhase.REVIEW -> plan?.let { stringResource(R.string.ui_1_s_local_commits_2_s_upstream_commits_77dc6fa8, it.ahead.toString(), it.behind.toString()) }
            RebasePhase.CONFLICTS, RebasePhase.BLOCKED, RebasePhase.READY -> listOfNotNull(branch,
                progress?.let { p -> val step = p.step; val total = p.total; if (step != null && total != null) stringResource(R.string.git_m3_commit_of, step, total) else null },
                progress?.stopped?.let { "${it.shortId} ${it.title}" }).joinToString(" · ")
            RebasePhase.DONE -> lastPlan?.let { pluralStringResource(R.plurals.git_m3_rebase_done_meta, ahead ?: it.ahead, ahead ?: it.ahead, it.upstreamHead.take(7)) }
            RebasePhase.ABORTED -> headShort?.let { stringResource(R.string.git_m3_rebase_restored, branch, it, ahead ?: 0, behind ?: 0) }
            RebasePhase.INITIAL -> null
        }
        meta?.let { Text(it, Modifier.padding(top = 10.dp), style = M3Type.code, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        when (phase) {
            RebasePhase.INITIAL -> M3Button(stringResource(R.string.ui_fetch_and_review_rebase_c81979af), onReview, Modifier.fillMaxWidth().padding(top = 20.dp)
                .testTag("rebase-review"), style = M3ButtonStyle.OnHero, size = M3ButtonSize.Large, icon = R.drawable.ic_m3_cloud_download, enabled = !busy)
            RebasePhase.REVIEW -> M3Button(stringResource(R.string.ui_rebase_onto_reviewed_upstream_c3b8fb93), onBegin, Modifier.fillMaxWidth().padding(top = 20.dp)
                .testTag("rebase-begin"), style = M3ButtonStyle.OnHero, size = M3ButtonSize.Large, enabled = !busy && (plan?.behind ?: 0) > 0)
            RebasePhase.CONFLICTS, RebasePhase.BLOCKED, RebasePhase.READY -> {
                val canContinue = phase == RebasePhase.READY && !busy
                val label = when (phase) {
                    RebasePhase.CONFLICTS -> pluralStringResource(R.plurals.git_m3_continue_resolve, session?.conflicts?.size ?: 0, session?.conflicts?.size ?: 0)
                    RebasePhase.BLOCKED -> stringResource(R.string.git_m3_continue_retry_first)
                    else -> stringResource(R.string.ui_continue_rebase_7a14e965)
                }
                M3Button(label, onContinue, Modifier.fillMaxWidth().padding(top = 20.dp).testTag("rebase-continue"),
                    style = M3ButtonStyle.Filled, size = M3ButtonSize.Large, enabled = canContinue, loading = busy && state.mutating,
                    shape = RoundedCornerShape(24.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3Button(stringResource(R.string.git_m3_skip_commit_ask), onSkip, Modifier.weight(1f).testTag("rebase-skip"), style = M3ButtonStyle.OutlinedContent,
                        size = M3ButtonSize.Medium, icon = R.drawable.ic_m3_skip_next, enabled = !busy)
                    M3Button(stringResource(R.string.git_m3_abort_rebase_ask), onAbort, Modifier.weight(1f).testTag("rebase-abort"), style = M3ButtonStyle.OutlinedContent,
                        size = M3ButtonSize.Medium, icon = R.drawable.ic_m3_close, enabled = !busy)
                }
            }
            RebasePhase.DONE -> if ((ahead ?: 0) > 0) M3Button(pluralStringResource(R.plurals.git_m3_push_commits, ahead ?: 0, ahead ?: 0), onPush,
                Modifier.fillMaxWidth().padding(top = 20.dp).testTag("rebase-push"), style = M3ButtonStyle.OutlinedContent, size = M3ButtonSize.Large,
                icon = R.drawable.ic_m3_arrow_upward, enabled = !pushing, loading = pushing)
            RebasePhase.ABORTED -> M3Button(stringResource(R.string.git_m3_back_to_remote), onBackToRemote, Modifier.fillMaxWidth().padding(top = 20.dp),
                style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large)
        }
    }
}

@Composable
private fun CommitGroup(title: String, commits: List<GitCommitLine>, dot: Color, ring: Boolean) {
    val c = M3.colors
    val context = LocalContext.current
    M3SectionLabel(title)
    if (commits.isNotEmpty()) M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        commits.forEachIndexed { index, line ->
            Row(Modifier.fillMaxWidth().m3TopDivider(index > 0, c.outlineVar).heightIn(min = 56.dp).padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GitSideDot(dot, ring)
                Column(Modifier.weight(1f)) {
                    Text(line.title, style = M3Type.body.copy(fontSize = 15.sp, lineHeight = 22.sp), color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(line.shortId, line.author, gitAge(context, line.time).ifBlank { null }).joinToString(" · "), style = M3Type.meta, color = c.onVar)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RebaseConflictCard(
    conflict: Conflict,
    open: Boolean,
    stagedNote: String?,
    stopped: GitCommitLine?,
    onto: GitCommitLine?,
    busy: Boolean,
    onLocal: () -> Unit,
    onUpstream: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = M3.colors
    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("rebase-conflict-${conflict.filePath}")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(conflict.filePath, Modifier.weight(1f), style = M3Type.metaStrong.copy(fontSize = 14.sp), color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                GitStatePill(stringResource(R.string.git_m3_unresolved), c.ec, c.onEc)
            }
            Text(stagedNote ?: stringResource(when {
                !conflict.hasCurrentVersion -> R.string.ui_upstream_deleted_this_file_40765116
                !conflict.hasIncomingVersion -> R.string.ui_the_replayed_local_commit_deletes_this_fil_ab00656d
                conflict.isRenameRename -> R.string.conflict_renamed_differently
                else -> R.string.git_m3_both_sides_edited
            }), Modifier.padding(top = 4.dp), style = M3Type.bodyMedium, color = c.onVar)
            if (open) {
                if (!conflict.isBinaryOrTooLarge) {
                    val shape = RoundedCornerShape(12.dp)
                    Column(Modifier.padding(top = 12.dp).fillMaxWidth().background(c.cHighest, shape)) {
                        Excerpt(stringResource(R.string.git_m3_replayed_label, stopped?.shortId ?: "—"), c.ahead, c.tcSoft, c.on,
                            if (conflict.hasIncomingVersion) ConflictExcerpts.excerpt(conflict.incomingContent, conflict.currentContent).lines.filter { it.second }.joinToString("\n") { it.first }.ifBlank { null } else null)
                        Excerpt(listOfNotNull(stringResource(R.string.ui_upstream_94adc696), onto?.shortId, onto?.author).joinToString(" · "), c.onPc, c.pc, c.onPc,
                            if (conflict.hasCurrentVersion) ConflictExcerpts.excerpt(conflict.currentContent, conflict.incomingContent).lines.filter { it.second }.joinToString("\n") { it.first }.ifBlank { null } else null)
                    }
                } else Text(stringResource(R.string.ui_binary_large_or_lfs_file_choose_an_entire_52c3278a), Modifier.padding(top = 8.dp), style = M3Type.bodySmall, color = c.onVar)
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // In a rebase Git's "ours" is the upstream and "theirs" the replayed local commit.
                    M3Button(stringResource(if (conflict.hasIncomingVersion) R.string.rebase_use_local else R.string.rebase_accept_local_deletion), onLocal,
                        Modifier.testTag("rebase-use-local"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Small, enabled = !busy)
                    M3Button(stringResource(if (conflict.hasCurrentVersion) R.string.rebase_use_upstream else R.string.rebase_accept_upstream_deletion), onUpstream,
                        Modifier.testTag("rebase-use-upstream"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Small, enabled = !busy)
                    M3Button(stringResource(R.string.ui_edit_resolution_9a6cda88), onEdit, Modifier.testTag("rebase-edit"), style = M3ButtonStyle.Tonal,
                        size = M3ButtonSize.Small, icon = R.drawable.ic_m3_edit, enabled = !busy && !conflict.isBinaryOrTooLarge)
                }
            }
        }
    }
}

@Composable
private fun Excerpt(label: String, labelColor: Color, bg: Color, ink: Color, text: String?) {
    Column(Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = M3Type.badge.copy(fontSize = 11.sp), color = labelColor)
        Text(text ?: "—", style = M3Type.meta.copy(lineHeight = 18.sp), color = ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RebaseStagedCard(path: String, note: String) {
    val c = M3.colors
    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(path, Modifier.weight(1f), style = M3Type.metaStrong.copy(fontSize = 14.sp), color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                GitStatePill(stringResource(R.string.git_m3_staged), c.okc, c.onOkc)
            }
            Text(note, Modifier.padding(top = 4.dp), style = M3Type.bodyMedium, color = c.onVar)
        }
    }
}

