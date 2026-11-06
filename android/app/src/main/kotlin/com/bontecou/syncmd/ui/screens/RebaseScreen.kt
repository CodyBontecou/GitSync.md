package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.services.git.RebasePlan
import com.bontecou.syncmd.services.git.RebaseProblem
import com.bontecou.syncmd.services.git.RebaseSession
import com.bontecou.syncmd.ui.components.ConflictFilenameChoices
import com.bontecou.syncmd.ui.components.GitWorkGhost
import com.bontecou.syncmd.ui.components.GitWorkHeroButton
import com.bontecou.syncmd.ui.components.GitWorkHeroSecondary
import com.bontecou.syncmd.ui.components.GitWorkNote
import com.bontecou.syncmd.ui.components.GitWorkPill
import com.bontecou.syncmd.ui.components.GitWorkPillStyle
import com.bontecou.syncmd.ui.components.GitWorkSheetTitle
import com.bontecou.syncmd.ui.components.GitWorkSideDot
import com.bontecou.syncmd.ui.components.GitWorkStateHero
import com.bontecou.syncmd.ui.components.GitWorkTone
import com.bontecou.syncmd.ui.components.GitWorkVersionCard
import com.bontecou.syncmd.ui.components.RebaseAdditionalBlockedPaths
import com.bontecou.syncmd.ui.components.RebaseCommitCounts
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.components.gitWorkPreviewConflict
import com.bontecou.syncmd.ui.components.gitWorkPreviewRename
import com.bontecou.syncmd.ui.components.gitWorkRail
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideSyncState
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors
import com.bontecou.syncmd.ui.util.UiText
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.RebaseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RebaseScreen(path: String, onBack: () -> Unit, model: RebaseViewModel = hiltViewModel()) {
    val state by model.state.collectAsState()
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
    var confirmation by remember { mutableStateOf<String?>(null) }
    var discardDraft by remember { mutableStateOf(false) }
    com.bontecou.syncmd.ui.util.RepositoryResumeEffect(path) { model.open(path) }
    LaunchedEffect(path) { model.open(path) }
    BackHandler(state.mutating) {}
    fun closeEditor() {
        val originalPath = state.selected?.let { it.theirs?.path ?: it.filePath }
        if (state.draft != state.selected?.incomingContent || state.resolutionPath != originalPath) discardDraft = true else model.closeEditor()
    }
    Scaffold(modifier = Modifier.testTag("rebase-screen"), containerColor = Tide.colors.paper, topBar = {
        WrappingTopBar(
            title = stringResource(R.string.ui_pull_with_rebase_6d25f1e0),
            backLabel = stringResource(R.string.publication_back),
            onBack = onBack, backEnabled = !state.mutating, titleModifier = Modifier.testTag("rebase-title"))
    }) { padding ->
        RebaseContent(
            state = state,
            errorText = errorText,
            modifier = Modifier.padding(padding),
            onReview = model::review,
            onBegin = { confirmation = "begin" },
            onResolve = { conflict, strategy -> model.resolve(conflict.filePath, strategy) },
            onEdit = model::edit,
            onRetryCheckout = model::retryCheckout,
            onContinue = model::continueRebase,
            onSkip = { confirmation = "skip" },
            onAbort = { confirmation = "abort" },
            onRefresh = model::refresh,
        )
    }

    // ── Confirmation: begin / skip / abort ──────────────────────────────────
    confirmation?.let { action ->
        val destructive = action != "begin"
        TideDialog(
            onDismissRequest = { confirmation = null },
            icon = if (destructive) ({ TideModalIcon() }) else null,
            content = {
                GitWorkSheetTitle(stringResource(when (action) {
                    "abort" -> R.string.rebase_abort_title
                    "skip" -> R.string.rebase_skip_title
                    else -> R.string.rebase_begin_title
                }), Modifier.testTag("rebase-confirm-title"))
                Column(Modifier.padding(top = 8.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(when (action) {
                        "abort" -> R.string.rebase_abort_body
                        "skip" -> R.string.rebase_skip_body
                        else -> R.string.rebase_begin_body
                    }), modifier = Modifier.testTag("rebase-confirm-body"), style = TideType.copy.copy(lineHeight = 21.sp),
                        color = Tide.colors.inkMid)
                }
            },
            confirmButton = {
                GitWorkPill(
                    stringResource(R.string.ui_confirm_eebdd24a),
                    onClick = { confirmation = null; when (action) { "abort" -> model.abort(); "skip" -> model.skip(); else -> model.begin() } },
                    modifier = Modifier.testTag("rebase-confirm"),
                    style = if (destructive) GitWorkPillStyle.DANGER else GitWorkPillStyle.PRIMARY,
                    height = 56.dp,
                )
            },
            dismissButton = { GitWorkGhost(stringResource(R.string.publication_cancel), { confirmation = null }) },
        )
    }

    // ── Conflict editor ─────────────────────────────────────────────────────
    state.selected?.let { conflict ->
        TideDialog(
            onDismissRequest = { if (!state.busy) closeEditor() },
            title = stringResource(R.string.ui_resolve_1_s_94accfee, conflict.filePath),
            content = {
                RebaseEditorBody(conflict, state, errorText, model::resolutionPath, model::draft)
            },
            confirmButton = {
                BPrimaryButton(stringResource(R.string.ui_resolve_and_stage_ebded3a7), isDisabled = state.busy,
                    onClick = { model.resolve(conflict.filePath, ConflictResolutionStrategy.MANUAL) })
            },
            dismissButton = { GitWorkGhost(stringResource(R.string.publication_cancel), ::closeEditor, enabled = !state.busy) },
        )
    }
    if (discardDraft) TideDialog(
        onDismissRequest = { discardDraft = false },
        icon = { TideModalIcon() },
        title = stringResource(R.string.ui_discard_resolution_draft_47c16148),
        text = { Text(stringResource(R.string.ui_your_unsubmitted_text_will_be_discarded_th_15717285)) },
        confirmButton = {
            GitWorkPill(stringResource(R.string.ui_discard_draft_cb30147e), { discardDraft = false; model.closeEditor() },
                style = GitWorkPillStyle.DANGER, height = 56.dp)
        },
        dismissButton = { GitWorkGhost(stringResource(R.string.ui_keep_editing_e76fd2ad), { discardDraft = false }) },
    )
}

