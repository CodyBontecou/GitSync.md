package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.automation.BackgroundPullAttention
import com.bontecou.syncmd.automation.BackgroundPullRun
import com.bontecou.syncmd.automation.BackgroundPullState
import com.bontecou.syncmd.services.automation.PullAllOutcome
import com.bontecou.syncmd.services.automation.PullAllResult
import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideHero
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.PullAllViewModel

@Composable
fun PullAllScreen(model: PullAllViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    var clearBackground by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.stop() }
    }
    BackHandler { model.stop(); onBack() }
    PullAllContent(
        state = state,
        onBack = { model.stop(); onBack() },
        onStart = model::start,
        onStop = model::stop,
        onStopBackground = model::stopBackground,
        onRequestClear = { clearBackground = true },
    )
    if (clearBackground) TideDialog(
        onDismissRequest = { clearBackground = false },
        title = stringResource(R.string.pull_all_background_clear),
        text = { Text(stringResource(R.string.pull_all_background_clear_scope)) },
        confirmButton = {
            BDestructiveButton(stringResource(R.string.pull_all_background_clear), Modifier.testTag("pull-all-background-clear-confirm"),
                onClick = { clearBackground = false; model.clearBackgroundSummary() })
        },
        dismissButton = {
            BGhostButton(stringResource(R.string.repository_actions_cancel), Modifier.fillMaxWidth(), onClick = { clearBackground = false })
        },
    )
}

/** Stateless Pull All: header, saved background run (dark hero), this run's vaults, one action at the bottom. */
@Composable
private fun PullAllContent(
    state: PullAllViewModel.State,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStopBackground: () -> Unit,
    onRequestClear: () -> Unit,
) {
    val c = Tide.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        WrappingTopBar(title = stringResource(R.string.pull_all_title), backLabel = stringResource(R.string.pull_all_back), onBack = onBack)

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("pull-all-results"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "scope") {
                Text(stringResource(R.string.pull_all_scope), Modifier.padding(horizontal = 4.dp), style = TideType.copy, color = c.inkMid)
            }
            item(key = "system-help") {
                TideCard(padding = 16.dp) {
                    Text(stringResource(R.string.pull_all_system_help), style = TideType.sans(13.sp), color = c.inkFaint)
                }
            }
            if (state.backgroundUnavailable) item(key = "background-unavailable") {
                PullAllNotice(stringResource(R.string.pull_all_background_unreadable), tint = c.danger) {
                    BSecondaryButton(stringResource(R.string.pull_all_background_clear), Modifier.testTag("pull-all-background-clear"), onClick = onRequestClear)
                }
            }
            if (state.backgroundControlFailed) item(key = "background-control-failed") {
                PullAllNotice(stringResource(R.string.pull_all_background_control_failed), tint = c.danger)
            }
            state.background?.let { run -> item(key = "background-summary") { BackgroundRunHero(run, onRequestClear) } }
            if (state.stopped) item(key = "stopped") {
                PullAllNotice(stringResource(R.string.pull_all_stopped), tint = c.ahead, live = true)
            }
            if (state.failed) item(key = "failed") { PullAllNotice(stringResource(R.string.pull_all_failed), tint = c.danger) }
            if (state.started && !state.busy && !state.failed && !state.stopped && state.targets.isEmpty()) item(key = "empty") {
                PullAllNotice(stringResource(R.string.pull_all_empty), tint = c.inkMid)
            }
            if (state.started && state.targets.isNotEmpty()) item(key = "progress") {
                Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pull_all_progress, state.results.size, state.targets.size),
                        Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        style = TideType.sans(20.sp, FontWeight.SemiBold), color = c.ink)
                    if (state.busy) TidePulse(Modifier.padding(start = 12.dp))
                }
            }
            items(state.targets, key = { it.path }) { target ->
                PullAllTargetRow(target, state.results.firstOrNull { it.target.path == target.path }?.outcome, state.current == target.path)
            }
        }

        Column(
            Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding().padding(horizontal = Tide.gutter, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = c.ink, trackColor = c.well)
                BSecondaryButton(stringResource(if (state.stopping) R.string.pull_all_stopping else R.string.pull_all_stop),
                    Modifier.testTag("pull-all-stop"), isDisabled = state.stopping, onClick = onStop)
            } else if (state.background?.busy == true) {
                BSecondaryButton(stringResource(R.string.pull_all_stop), Modifier.testTag("pull-all-background-stop"), onClick = onStopBackground)
            } else {
                BPrimaryButton(stringResource(if (state.started || state.background != null) R.string.pull_all_again else R.string.pull_all_title),
                    Modifier.testTag("pull-all-start"), leadingIconRes = R.drawable.ic_pull_all, onClick = onStart)
            }
        }
    }
}

