package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.RemoteMergeKind
import com.bontecou.syncmd.services.git.RemoteMergeOutcome
import com.bontecou.syncmd.services.git.RemoteMergePlan
import com.bontecou.syncmd.services.git.RemoteMergePush
import com.bontecou.syncmd.ui.components.GitWorkGhost
import com.bontecou.syncmd.ui.components.GitWorkHeroButton
import com.bontecou.syncmd.ui.components.GitWorkHeroSecondary
import com.bontecou.syncmd.ui.components.GitWorkNote
import com.bontecou.syncmd.ui.components.GitWorkPill
import com.bontecou.syncmd.ui.components.GitWorkPillStyle
import com.bontecou.syncmd.ui.components.GitWorkSheetTitle
import com.bontecou.syncmd.ui.components.GitWorkStateHero
import com.bontecou.syncmd.ui.components.GitWorkTone
import com.bontecou.syncmd.ui.components.GitWorkWorking
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.components.gitWorkRail
import com.bontecou.syncmd.ui.components.tint
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideSyncState
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.RemoteMergeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteMergeScreen(path: String, onBack: () -> Unit, model: RemoteMergeViewModel = hiltViewModel()) {
    val state by model.state.collectAsState()
    var confirmation by remember(state.plan) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(path) { model.open(path) }
    com.bontecou.syncmd.ui.util.RepositoryResumeEffect(path) { model.open(path) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.cancelReview() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.cancelReview() }
    }
    BackHandler(state.mutating) {}
    Scaffold(Modifier.testTag("remote-merge-screen"), containerColor = Tide.colors.paper, topBar = {
        WrappingTopBar(title = stringResource(R.string.remote_merge_title), backLabel = stringResource(R.string.publication_back),
            onBack = onBack, backEnabled = !state.mutating, titleModifier = Modifier.testTag("remote-merge-title"),
            backModifier = Modifier.testTag("remote-merge-back"))
    }) { padding ->
        RemoteMergeContent(
            state = state,
            modifier = Modifier.padding(padding),
            onOpenGit = onBack,
            onReview = model::review,
            onMergeLocal = { confirmation = false },
            onMergePublish = { confirmation = true },
            onRetry = { model.retryPush(it) },
            onRefresh = model::refresh,
        )
    }
    val plan = state.plan
    confirmation?.let { publish -> if (plan != null) TideDialog(
        onDismissRequest = { confirmation = null },
        content = {
            GitWorkSheetTitle(stringResource(if (publish) R.string.remote_merge_confirm_push else R.string.remote_merge_confirm_local),
                Modifier.testTag("remote-merge-confirm-title"))
            // Bounded so both actions stay on screen at large text; the consent text scrolls.
            Column(Modifier.padding(top = 10.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("remote-merge-confirm-scroll"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (publish) R.string.remote_merge_confirm_push_body else R.string.remote_merge_confirm_local_body, plan.branch, plan.upstream),
                    style = TideType.copy.copy(lineHeight = 21.sp), color = Tide.colors.inkMid)
                if (publish) Text(stringResource(R.string.remote_merge_push_address, plan.pushAddress),
                    style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr), color = Tide.colors.ink)
            }
        },
        confirmButton = {
            GitWorkPill(stringResource(if (publish) R.string.remote_merge_publish else R.string.remote_merge_local),
                { confirmation = null; model.begin(plan, publish) }, Modifier.testTag("remote-merge-confirm"),
                style = GitWorkPillStyle.PRIMARY, enabled = !state.busy, height = 56.dp)
        },
        dismissButton = {
            GitWorkGhost(stringResource(R.string.publication_cancel), { confirmation = null }, Modifier.testTag("remote-merge-cancel"))
        },
    ) }
}