/** Stateless rebase body: state hero, notes, the reviewed plan or the stopped session. */
@Composable
private fun RebaseContent(
    state: RebaseViewModel.State,
    errorText: String?,
    modifier: Modifier = Modifier,
    onReview: () -> Unit,
    onBegin: () -> Unit,
    onResolve: (Conflict, ConflictResolutionStrategy) -> Unit,
    onEdit: (Conflict) -> Unit,
    onRetryCheckout: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onAbort: () -> Unit,
    onRefresh: () -> Unit,
) {
    val c = Tide.colors
    val session = state.session
    val plan = state.plan
    Column(
        modifier.fillMaxSize().background(c.paper).verticalScroll(rememberScrollState())
            .padding(horizontal = Tide.gutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (session != null) SessionHero(session, state.busy, onContinue, onSkip, onAbort)
        else PlanHero(plan, state.status, state.busy, onReview, onBegin)

        Text(stringResource(R.string.ui_replays_local_commits_on_the_configured_up_709bdce6),
            Modifier.padding(horizontal = 4.dp), style = TideType.sans(14.sp), color = c.inkMid)

        state.status?.let { status ->
            val (id, tone) = when (status) {
                "OK", "FAST_FORWARD", "UP_TO_DATE" -> R.string.rebase_status_success to GitWorkTone.SYNCED
                "ABORTED" -> R.string.rebase_status_aborted to GitWorkTone.NEUTRAL
                "STOPPED", "CONFLICTS" -> R.string.rebase_status_conflicts to GitWorkTone.DANGER
                else -> R.string.rebase_status_format to GitWorkTone.NEUTRAL
            }
            GitWorkNote(stringResource(id, status), tone = tone, textModifier = Modifier.testTag("rebase-message"))
        }
        state.message?.let { GitWorkNote(it, tone = GitWorkTone.AHEAD, textModifier = Modifier.testTag("rebase-attention")) }
        errorText?.let { GitWorkNote(it, tone = GitWorkTone.DANGER, textModifier = Modifier.testTag("rebase-error")) }

        if (session == null) {
            if (plan != null) {
                PlanCard(plan)
                GitWorkPill(stringResource(R.string.ui_fetch_and_review_rebase_c81979af), onReview,
                    Modifier.testTag("rebase-review"), enabled = !state.busy)
            }
        } else {
            Text(stringResource(R.string.ui_during_rebase_upstream_is_git_s_ours_side_9d2cb94f),
                Modifier.padding(horizontal = 4.dp), style = TideType.meta, color = c.inkFaint)
            session.conflicts.forEach { conflict -> SessionConflictCard(conflict, state.busy, onResolve, onEdit) }
            if (session.checkoutBlocked) {
                GitWorkNote(
                    stringResource(if (session.canRetryCheckout) R.string.rebase_checkout_blocked_summary else R.string.rebase_checkout_review_required),
                    tone = GitWorkTone.AHEAD, textModifier = Modifier.testTag("rebase-checkout-blocked"),
                )
                if (session.checkoutPaths.isNotEmpty() || session.checkoutPathCount > 0) {
                    TideCard(padding = 16.dp) {
                        session.checkoutPaths.forEach {
                            Text(it, Modifier.padding(vertical = 3.dp), style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr), color = c.ink)
                        }
                        RebaseAdditionalBlockedPaths(session.checkoutPathCount - session.checkoutPaths.size)
                    }
                }
                GitWorkPill(stringResource(R.string.rebase_checkout_retry), onRetryCheckout, Modifier.testTag("rebase-retry-checkout"),
                    style = GitWorkPillStyle.PRIMARY, enabled = !state.busy && session.canRetryCheckout)
            }
            if (session.conflicts.isEmpty() && !session.checkoutBlocked) {
                GitWorkNote(stringResource(R.string.ui_all_conflicts_are_staged_continue_replayin_b86b2a5a), tone = GitWorkTone.SYNCED)
            }
        }
        GitWorkPill(stringResource(R.string.ui_refresh_rebase_state_39c06a2c), onRefresh, enabled = !state.busy)
        Spacer(Modifier.size(16.dp))
    }
}

