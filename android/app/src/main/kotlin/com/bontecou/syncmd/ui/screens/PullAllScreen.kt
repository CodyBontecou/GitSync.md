package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.bontecou.syncmd.ui.theme.*
import com.bontecou.syncmd.ui.viewmodels.PullAllViewModel

/** Board "Pull all": idle explainer, the saved background summary, this run's per-vault results and one bottom action. */
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
    LaunchedEffect(model) { model.refreshCatalog() }
    BackHandler { model.stop(); onBack() }
    PullAllContent(
        state = state,
        onBack = { model.stop(); onBack() },
        onStart = model::start,
        onStop = model::stop,
        onStopBackground = model::stopBackground,
        onRequestClear = { clearBackground = true },
    )
    if (clearBackground) M3Dialog(onDismissRequest = { clearBackground = false }, title = stringResource(R.string.pullall_m3_clear_title),
        icon = R.drawable.ic_m3_delete, iconTint = M3.colors.error, text = stringResource(R.string.pullall_m3_clear_body)) {
        M3DialogAction(stringResource(R.string.lfs_m3_cancel), { clearBackground = false })
        M3DialogAction(stringResource(R.string.pullall_m3_clear_confirm), { clearBackground = false; model.clearBackgroundSummary() },
            Modifier.testTag("pull-all-background-clear-confirm"), destructive = true, filled = true)
    }
}

@Composable
private fun PullAllContent(
    state: PullAllViewModel.State,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStopBackground: () -> Unit,
    onRequestClear: () -> Unit,
) {
    val c = M3.colors
    val idle = !state.started && state.background == null && !state.backgroundUnavailable
    Column(Modifier.fillMaxSize().background(c.surface)) {
        M3TopBar(stringResource(R.string.pullall_m3_title), onBack = onBack, backLabel = stringResource(R.string.pull_all_back))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("pull-all-results"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "scope") {
                Text(stringResource(R.string.pullall_m3_scope), style = M3Type.body, color = c.onVar, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
            }
            if (idle) item(key = "system-help") { SystemSurfaces() }
            if (state.backgroundUnavailable) item(key = "background-unavailable") {
                M3Note(stringResource(R.string.pullall_m3_unreadable), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), tone = M3NoteTone.Warn,
                    action = { M3Button(stringResource(R.string.pullall_m3_clear), onRequestClear, Modifier.testTag("pull-all-background-clear"),
                        style = M3ButtonStyle.OutlinedContent, size = M3ButtonSize.Small) })
            }
            if (state.backgroundControlFailed) item(key = "background-control-failed") {
                M3Note(stringResource(R.string.pullall_m3_control_failed), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), tone = M3NoteTone.Warn)
            }
            state.background?.let { run -> item(key = "background-summary") { BackgroundRunHero(run, onRequestClear) } }
            if (state.stopped) item(key = "stopped") {
                M3Note(stringResource(R.string.pullall_m3_stopped), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, tone = M3NoteTone.Ahead, icon = R.drawable.ic_m3_stop)
            }
            if (state.failed) item(key = "failed") {
                M3Note(stringResource(R.string.pullall_m3_start_failed), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), tone = M3NoteTone.Warn)
            }
            if (state.started && !state.busy && !state.failed && !state.stopped && state.targets.isEmpty()) item(key = "empty") {
                M3Note(stringResource(R.string.pullall_m3_empty), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), tone = M3NoteTone.Muted)
            }
            if (state.started && state.targets.isNotEmpty()) {
                item(key = "progress") {
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(pluralStringResource(R.plurals.pullall_m3_checked_vaults, state.targets.size, state.results.size, state.targets.size),
                            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = M3Type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                            color = c.on)
                        if (state.busy) Box(Modifier.size(18.dp).background(c.pc, CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(10.dp).background(c.primary, CircleShape))
                        }
                    }
                }
                itemsIndexed(state.targets, key = { _, it -> it.path }) { index, target ->
                    val result = state.results.firstOrNull { it.target.path == target.path }
                    PullAllTargetRow(target, state.details[target.path], result, state.current == target.path,
                        Modifier.padding(horizontal = 16.dp).padding(top = if (index == 0) 8.dp else 0.dp)
                            .m3GroupedItem(index, state.targets.size, c.cLow).m3TopDivider(index > 0, c.outlineVar))
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(c.surface).m3TopDivider(true, c.outlineVar).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
            when {
                state.busy -> {
                    val fraction = if (state.targets.isEmpty()) 0f else state.results.size.toFloat() / state.targets.size
                    M3ProgressBar(fraction, Modifier.padding(bottom = 12.dp), description = stringResource(R.string.pullall_m3_progress_cd))
                    M3Button(stringResource(if (state.stopping) R.string.pullall_m3_stopping else R.string.pullall_m3_stop), onStop,
                        Modifier.fillMaxWidth().testTag("pull-all-stop"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Hero,
                        icon = R.drawable.ic_m3_stop, enabled = !state.stopping)
                }
                state.background?.busy == true -> M3Button(stringResource(R.string.pullall_m3_stop), onStopBackground,
                    Modifier.fillMaxWidth().testTag("pull-all-background-stop"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Hero, icon = R.drawable.ic_m3_stop)
                else -> M3FilledButton(stringResource(if (state.started || state.background != null) R.string.pullall_m3_again else R.string.pullall_m3_start), onStart,
                    Modifier.fillMaxWidth().testTag("pull-all-start"), size = M3ButtonSize.Hero, icon = R.drawable.ic_m3_arrow_downward,
                    trailingMeta = state.vaults?.let { pluralStringResource(R.plurals.pullall_m3_vaults, it, it) })
            }
        }
    }
}