/** Stateless merge body: state hero, scope, then the reviewed plan or the result. */
@Composable
private fun RemoteMergeContent(
    state: RemoteMergeViewModel.State,
    modifier: Modifier = Modifier,
    onOpenGit: () -> Unit,
    onReview: () -> Unit,
    onMergeLocal: () -> Unit,
    onMergePublish: () -> Unit,
    onRetry: (RemoteMergeOutcome) -> Unit,
    onRefresh: () -> Unit,
) {
    val c = Tide.colors
    val plan = state.plan
    Column(
        modifier.fillMaxSize().background(c.paper).verticalScroll(rememberScrollState())
            .padding(horizontal = Tide.gutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            state.conflicts -> GitWorkStateHero(
                kicker = stringResource(R.string.tide_merge_merge_paused_kicker),
                headline = stringResource(R.string.tide_merge_merge_paused_headline),
                tone = GitWorkTone.DANGER,
                rail = TideSyncState.Diverged(1, 1),
                busy = state.busy,
                detail = {
                    Text(stringResource(R.string.remote_merge_result_conflicts), Modifier.testTag("remote-merge-conflicts"),
                        style = TideType.sans(14.sp), color = c.heroMeta)
                },
                actions = {
                    GitWorkHeroButton(stringResource(R.string.remote_merge_open_git), onOpenGit,
                        Modifier.testTag("remote-merge-open-git"), enabled = !state.busy)
                },
            )
            plan != null -> MergePlanHero(plan.ahead, plan.behind, state.busy, onMergeLocal, onMergePublish)
            state.outcome != null -> {
                val current = state.outcome.kind == RemoteMergeKind.UP_TO_DATE
                GitWorkStateHero(
                    kicker = stringResource(if (current) R.string.tide_merge_in_sync_kicker else R.string.tide_merge_merge_done_kicker),
                    headline = stringResource(if (current) R.string.tide_merge_in_sync_headline else R.string.tide_merge_merge_done_headline),
                    tone = GitWorkTone.SYNCED,
                    busy = state.busy,
                    actions = { ReviewHeroButton(state.busy, onReview) },
                )
            }
            else -> GitWorkStateHero(
                kicker = stringResource(R.string.tide_merge_merge_kicker),
                headline = stringResource(R.string.tide_merge_merge_initial),
                busy = state.busy,
                actions = { ReviewHeroButton(state.busy, onReview) },
            )
        }

        Text(stringResource(R.string.remote_merge_scope), Modifier.padding(horizontal = 4.dp), style = TideType.sans(14.sp), color = c.inkMid)
        if (state.mutating) GitWorkWorking(stringResource(R.string.remote_merge_in_flight))
        state.error?.let { GitWorkNote(it, tone = GitWorkTone.DANGER, textModifier = Modifier.testTag("remote-merge-error")) }

        plan?.let { PlanDetails(it) }
        state.outcome?.let { OutcomeCard(it, state.busy, onRetry) }

        // With a plan on screen the hero holds the merge, so re-review moves here.
        if (!state.conflicts && plan != null) {
            GitWorkPill(stringResource(R.string.remote_merge_review), onReview, Modifier.testTag("remote-merge-review"), enabled = !state.busy)
        }
        GitWorkPill(stringResource(R.string.remote_merge_refresh), onRefresh, Modifier.testTag("remote-merge-refresh"), enabled = !state.busy)
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun ReviewHeroButton(busy: Boolean, onReview: () -> Unit) {
    GitWorkHeroButton(stringResource(R.string.remote_merge_review), onReview, Modifier.testTag("remote-merge-review"), enabled = !busy)
}

/** Reviewed plan hero: what moved on each side, merge locally (primary) or merge and push. */
@Composable
private fun MergePlanHero(ahead: Int, behind: Int, busy: Boolean, onMergeLocal: () -> Unit, onMergePublish: () -> Unit) {
    val (kicker, headline, tone) = when {
        ahead > 0 && behind > 0 -> Triple(R.string.tide_merge_both_moved, R.string.tide_merge_moved_on, GitWorkTone.DANGER)
        behind > 0 -> Triple(R.string.tide_merge_behind_kicker, R.string.tide_merge_behind_headline, GitWorkTone.BEHIND)
        ahead > 0 -> Triple(R.string.tide_merge_ahead_kicker, R.string.tide_merge_ahead_headline, GitWorkTone.AHEAD)
        else -> Triple(R.string.tide_merge_in_sync_kicker, R.string.tide_merge_in_sync_headline, GitWorkTone.SYNCED)
    }
    GitWorkStateHero(
        kicker = stringResource(kicker),
        headline = stringResource(headline),
        tone = tone,
        rail = gitWorkRail(ahead, behind),
        yours = ahead,
        theirs = behind,
        busy = busy,
        detail = if (behind == 0) ({
            Text(stringResource(if (ahead == 0) R.string.remote_merge_current else R.string.remote_merge_no_incoming),
                style = TideType.sans(14.sp), color = Tide.colors.heroMeta)
        }) else null,
        actions = {
            GitWorkHeroButton(stringResource(R.string.remote_merge_local), onMergeLocal, Modifier.testTag("remote-merge-local"),
                enabled = !busy && behind > 0)
            GitWorkHeroSecondary(stringResource(R.string.remote_merge_publish), onMergePublish, Modifier.testTag("remote-merge-publish"),
                enabled = !busy && (ahead > 0 || behind > 0))
        },
    )
}

/** Git facts the merge is pinned to, one mono line each. */
@Composable
private fun PlanDetails(plan: RemoteMergePlan) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth()) {
        TideSectionLabel(stringResource(R.string.tide_merge_plan_section), Modifier.padding(start = 4.dp, bottom = 8.dp))
        TideCard(Modifier.testTag("remote-merge-plan"), padding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.remote_merge_destination, plan.branch), style = TideType.mono(13.sp, FontWeight.SemiBold), color = c.ink)
                Text(stringResource(R.string.remote_merge_upstream, plan.upstream), style = TideType.mono(13.sp), color = c.ink)
                Text(stringResource(R.string.remote_merge_counts, plan.ahead, plan.behind), style = TideType.mono(12.sp), color = c.inkMid)
                TideHairline()
                Text(stringResource(R.string.remote_merge_local_oid, plan.head.take(12)), style = TideType.mono(12.sp), color = c.ahead)
                Text(stringResource(R.string.remote_merge_incoming_oid, plan.incoming.take(12)), style = TideType.mono(12.sp), color = c.behind)
                TideHairline()
                Text(stringResource(R.string.remote_merge_fetch_address, plan.fetchAddress),
                    style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                Text(stringResource(R.string.remote_merge_push_address, plan.pushAddress),
                    style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                plan.author?.let { Text(stringResource(R.string.remote_merge_author, it.first, it.second), style = TideType.mono(12.sp), color = c.inkFaint) }
            }
        }
    }
}