/** Hero before a rebase starts: not reviewed, reviewed plan, or the last result. */
@Composable
private fun PlanHero(plan: RebasePlan?, status: String?, busy: Boolean, onReview: () -> Unit, onBegin: () -> Unit) {
    if (plan == null) {
        val (headline, tone) = when (status) {
            "OK", "FAST_FORWARD", "UP_TO_DATE" -> R.string.tide_merge_rebase_done_headline to GitWorkTone.SYNCED
            "ABORTED" -> R.string.tide_merge_rebase_aborted_headline to GitWorkTone.NEUTRAL
            else -> R.string.tide_merge_rebase_initial to GitWorkTone.NEUTRAL
        }
        GitWorkStateHero(
            kicker = stringResource(R.string.tide_merge_rebase_kicker),
            headline = stringResource(headline),
            tone = tone,
            busy = busy,
            actions = {
                GitWorkHeroButton(stringResource(R.string.ui_fetch_and_review_rebase_c81979af), onReview,
                    Modifier.testTag("rebase-review"), enabled = !busy)
            },
        )
        return
    }
    val (kicker, headline, tone) = when {
        plan.ahead > 0 && plan.behind > 0 -> Triple(R.string.tide_merge_both_moved, R.string.tide_merge_moved_on, GitWorkTone.DANGER)
        plan.behind > 0 -> Triple(R.string.tide_merge_behind_kicker, R.string.tide_merge_behind_headline, GitWorkTone.BEHIND)
        plan.ahead > 0 -> Triple(R.string.tide_merge_ahead_kicker, R.string.tide_merge_ahead_headline, GitWorkTone.AHEAD)
        else -> Triple(R.string.tide_merge_in_sync_kicker, R.string.tide_merge_in_sync_headline, GitWorkTone.SYNCED)
    }
    GitWorkStateHero(
        kicker = stringResource(kicker),
        headline = stringResource(headline),
        tone = tone,
        rail = gitWorkRail(plan.ahead, plan.behind),
        yours = plan.ahead,
        theirs = plan.behind,
        busy = busy,
        actions = {
            GitWorkHeroButton(stringResource(R.string.ui_rebase_onto_reviewed_upstream_c3b8fb93), onBegin,
                Modifier.testTag("rebase-begin"), enabled = !busy)
        },
    )
}