/** Idle: how the Pull all widget and Quick Settings tile look, and what they do. */
@Composable
private fun SystemSurfaces() {
    val c = M3.colors
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().background(c.cLow, M3.cardShape).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(128.dp).background(c.inv, RoundedCornerShape(20.dp)).padding(12.dp)) {
                M3Icon(R.drawable.ic_m3_arrow_downward, tint = c.onInv)
                Text(stringResource(R.string.pullall_m3_widget_name), style = M3Type.section.copy(lineHeight = 18.sp), color = c.onInv, modifier = Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.pullall_m3_widget_sub), style = M3Type.caption.copy(fontSize = 11.sp, lineHeight = 14.sp), color = c.invMeta,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.heightIn(min = 48.dp).background(c.pc, RoundedCornerShape(24.dp)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    M3Icon(R.drawable.ic_m3_sync, tint = c.onPc, size = 20.dp)
                    Text(stringResource(R.string.pullall_m3_tile_preview), style = M3Type.badge.copy(fontSize = 13.sp), color = c.onPc)
                }
                Text(stringResource(R.string.pullall_m3_surfaces), style = M3Type.caption.copy(fontSize = 11.sp, lineHeight = 14.sp), color = c.onVar)
            }
        }
        Text(stringResource(R.string.pullall_m3_help), style = M3Type.bodySmall, color = c.onVar, modifier = Modifier.padding(top = 14.dp))
    }
}