/** Tinted paper notice: a status sentence with an optional action under it. */
@Composable
private fun PullAllNotice(text: String, tint: Color, live: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val c = Tide.colors
    val shape = Tide.cardShape
    Column(
        Modifier.fillMaxWidth()
            .background(if (tint == c.inkMid) c.card else tint.copy(alpha = 0.10f), shape)
            .border(1.dp, if (tint == c.inkMid) c.line else tint.copy(alpha = 0.25f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, Modifier.then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
            style = TideType.sans(15.sp, FontWeight.Medium), color = if (tint == c.inkMid) c.ink else tint)
        action?.invoke()
    }
}

/** The saved background run as a dark state hero — it is state, not a setting. */
@Composable
private fun BackgroundRunHero(run: BackgroundPullRun, onRequestClear: () -> Unit) {
    val c = Tide.colors
    val stateTint = when (run.state) {
        BackgroundPullState.QUEUED, BackgroundPullState.RUNNING -> c.heroBehind
        BackgroundPullState.COMPLETE -> c.heroSynced
        BackgroundPullState.FAILED -> c.heroDanger
        BackgroundPullState.INTERRUPTED -> c.heroAhead
    }
    TideHero(Modifier.testTag("pull-all-background-summary")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pull_all_background_title), Modifier.weight(1f),
                    style = TideType.sans(19.sp, FontWeight.SemiBold), color = c.heroText)
                if (run.busy) TidePulse(color = c.heroText) else Box(Modifier.size(10.dp).background(stateTint, CircleShape))
            }
            Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(run.created)),
                style = TideType.mono(12.sp), color = c.heroMeta)
            Text(stringResource(when (run.state) {
                BackgroundPullState.QUEUED, BackgroundPullState.RUNNING -> R.string.pull_all_background_running
                BackgroundPullState.COMPLETE -> R.string.pull_all_background_complete
                BackgroundPullState.FAILED -> R.string.pull_all_background_failed
                BackgroundPullState.INTERRUPTED -> R.string.pull_all_background_interrupted
            }), Modifier.testTag("pull-all-background-state").semantics { liveRegion = LiveRegionMode.Polite },
                style = TideType.sans(15.sp, FontWeight.Medium), color = stateTint)
            Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(c.heroLine))
            Text(stringResource(R.string.pull_all_progress, run.checked, run.total), style = TideType.mono(12.sp, FontWeight.SemiBold), color = c.heroText)
            Text(stringResource(R.string.pull_all_background_counts, run.counts[PullAllOutcome.UPDATED] ?: 0,
                run.counts[PullAllOutcome.UP_TO_DATE] ?: 0, run.counts[PullAllOutcome.ATTENTION] ?: 0,
                run.counts[PullAllOutcome.BLOCKED] ?: 0, run.counts[PullAllOutcome.FAILED] ?: 0),
                style = TideType.mono(12.sp), color = c.heroMeta)
            run.attention.forEach { attention ->
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(attention.name, style = TideType.sans(15.sp, FontWeight.SemiBold), color = c.heroText)
                    Text(stringResource(when (attention.outcome) {
                        PullAllOutcome.BLOCKED -> R.string.pull_all_blocked
                        PullAllOutcome.ATTENTION -> R.string.pull_all_attention
                        else -> R.string.pull_all_item_failed
                    }), style = TideType.sans(13.sp), color = c.heroMeta)
                }
            }
            if (!run.busy) {
                Box(
                    Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(min = 52.dp).clip(CircleShape)
                        .border(1.dp, c.heroLine, CircleShape)
                        .testTag("pull-all-background-clear")
                        .clickable(role = Role.Button, onClick = onRequestClear)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.pull_all_background_clear), style = TideType.sans(15.sp, FontWeight.SemiBold),
                        color = c.heroText, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** One vault in this run: status dot, name, mono path, and the outcome in its state colour. */
@Composable
private fun PullAllTargetRow(target: PullAllTarget, outcome: PullAllOutcome?, running: Boolean) {
    val c = Tide.colors
    val (message, tint) = when (outcome) {
        PullAllOutcome.UPDATED -> R.string.pull_all_updated to c.behind
        PullAllOutcome.UP_TO_DATE -> R.string.pull_all_current to c.synced
        PullAllOutcome.ATTENTION -> R.string.pull_all_attention to c.ahead
        PullAllOutcome.BLOCKED -> R.string.pull_all_blocked to c.ahead
        PullAllOutcome.FAILED -> R.string.pull_all_item_failed to c.danger
        null -> if (running) R.string.pull_all_running to c.ink else R.string.pull_all_not_run to c.inkFaint
    }
    TideCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(target.name, Modifier.weight(1f), style = TideType.title, color = c.ink)
            if (running && outcome == null) TidePulse(Modifier.padding(start = 10.dp), color = c.inkMid)
            else Box(Modifier.padding(start = 10.dp).size(10.dp).background(tint, CircleShape))
        }
        Text(target.path, Modifier.padding(top = 3.dp), style = TideType.meta.copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
        Text(stringResource(message), Modifier.padding(top = 10.dp).testTag("pull-all-outcome-${target.path}"),
            style = TideType.sans(14.sp, FontWeight.Medium), color = if (tint == c.inkFaint) c.inkMid else tint)
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

private val previewTargets = listOf(
    PullAllTarget("/data/repos/cody/notes", "Personal notes"),
    PullAllTarget("/data/repos/cody/voxvault", "VoxVault"),
    PullAllTarget("/data/repos/team/wiki", "Team wiki"),
    PullAllTarget("/data/repos/team/site", "Site"),
)

private fun previewRun(state: BackgroundPullState) = BackgroundPullRun("preview", 1_735_689_600_000L, state, 8,
    if (state == BackgroundPullState.QUEUED) emptyMap() else mapOf(PullAllOutcome.UPDATED to 2, PullAllOutcome.UP_TO_DATE to 3,
        PullAllOutcome.ATTENTION to 1, PullAllOutcome.BLOCKED to 1, PullAllOutcome.FAILED to 1),
    if (state == BackgroundPullState.QUEUED) emptyList() else listOf(BackgroundPullAttention("Team wiki", PullAllOutcome.BLOCKED)))

@Composable
private fun PreviewPullAll(state: PullAllViewModel.State, dark: Boolean) =
    SyncMdTheme(darkTheme = dark) { PullAllContent(state, {}, {}, {}, {}, {}) }

private val previewResults = PullAllViewModel.State(started = true, targets = previewTargets, results = listOf(
    PullAllResult(previewTargets[0], PullAllOutcome.UPDATED), PullAllResult(previewTargets[1], PullAllOutcome.UP_TO_DATE),
    PullAllResult(previewTargets[2], PullAllOutcome.BLOCKED), PullAllResult(previewTargets[3], PullAllOutcome.FAILED)))

@Preview(name = "Pull All · ready · light", heightDp = 800)
@Composable private fun PreviewPullAllReady() = PreviewPullAll(PullAllViewModel.State(), dark = false)

@Preview(name = "Pull All · running · dark", heightDp = 800)
@Composable private fun PreviewPullAllRunning() = PreviewPullAll(PullAllViewModel.State(busy = true, started = true, targets = previewTargets,
    current = previewTargets[1].path, results = listOf(PullAllResult(previewTargets[0], PullAllOutcome.UPDATED))), dark = true)

@Preview(name = "Pull All · stopping · light", heightDp = 800)
@Composable private fun PreviewPullAllStopping() = PreviewPullAll(PullAllViewModel.State(busy = true, stopping = true, started = true,
    targets = previewTargets, current = previewTargets[0].path), dark = false)

@Preview(name = "Pull All · results · light", heightDp = 1000)
@Composable private fun PreviewPullAllResultsLight() = PreviewPullAll(previewResults, dark = false)

@Preview(name = "Pull All · results · dark", heightDp = 1000)
@Composable private fun PreviewPullAllResultsDark() = PreviewPullAll(previewResults, dark = true)

@Preview(name = "Pull All · stopped · light", heightDp = 800)
@Composable private fun PreviewPullAllStopped() = PreviewPullAll(PullAllViewModel.State(started = true, stopped = true, targets = previewTargets.take(2)), dark = false)

@Preview(name = "Pull All · empty & failed · dark", heightDp = 800)
@Composable private fun PreviewPullAllEmpty() = PreviewPullAll(PullAllViewModel.State(started = true, failed = true), dark = true)

@Preview(name = "Pull All · nothing to pull · light", heightDp = 800)
@Composable private fun PreviewPullAllNothing() = PreviewPullAll(PullAllViewModel.State(started = true), dark = false)

@Preview(name = "Pull All · background running · light", heightDp = 900)
@Composable private fun PreviewPullAllBackgroundRunning() = PreviewPullAll(PullAllViewModel.State(background = previewRun(BackgroundPullState.RUNNING)), dark = false)

@Preview(name = "Pull All · background complete · dark", heightDp = 1000)
@Composable private fun PreviewPullAllBackgroundComplete() = PreviewPullAll(PullAllViewModel.State(background = previewRun(BackgroundPullState.COMPLETE)), dark = true)

@Preview(name = "Pull All · background failed · light", heightDp = 1000)
@Composable private fun PreviewPullAllBackgroundFailed() = PreviewPullAll(PullAllViewModel.State(background = previewRun(BackgroundPullState.FAILED),
    backgroundControlFailed = true), dark = false)

@Preview(name = "Pull All · summary unreadable · dark", heightDp = 800)
@Composable private fun PreviewPullAllUnreadable() = PreviewPullAll(PullAllViewModel.State(backgroundUnavailable = true), dark = true)