/** Hero while Git holds a stopped rebase: conflicts, blocked checkout, or ready to continue. */
@Composable
private fun SessionHero(session: RebaseSession, busy: Boolean, onContinue: () -> Unit, onSkip: () -> Unit, onAbort: () -> Unit) {
    val c = Tide.colors
    val (headline, tone) = when {
        session.conflicts.isNotEmpty() -> R.string.tide_merge_rebase_conflicts_headline to GitWorkTone.DANGER
        session.checkoutBlocked -> R.string.tide_merge_rebase_blocked_headline to GitWorkTone.AHEAD
        else -> R.string.tide_merge_rebase_ready_headline to GitWorkTone.SYNCED
    }
    GitWorkStateHero(
        kicker = stringResource(R.string.ui_rebase_in_progress_3ecd9c42),
        kickerModifier = Modifier.testTag("rebase-active"),
        headline = stringResource(headline),
        tone = tone,
        rail = TideSyncState.Diverged(1, 1),
        busy = busy,
        detail = {
            Text(session.branch?.removePrefix("refs/heads/") ?: stringResource(R.string.rebase_detached),
                style = TideType.mono(12.sp, FontWeight.SemiBold), color = c.heroMeta)
        },
        actions = {
            GitWorkHeroButton(stringResource(R.string.ui_continue_rebase_7a14e965), onContinue, Modifier.testTag("rebase-continue"),
                enabled = !busy && session.conflicts.isEmpty() && !session.checkoutBlocked)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GitWorkHeroSecondary(stringResource(R.string.ui_skip_current_commit_370b7166), onSkip,
                    Modifier.weight(1f).testTag("rebase-skip"), enabled = !busy)
                GitWorkHeroSecondary(stringResource(R.string.ui_abort_rebase_fd5cfc32), onAbort,
                    Modifier.weight(1f).testTag("rebase-abort"), enabled = !busy, danger = true)
            }
        },
    )
}

/** The reviewed plan's git facts: branch, counts, upstream and its pinned head. */
@Composable
private fun PlanCard(plan: RebasePlan) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth()) {
        TideSectionLabel(stringResource(R.string.tide_merge_plan_section), Modifier.padding(start = 4.dp, bottom = 8.dp))
        TideCard {
            Text(plan.branch.removePrefix("refs/heads/"), style = TideType.mono(15.sp, FontWeight.SemiBold), color = c.ink)
            Spacer(Modifier.size(6.dp))
            RebaseCommitCounts(plan.ahead, plan.behind)
            Spacer(Modifier.size(4.dp))
            Text("${plan.upstream} · ${plan.upstreamHead.take(12)}", style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr),
                color = c.inkFaint)
        }
    }
}