/** The saved background run as the inverse hero: when and from where, its state, counts, what needs attention. */
@Composable
private fun BackgroundRunHero(run: BackgroundPullRun, onRequestClear: () -> Unit) {
    val c = M3.colors
    val tint = when (run.state) {
        BackgroundPullState.QUEUED, BackgroundPullState.RUNNING -> c.invBehind
        BackgroundPullState.COMPLETE -> c.invOk
        BackgroundPullState.FAILED -> c.invDanger
        BackgroundPullState.INTERRUPTED -> c.invAhead
    }
    M3Hero(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).testTag("pull-all-background-summary")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pullall_m3_background), style = M3Type.lead.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold), color = c.onInv,
                modifier = Modifier.weight(1f))
            Box(Modifier.size(10.dp).background(tint, CircleShape))
        }
        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(run.created))
        val source = when (run.source) {
            "widget" -> stringResource(R.string.pullall_m3_source_widget)
            "tile" -> stringResource(R.string.pullall_m3_source_tile)
            "shortcut" -> stringResource(R.string.pullall_m3_source_shortcut)
            else -> null
        }
        Text(if (source != null) stringResource(R.string.pullall_m3_when_source, time, source) else time, style = M3Type.meta, color = c.invMeta,
            modifier = Modifier.padding(top = 4.dp))
        Text(stringResource(when (run.state) {
            BackgroundPullState.QUEUED, BackgroundPullState.RUNNING -> R.string.pullall_m3_running
            BackgroundPullState.COMPLETE -> R.string.pullall_m3_complete
            BackgroundPullState.FAILED -> R.string.pullall_m3_failed
            BackgroundPullState.INTERRUPTED -> R.string.pullall_m3_interrupted
        }), style = M3Type.step.copy(fontWeight = FontWeight.Medium), color = tint,
            modifier = Modifier.padding(top = 10.dp).testTag("pull-all-background-state").semantics { liveRegion = LiveRegionMode.Polite })
        Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(c.invLine))
        Text(pluralStringResource(R.plurals.pullall_m3_checked_repositories, run.total, run.checked, run.total), style = M3Type.metaStrong, color = c.onInv)
        Text(stringResource(R.string.pullall_m3_counts, run.counts[PullAllOutcome.UPDATED] ?: 0, run.counts[PullAllOutcome.UP_TO_DATE] ?: 0,
            run.counts[PullAllOutcome.ATTENTION] ?: 0, run.counts[PullAllOutcome.BLOCKED] ?: 0, run.counts[PullAllOutcome.FAILED] ?: 0),
            style = M3Type.meta.copy(lineHeight = 18.sp), color = c.invMeta, modifier = Modifier.padding(top = 4.dp))
        run.attention.forEach { attention ->
            Column(Modifier.padding(top = 12.dp)) {
                Text(attention.name, style = M3Type.step.copy(fontWeight = FontWeight.SemiBold), color = c.onInv)
                Text(stringResource(when (attention.outcome) {
                    PullAllOutcome.ATTENTION -> R.string.pullall_m3_attention_lfs
                    PullAllOutcome.BLOCKED -> R.string.pullall_m3_attention_blocked
                    else -> R.string.pullall_m3_attention_failed
                }), style = M3Type.bodySmall, color = c.invMeta, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (!run.busy) M3Button(stringResource(R.string.pullall_m3_clear), onRequestClear, Modifier.padding(top = 18.dp).fillMaxWidth().testTag("pull-all-background-clear"),
            style = M3ButtonStyle.OutlinedOnHero, size = M3ButtonSize.Large)
    }
}

/** One vault in this run: a toned circle, name, branch and remote, and the outcome in its state colour. */
@Composable
private fun PullAllTargetRow(target: PullAllTarget, detail: String?, result: PullAllResult?, running: Boolean, modifier: Modifier) {
    val c = M3.colors
    val outcome = result?.outcome
    data class Look(val tone: M3Tone, val icon: Int, val ink: Color)
    val look = when (outcome) {
        PullAllOutcome.UPDATED -> Look(M3Tone.Behind, R.drawable.ic_m3_arrow_downward, c.primary)
        PullAllOutcome.UP_TO_DATE -> Look(M3Tone.Ok, R.drawable.ic_m3_check, c.ok)
        PullAllOutcome.ATTENTION -> Look(M3Tone.Ahead, R.drawable.ic_m3_cloud_download, c.ahead)
        PullAllOutcome.BLOCKED -> Look(M3Tone.Ahead, R.drawable.ic_m3_lock, c.ahead)
        PullAllOutcome.FAILED -> Look(M3Tone.Warn, R.drawable.ic_m3_error, c.error)
        null -> if (running) Look(M3Tone.Behind, R.drawable.ic_m3_sync, c.on) else Look(M3Tone.Muted, R.drawable.ic_m3_stop, c.onVar)
    }
    val message = when (outcome) {
        PullAllOutcome.UPDATED -> if (result.commits > 0) pluralStringResource(R.plurals.pullall_m3_updated_commits, result.commits, result.commits)
            else stringResource(R.string.pullall_m3_updated)
        PullAllOutcome.UP_TO_DATE -> stringResource(R.string.pullall_m3_current)
        PullAllOutcome.ATTENTION -> stringResource(R.string.pullall_m3_lfs)
        PullAllOutcome.BLOCKED -> stringResource(R.string.pullall_m3_blocked)
        PullAllOutcome.FAILED -> stringResource(R.string.pullall_m3_item_failed)
        null -> stringResource(if (running) R.string.pullall_m3_pulling else R.string.pullall_m3_not_run)
    }
    val (bg, fg) = look.tone.colors()
    Row(modifier.heightIn(min = 72.dp).padding(start = 16.dp, end = 20.dp, top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(40.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) { M3Icon(look.icon, tint = fg) }
        Column(Modifier.weight(1f)) {
            Text(target.name, style = M3Type.bodyStrong, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, style = M3Type.meta, color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(message, style = M3Type.bodySmall.copy(fontWeight = if (running && outcome == null) FontWeight.SemiBold else FontWeight.Normal), color = look.ink,
                modifier = Modifier.padding(top = 4.dp).testTag("pull-all-outcome-${target.path}"))
        }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

private val previewTargets = listOf(
    PullAllTarget("/storage/Documents/VoxVault", "VoxVault"), PullAllTarget("/storage/Documents/notes", "notes"),
    PullAllTarget("/storage/Documents/health-md", "health-md"), PullAllTarget("/storage/Documents/recipes", "recipes"),
    PullAllTarget("/storage/Documents/reading-vault", "reading-vault"),
)
private val previewDetails = mapOf(previewTargets[0].path to "main · CodyBontecou/VoxVault", previewTargets[1].path to "main · CodyBontecou/notes",
    previewTargets[2].path to "main · CodyBontecou/health-md", previewTargets[3].path to "main · gitea.home.lan", previewTargets[4].path to "main · CodyBontecou/reading-vault")

private fun previewRun(state: BackgroundPullState) = BackgroundPullRun("preview", 1_793_966_520_000L, state, 5,
    mapOf(PullAllOutcome.UPDATED to 2, PullAllOutcome.UP_TO_DATE to 1, PullAllOutcome.ATTENTION to 1, PullAllOutcome.BLOCKED to 1),
    listOf(BackgroundPullAttention("health-md", PullAllOutcome.ATTENTION), BackgroundPullAttention("recipes", PullAllOutcome.BLOCKED)), source = "widget")

@Composable
private fun PreviewPullAll(state: PullAllViewModel.State, dark: Boolean) =
    SyncMdTheme(darkTheme = dark) { PullAllContent(state.copy(vaults = 5, details = previewDetails), {}, {}, {}, {}, {}) }

private val previewResults = PullAllViewModel.State(started = true, targets = previewTargets, results = listOf(
    PullAllResult(previewTargets[0], PullAllOutcome.UPDATED, 2), PullAllResult(previewTargets[1], PullAllOutcome.UP_TO_DATE),
    PullAllResult(previewTargets[2], PullAllOutcome.ATTENTION), PullAllResult(previewTargets[3], PullAllOutcome.BLOCKED),
    PullAllResult(previewTargets[4], PullAllOutcome.FAILED)))

@Preview(name = "Pull all · idle", heightDp = 915)
@Composable private fun PreviewPullAllIdle() = PreviewPullAll(PullAllViewModel.State(), dark = false)

@Preview(name = "Pull all · running · dark", heightDp = 915)
@Composable private fun PreviewPullAllRunning() = PreviewPullAll(PullAllViewModel.State(busy = true, started = true, targets = previewTargets,
    current = previewTargets[2].path, results = previewResults.results.take(2)), dark = true)

@Preview(name = "Pull all · results", heightDp = 915)
@Composable private fun PreviewPullAllResults() = PreviewPullAll(previewResults, dark = false)

@Preview(name = "Pull all · stopped", heightDp = 915)
@Composable private fun PreviewPullAllStopped() = PreviewPullAll(PullAllViewModel.State(started = true, stopped = true, targets = previewTargets,
    results = previewResults.results.take(2)), dark = false)

@Preview(name = "Pull all · background · dark", heightDp = 915)
@Composable private fun PreviewPullAllBackground() = PreviewPullAll(PullAllViewModel.State(background = previewRun(BackgroundPullState.COMPLETE)), dark = true)