/** What the merge did locally and whether the remote acknowledged the push. */
@Composable
private fun OutcomeCard(result: RemoteMergeOutcome, busy: Boolean, onRetry: (RemoteMergeOutcome) -> Unit) {
    val c = Tide.colors
    val kindTone = when (result.kind) {
        RemoteMergeKind.CONFLICTS -> GitWorkTone.DANGER
        else -> GitWorkTone.SYNCED
    }
    val pushTone = when (result.push) {
        RemoteMergePush.CONFIRMED -> GitWorkTone.SYNCED
        RemoteMergePush.UNCONFIRMED -> GitWorkTone.AHEAD
        RemoteMergePush.BLOCKED_BY_CONFLICTS -> GitWorkTone.DANGER
        RemoteMergePush.NOT_REQUESTED -> GitWorkTone.NEUTRAL
    }
    Column(Modifier.fillMaxWidth()) {
        TideSectionLabel(stringResource(R.string.tide_merge_result_section), Modifier.padding(start = 4.dp, bottom = 8.dp))
        TideCard(padding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(when (result.kind) {
                    RemoteMergeKind.UP_TO_DATE -> R.string.remote_merge_result_current
                    RemoteMergeKind.FAST_FORWARD -> R.string.remote_merge_result_ff
                    RemoteMergeKind.MERGED -> R.string.remote_merge_result_merged
                    RemoteMergeKind.CONFLICTS -> R.string.remote_merge_result_conflicts
                }), Modifier.testTag("remote-merge-result"), style = TideType.sans(16.sp, FontWeight.SemiBold), color = kindTone.tint())
                Text(stringResource(when (result.push) {
                    RemoteMergePush.NOT_REQUESTED -> R.string.remote_merge_local_result
                    RemoteMergePush.BLOCKED_BY_CONFLICTS -> R.string.remote_merge_push_blocked
                    RemoteMergePush.CONFIRMED -> R.string.remote_merge_push_confirmed
                    RemoteMergePush.UNCONFIRMED -> R.string.remote_merge_push_unconfirmed
                }), Modifier.testTag("remote-merge-push-result"), style = TideType.sans(14.sp),
                    color = if (pushTone == GitWorkTone.NEUTRAL) c.inkMid else pushTone.tint())
                Text(stringResource(R.string.remote_merge_local_oid, result.head.take(12)), style = TideType.mono(12.sp), color = c.inkFaint)
                result.notice?.let { Text(it, style = TideType.sans(14.sp), color = c.inkMid) }
                if (result.push == RemoteMergePush.UNCONFIRMED) {
                    GitWorkPill(stringResource(R.string.remote_merge_retry), { onRetry(result) }, Modifier.testTag("remote-merge-retry"),
                        style = GitWorkPillStyle.PRIMARY, enabled = !busy)
                }
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────
// RemoteMergePlan is only built by the service, so plan states preview via the hero.

@Composable
private fun MergePreview(state: RemoteMergeViewModel.State) {
    RemoteMergeContent(state, Modifier, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Merge · not reviewed", heightDp = 640) @Composable
private fun MergeInitial() = SyncMdTheme(darkTheme = false) { MergePreview(RemoteMergeViewModel.State(path = "/vault")) }

@Preview(name = "Merge · busy · dark", heightDp = 640) @Composable
private fun MergeBusy() = SyncMdTheme(darkTheme = true) { MergePreview(RemoteMergeViewModel.State(busy = true, mutating = true)) }

@Preview(name = "Merge · paused for conflicts", heightDp = 700) @Composable
private fun MergeConflicts() = SyncMdTheme(darkTheme = false) {
    MergePreview(RemoteMergeViewModel.State(conflicts = true,
        outcome = RemoteMergeOutcome(RemoteMergeKind.CONFLICTS, "a41c9e2d0f11", RemoteMergePush.BLOCKED_BY_CONFLICTS)))
}

@Preview(name = "Merge · unconfirmed push · dark", heightDp = 800) @Composable
private fun MergeUnconfirmed() = SyncMdTheme(darkTheme = true) {
    MergePreview(RemoteMergeViewModel.State(outcome = RemoteMergeOutcome(RemoteMergeKind.MERGED, "a41c9e2d0f11", RemoteMergePush.UNCONFIRMED)))
}

@Preview(name = "Merge · error", heightDp = 640) @Composable
private fun MergeError() = SyncMdTheme(darkTheme = false) {
    MergePreview(RemoteMergeViewModel.State(error = "The remote could not be reached."))
}

@Preview(name = "Merge · reviewed, diverged · light") @Composable
private fun MergePlanLight() = SyncMdTheme(darkTheme = false) {
    Column(Modifier.background(Tide.colors.paper).padding(16.dp)) { MergePlanHero(2, 3, false, {}, {}) }
}

@Preview(name = "Merge · reviewed, only yours · dark") @Composable
private fun MergePlanAheadDark() = SyncMdTheme(darkTheme = true) {
    Column(Modifier.background(Tide.colors.paper).padding(16.dp)) { MergePlanHero(2, 0, false, {}, {}) }
}