/** One stopped file: path, what each side did, then file-level choices as pills. */
@Composable
private fun SessionConflictCard(
    conflict: Conflict,
    busy: Boolean,
    onResolve: (Conflict, ConflictResolutionStrategy) -> Unit,
    onEdit: (Conflict) -> Unit,
) {
    val c = Tide.colors
    TideCard(Modifier.testTag("rebase-conflict-${conflict.filePath}"), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(conflict.filePath, Modifier.weight(1f), style = TideType.mono(13.sp, FontWeight.SemiBold)
                .copy(textDirection = TextDirection.Ltr), color = c.ink)
            TidePill(stringResource(R.string.tide_merge_conflict_unresolved), c.danger, c.danger.copy(alpha = 0.10f))
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (conflict.isRenameRename) {
                Text(stringResource(R.string.conflict_renamed_differently), style = TideType.sans(14.sp, FontWeight.Medium), color = c.ink)
                SideRow(yours = false, label = stringResource(R.string.ui_upstream_94adc696), path = requireNotNull(conflict.ours).path)
                SideRow(yours = true, label = stringResource(R.string.ui_replayed_local_commit_cb0e9ca0), path = requireNotNull(conflict.theirs).path)
            }
            if (!conflict.hasCurrentVersion) Text(stringResource(R.string.ui_upstream_deleted_this_file_40765116), style = TideType.sans(14.sp), color = c.behind)
            if (!conflict.hasIncomingVersion) Text(stringResource(R.string.ui_the_replayed_local_commit_deletes_this_fil_ab00656d), style = TideType.sans(14.sp), color = c.ahead)
            if (conflict.isBinaryOrTooLarge) Text(stringResource(R.string.ui_binary_large_or_lfs_file_choose_an_entire_52c3278a), style = TideType.meta, color = c.inkFaint)
        }
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // During a rebase Git's "ours" is the upstream (theirs, blue); "theirs" is your replayed commit (orange).
                GitWorkPill(
                    stringResource(if (conflict.hasIncomingVersion) R.string.rebase_use_local else R.string.rebase_accept_local_deletion),
                    { onResolve(conflict, ConflictResolutionStrategy.THEIRS) },
                    Modifier.weight(1f).testTag("rebase-use-local"), style = GitWorkPillStyle.AHEAD, enabled = !busy,
                )
                GitWorkPill(
                    stringResource(if (conflict.hasCurrentVersion) R.string.rebase_use_upstream else R.string.rebase_accept_upstream_deletion),
                    { onResolve(conflict, ConflictResolutionStrategy.OURS) },
                    Modifier.weight(1f).testTag("rebase-use-upstream"), style = GitWorkPillStyle.BEHIND, enabled = !busy,
                )
            }
            GitWorkPill(stringResource(R.string.ui_edit_resolution_9a6cda88), { onEdit(conflict) },
                enabled = !busy && !conflict.isBinaryOrTooLarge)
        }
    }
}

@Composable
private fun SideRow(yours: Boolean, label: String, path: String) {
    val c = Tide.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GitWorkSideDot(yours)
        Column {
            Text(label, style = TideType.sans(13.sp, FontWeight.Medium), color = c.inkMid)
            Text(path, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.ink)
        }
    }
}

/** Editor sheet body: filename choice, both versions, then the staged result. */
@Composable
private fun RebaseEditorBody(
    conflict: Conflict,
    state: RebaseViewModel.State,
    errorText: String?,
    onResolutionPath: (String) -> Unit,
    onDraft: (String) -> Unit,
) {
    val c = Tide.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConflictFilenameChoices(conflict, state.resolutionPath, !state.busy, onResolutionPath,
            oursLabel = R.string.ui_upstream_94adc696,
            theirsLabel = R.string.ui_replayed_local_commit_cb0e9ca0,
            oursIsYours = false)
        GitWorkVersionCard(
            yours = true,
            label = stringResource(R.string.ui_replayed_local_commit_cb0e9ca0),
            content = if (conflict.hasIncomingVersion) conflict.incomingContent else null,
            deletedNote = stringResource(R.string.ui_the_replayed_local_commit_deletes_this_fil_ab00656d),
            blobByteCount = conflict.incomingByteCount,
            textAvailable = !conflict.isBinaryOrTooLarge,
        )
        GitWorkVersionCard(
            yours = false,
            label = stringResource(R.string.ui_upstream_94adc696),
            content = if (conflict.hasCurrentVersion) conflict.currentContent else null,
            deletedNote = stringResource(R.string.ui_upstream_deleted_this_file_40765116),
            blobByteCount = conflict.currentByteCount,
            textAvailable = !conflict.isBinaryOrTooLarge,
        )
        OutlinedTextField(
            state.draft, onDraft, enabled = !state.busy,
            label = { Text(stringResource(R.string.ui_resolved_contents_55ceff37)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("rebase-resolution"),
            shape = Tide.fieldShape,
            colors = tideFieldColors(),
            textStyle = TideType.mono(12.5.sp).copy(color = c.ink, textDirection = TextDirection.Ltr),
        )
        errorText?.let { Text(it, style = TideType.sans(14.sp, FontWeight.Medium), color = c.danger) }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewPlan = RebasePlan(
    path = "/vault", branch = "refs/heads/main", head = "a41c9e2d0f11", remote = "origin", upstream = "origin/main",
    upstreamHead = "e19c4a07bb21", configRevision = "", metadataIdentity = "", ahead = 2, behind = 3,
    credentialRevision = null, remoteBranch = "main",
)
private val previewSession = RebaseSession(
    path = "/vault", branch = "refs/heads/main", originalHead = "a41c9e2", head = "e19c4a0", revision = "1",
    conflicts = listOf(gitWorkPreviewConflict, gitWorkPreviewRename),
)

@Composable
private fun RebasePreview(state: RebaseViewModel.State, error: String? = null) {
    RebaseContent(state, error, Modifier, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Rebase · not reviewed", heightDp = 700) @Composable
private fun RebaseInitial() = SyncMdTheme(darkTheme = false) { RebasePreview(RebaseViewModel.State(path = "/vault")) }

@Preview(name = "Rebase · reviewed, diverged · light", heightDp = 900) @Composable
private fun RebasePlanLight() = SyncMdTheme(darkTheme = false) { RebasePreview(RebaseViewModel.State(plan = previewPlan)) }

@Preview(name = "Rebase · reviewed, behind · dark", heightDp = 900) @Composable
private fun RebasePlanDark() = SyncMdTheme(darkTheme = true) {
    RebasePreview(RebaseViewModel.State(plan = previewPlan.copy(ahead = 0), busy = true))
}

@Preview(name = "Rebase · stopped on conflicts · light", heightDp = 1300) @Composable
private fun RebaseStoppedLight() = SyncMdTheme(darkTheme = false) {
    RebasePreview(RebaseViewModel.State(session = previewSession, status = "STOPPED"))
}

@Preview(name = "Rebase · checkout blocked · dark", heightDp = 1000) @Composable
private fun RebaseBlockedDark() = SyncMdTheme(darkTheme = true) {
    RebasePreview(RebaseViewModel.State(session = previewSession.copy(conflicts = emptyList(), checkoutBlocked = true,
        checkoutPaths = listOf("notes/today.md"), checkoutPathCount = 3, canRetryCheckout = true)))
}

@Preview(name = "Rebase · ready to continue", heightDp = 900) @Composable
private fun RebaseReady() = SyncMdTheme(darkTheme = false) {
    RebasePreview(RebaseViewModel.State(session = previewSession.copy(conflicts = emptyList())))
}

@Preview(name = "Rebase · complete", heightDp = 700) @Composable
private fun RebaseComplete() = SyncMdTheme(darkTheme = false) { RebasePreview(RebaseViewModel.State(status = "OK")) }

@Preview(name = "Rebase · aborted · dark", heightDp = 700) @Composable
private fun RebaseAborted() = SyncMdTheme(darkTheme = true) { RebasePreview(RebaseViewModel.State(status = "ABORTED")) }

@Preview(name = "Rebase · error", heightDp = 700) @Composable
private fun RebaseError() = SyncMdTheme(darkTheme = false) {
    RebasePreview(RebaseViewModel.State(error = UiText.Raw("x")), error = "Commit or stash all local changes before rebasing.")
}
