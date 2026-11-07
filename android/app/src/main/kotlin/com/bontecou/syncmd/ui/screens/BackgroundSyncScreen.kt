package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.background.BackgroundRun
import com.bontecou.syncmd.background.BackgroundRunVault
import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.services.automation.RepositoryActionProblem
import com.bontecou.syncmd.services.background.BackgroundConfiguration
import com.bontecou.syncmd.services.background.BackgroundHealth
import com.bontecou.syncmd.services.background.BackgroundHealthKind
import com.bontecou.syncmd.services.background.BackgroundPolicy
import com.bontecou.syncmd.ui.components.SettingsChipFlow
import com.bontecou.syncmd.ui.components.SettingsIntro
import com.bontecou.syncmd.ui.components.relativeAgo
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3BottomActionBar
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3DotRow
import com.bontecou.syncmd.ui.theme.M3FilledButton
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3InfoChip
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3OutlinedButton
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3SettingsRow
import com.bontecou.syncmd.ui.theme.M3Switch
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.BackgroundSyncViewModel

// ─── Background Sync (board 17) ─────────────────────────────────────────────

/**
 * [pushSyncOn] and [onPushSync] drive the "Push Sync" row ("Connect" until it's on). Vault
 * inclusion lives on each vault's settings page; the "Vaults" group here keeps review, exclude and
 * forget for every registered vault, including ones whose folder went away.
 */
@Composable
fun BackgroundSyncScreen(model: BackgroundSyncViewModel, onBack: () -> Unit, onOpenRepository: (String) -> Unit = {},
    pushSyncOn: Boolean = false, onPushSync: () -> Unit = {}) {
    val state by model.state.collectAsState()
    var publish by remember { mutableStateOf<Triple<Boolean, Boolean, Long>?>(null) }
    var reset by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !model.state.value.busy) model.reload()
            if (event == Lifecycle.Event.ON_STOP) { publish = null; reset = null; model.stopReview() }
        }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler { model.stopReview(); onBack() }
    BackgroundSyncContent(
        state = state,
        pushSyncOn = pushSyncOn,
        onBack = { model.stopReview(); onBack() },
        onRun = model::runNow,
        onStop = model::stopWork,
        onPull = { model.modes(it, state.configuration.push, state.configuration.generation) },
        onPush = {
            if (it) publish = Triple(state.configuration.pull, true, state.configuration.generation)
            else model.modes(state.configuration.pull, false, state.configuration.generation)
        },
        onWifi = { model.conditions(it, state.chargingOnly) },
        onCharging = { model.conditions(state.wifiOnly, it) },
        onPushSync = onPushSync,
        onReset = { reset = state.recovery },
        onExclude = model::exclude,
        onForget = model::forget,
        onReview = model::review,
        onOpen = { onOpenRepository(it.target.path) },
    )
    publish?.let { request ->
        M3Dialog(onDismissRequest = { publish = null }, title = stringResource(R.string.background_push_confirm), icon = R.drawable.ic_m3_arrow_upward,
            iconTint = M3.colors.tertiary, text = stringResource(R.string.background_push_detail)) {
            M3DialogAction(stringResource(android.R.string.cancel), { publish = null })
            M3DialogAction(stringResource(R.string.background_allow_push), { publish = null; model.modes(request.first, request.second, request.third) },
                Modifier.testTag("background-confirm-push"), filled = true)
        }
    }
    state.review?.let { plan ->
        val c = M3.colors
        M3Dialog(onDismissRequest = model::dismissReview, title = plan.target.name, content = {
            Text(plan.target.path, style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.onVar)
            Text(stringResource(R.string.background_binding, plan.branch.orEmpty(), plan.remote.orEmpty(), plan.remoteBranch.orEmpty()), Modifier.padding(top = 10.dp))
            listOfNotNull(plan.fetchUrl, plan.pushUrl).distinct().forEach { address ->
                com.bontecou.syncmd.services.repositories.RepositoryDiscovery.redactRemoteUrl(address)?.let {
                    Text(it, Modifier.padding(top = 6.dp), style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.onVar)
                }
            }
            Text(stringResource(R.string.settings_m3_bg_conditions_apply, conditionsLabel(state.wifiOnly, state.chargingOnly)), Modifier.padding(top = 10.dp))
        }) {
            M3DialogAction(stringResource(android.R.string.cancel), model::dismissReview, enabled = !state.busy)
            M3DialogAction(stringResource(R.string.background_include), model::includeReviewed, Modifier.testTag("background-confirm-include"),
                filled = true, enabled = !state.busy)
        }
    }
    reset?.let { version ->
        M3Dialog(onDismissRequest = { reset = null }, title = stringResource(R.string.background_reset), icon = R.drawable.ic_m3_warning,
            iconTint = M3.colors.error, text = stringResource(R.string.background_reset_detail)) {
            M3DialogAction(stringResource(android.R.string.cancel), { reset = null })
            M3DialogAction(stringResource(R.string.background_reset), { reset = null; model.reset(version) }, Modifier.testTag("background-confirm-reset"),
                destructive = true, filled = true)
        }
    }
}

@Composable
private fun conditionsLabel(wifi: Boolean, charging: Boolean): String = when {
    wifi && charging -> stringResource(R.string.settings_m3_bg_cond_both)
    wifi -> stringResource(R.string.settings_m3_bg_cond_wifi)
    charging -> stringResource(R.string.settings_m3_bg_cond_charging)
    else -> stringResource(R.string.settings_m3_bg_cond_any)
}

@Composable
private fun BackgroundSyncContent(
    state: BackgroundSyncViewModel.State,
    pushSyncOn: Boolean,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onPull: (Boolean) -> Unit,
    onPush: (Boolean) -> Unit,
    onWifi: (Boolean) -> Unit,
    onCharging: (Boolean) -> Unit,
    onPushSync: () -> Unit,
    onReset: () -> Unit,
    onExclude: (BackgroundSyncViewModel.Row) -> Unit,
    onForget: (BackgroundSyncViewModel.Row) -> Unit,
    onReview: (BackgroundSyncViewModel.Row) -> Unit,
    onOpen: (BackgroundSyncViewModel.Row) -> Unit,
) {
    val c = M3.colors
    val editable = !state.busy && state.recovery == null
    Column(Modifier.fillMaxSize().background(c.surface).testTag("background-screen")) {
        M3TopBar(stringResource(R.string.settings_m3_background_sync),
            subtitle = pluralStringResource(R.plurals.settings_m3_bg_included, state.includedCount, state.includedCount), onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsIntro(stringResource(R.string.settings_m3_bg_intro))
            if (state.busy) M3IndeterminateProgressBar(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
            if (state.failed) M3Note(stringResource(R.string.background_error), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("background-error"),
                tone = M3NoteTone.Warn)
            if (state.recovery != null) M3Note(stringResource(R.string.background_reset_detail), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                tone = M3NoteTone.Warn, action = {
                    M3OutlinedButton(stringResource(R.string.background_reset), onReset, Modifier.testTag("background-reset"), size = M3ButtonSize.Medium,
                        destructive = true, enabled = !state.busy)
                })
            M3Group(stringResource(R.string.settings_m3_bg_modes)) {
                M3SettingsRow(stringResource(R.string.background_pull), Modifier.testTag("background-pull"), subtitle = stringResource(R.string.settings_m3_bg_pull_sub),
                    checked = state.configuration.pull, onCheckedChange = onPull, enabled = editable)
                M3SettingsRow(stringResource(R.string.background_push), Modifier.testTag("background-push"), subtitle = stringResource(R.string.settings_m3_bg_push_sub),
                    checked = state.configuration.push, onCheckedChange = onPush, enabled = editable, divider = true)
            }
            M3Group(stringResource(R.string.settings_m3_bg_only_when)) {
                M3SettingsRow(stringResource(R.string.settings_m3_bg_wifi), Modifier.testTag("background-wifi"), icon = R.drawable.ic_m3_wifi,
                    checked = state.wifiOnly, onCheckedChange = onWifi, enabled = editable)
                M3SettingsRow(stringResource(R.string.settings_m3_bg_charging), Modifier.testTag("background-power"), icon = R.drawable.ic_m3_battery,
                    checked = state.chargingOnly, onCheckedChange = onCharging, enabled = editable, divider = true)
            }
            M3Group(stringResource(R.string.settings_m3_push_title)) {
                M3SettingsRow(stringResource(R.string.settings_m3_bg_nudge), Modifier.testTag("background-push-sync"), icon = R.drawable.ic_m3_notifications,
                    subtitle = stringResource(R.string.settings_m3_bg_nudge_sub),
                    value = stringResource(if (pushSyncOn) R.string.settings_m3_on else R.string.settings_m3_bg_connect), onClick = onPushSync)
            }
            if (state.rows.isNotEmpty()) M3Group(stringResource(R.string.settings_m3_bg_vaults)) {
                state.rows.forEachIndexed { index, row -> VaultRow(row, state.busy, state.recovery == null, index > 0, onExclude, onForget, onReview, onOpen) }
            } else if (state.ready) M3Note(stringResource(R.string.background_empty), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Muted)
            M3SectionLabel(stringResource(R.string.settings_m3_bg_never))
            SettingsChipFlow {
                listOf(R.string.settings_m3_never_force, R.string.settings_m3_never_rebase, R.string.settings_m3_never_switch, R.string.settings_m3_never_resolve,
                    R.string.settings_m3_never_overwrite, R.string.settings_m3_never_send).forEach { M3InfoChip(stringResource(it)) }
            }
            M3Group(stringResource(R.string.settings_m3_bg_recent)) {
                val rows = runRows(state.runs)
                if (rows.isEmpty()) Text(stringResource(R.string.settings_m3_bg_no_runs), Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = M3Type.bodyMedium, color = c.onVar)
                rows.forEachIndexed { index, row -> M3DotRow(row.label, row.dot, Modifier.testTag("background-run-$index"), trailing = row.time, divider = index > 0) }
            }
            Spacer(Modifier.height(32.dp))
        }
        M3BottomActionBar {
            if (state.running || state.queued) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    M3IndeterminateProgressBar(Modifier.weight(1f))
                    M3TextButton(stringResource(R.string.background_stop), onStop, Modifier.testTag("background-stop"), enabled = !state.busy)
                }
                Text(stringResource(if (state.running) R.string.background_running else R.string.background_queued), style = M3Type.meta, color = c.onVar)
            } else {
                M3FilledButton(stringResource(R.string.background_run), onRun, Modifier.fillMaxWidth().testTag("background-run"), size = M3ButtonSize.XLarge,
                    enabled = state.configuration.active && !state.busy && state.recovery == null)
            }
        }
    }
}

@Composable
private fun VaultRow(row: BackgroundSyncViewModel.Row, busy: Boolean, reviewable: Boolean, divider: Boolean,
    onExclude: (BackgroundSyncViewModel.Row) -> Unit, onForget: (BackgroundSyncViewModel.Row) -> Unit,
    onReview: (BackgroundSyncViewModel.Row) -> Unit, onOpen: (BackgroundSyncViewModel.Row) -> Unit) {
    val included = row.policy?.included == true
    val meta = when {
        !row.available -> stringResource(R.string.settings_m3_bg_vault_missing)
        row.policy == null -> stringResource(R.string.settings_m3_bg_vault_not_included)
        else -> {
            val health = row.policy.health
            val label = stringResource(healthLabel(health.kind))
            if (health.attemptedAt > 0) "$label · ${relativeAgo(health.attemptedAt)}" else label
        }
    }
    M3ListRow(row.target.name, Modifier.testTag("background-row-${row.target.path}"), meta = meta, metaMono = false,
        divider = divider, onClick = if (row.available) ({ onOpen(row) }) else null,
        trailing = {
            if (row.available) M3Switch(included, onCheckedChange = { if (it) onReview(row) else onExclude(row) },
                Modifier.testTag("background-include-${row.target.path}"), enabled = !busy && reviewable,
                contentDescription = stringResource(R.string.settings_m3_bg_include_cd, row.target.name))
            else M3TextButton(stringResource(R.string.background_forget), { onForget(row) }, destructive = true, enabled = !busy)
        })
}

private data class RunRow(val label: String, val time: String, val dot: Color)

/** One row per vault that changed or stopped; a quiet pass reads "4 vaults, nothing new". Newest first, at most 6. */
@Composable
private fun runRows(runs: List<BackgroundRun>): List<RunRow> {
    val c = M3.colors
    val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    val rows = mutableListOf<RunRow>()
    for (run in runs) {
        val time = if (System.currentTimeMillis() - run.time < 24 * 60 * 60 * 1000L) format.format(java.util.Date(run.time)) else relativeAgo(run.time)
        val notable = run.vaults.filter { it.kind != BackgroundHealthKind.UP_TO_DATE }
        if (notable.isEmpty()) rows += RunRow(pluralStringResource(R.plurals.settings_m3_bg_run_quiet, run.vaults.size, run.vaults.size), time, c.ok)
        notable.forEach { rows += RunRow(runLabel(it), time, runDot(it)) }
        if (rows.size >= 6) break
    }
    return rows.take(6)
}

@Composable
private fun runLabel(v: BackgroundRunVault): String = when (v.kind) {
    BackgroundHealthKind.UPDATED -> listOfNotNull(v.name, if (v.pulled > 0) "↓${v.pulled}" else null,
        if (v.pushed) stringResource(R.string.settings_m3_bg_run_pushed) else null).joinToString(" ")
    BackgroundHealthKind.ATTENTION -> if (v.problem == RepositoryActionProblem.LOCAL_CHANGES) stringResource(R.string.settings_m3_bg_run_local_edits, v.name)
        else stringResource(R.string.settings_m3_bg_run_review, v.name)
    BackgroundHealthKind.WAIT_WIFI -> stringResource(R.string.settings_m3_bg_run_wifi, v.name)
    BackgroundHealthKind.WAIT_POWER -> stringResource(R.string.settings_m3_bg_run_power, v.name)
    BackgroundHealthKind.WAIT_NETWORK -> stringResource(R.string.settings_m3_bg_run_offline, v.name)
    BackgroundHealthKind.FAILED, BackgroundHealthKind.UNAVAILABLE -> stringResource(R.string.settings_m3_bg_run_failed, v.name)
    else -> stringResource(R.string.settings_m3_bg_run_skipped, v.name, stringResource(healthLabel(v.kind)))
}

@Composable
private fun runDot(v: BackgroundRunVault): Color = when (v.kind) {
    BackgroundHealthKind.UPDATED, BackgroundHealthKind.UP_TO_DATE -> M3.colors.ok
    BackgroundHealthKind.FAILED, BackgroundHealthKind.UNAVAILABLE -> M3.colors.error
    else -> M3.colors.ahead
}

internal fun healthLabel(kind: BackgroundHealthKind) = when (kind) {
    BackgroundHealthKind.NEVER -> R.string.background_never
    BackgroundHealthKind.WAIT_NETWORK -> R.string.background_wait_network
    BackgroundHealthKind.WAIT_WIFI -> R.string.background_wait_wifi
    BackgroundHealthKind.WAIT_POWER -> R.string.background_wait_power
    BackgroundHealthKind.WRONG_BRANCH -> R.string.background_wrong_branch
    BackgroundHealthKind.CHANGED -> R.string.background_changed
    BackgroundHealthKind.UNAVAILABLE -> R.string.background_unavailable
    BackgroundHealthKind.UP_TO_DATE -> R.string.background_current
    BackgroundHealthKind.UPDATED -> R.string.background_updated
    BackgroundHealthKind.ATTENTION -> R.string.settings_m3_bg_attention_short
    BackgroundHealthKind.FAILED -> R.string.settings_m3_bg_failed_short
    BackgroundHealthKind.STOPPED -> R.string.background_stopped
}

// ─── Previews ───────────────────────────────────────────────────────────────

private fun previewPolicy(kind: BackgroundHealthKind) = BackgroundPolicy(
    "00000000-0000-0000-0000-000000000001", PullAllTarget("/storage/emulated/0/Documents/Notes", "notes"), "github:1",
    "main", "origin", "main", "https://github.com/me/notes.git", "https://github.com/me/notes.git", null,
    health = BackgroundHealth(kind, attemptedAt = System.currentTimeMillis() - 14 * 60_000L),
)

private fun previewState(running: Boolean = false): BackgroundSyncViewModel.State {
    val policy = previewPolicy(BackgroundHealthKind.UPDATED)
    val now = System.currentTimeMillis()
    return BackgroundSyncViewModel.State(
        ready = true, running = running, wifiOnly = true,
        configuration = BackgroundConfiguration(pull = true, push = false, policies = mapOf(policy.target.path to policy)),
        rows = listOf(BackgroundSyncViewModel.Row(policy.target, policy, true), BackgroundSyncViewModel.Row(PullAllTarget("/storage/emulated/0/VoxVault", "VoxVault"), null, true)),
        runs = listOf(
            BackgroundRun(now - 60_000, listOf(BackgroundRunVault("/n", "notes", BackgroundHealthKind.UPDATED, null, null, pulled = 2))),
            BackgroundRun(now - 3 * 3_600_000, List(4) { BackgroundRunVault("/$it", "v$it", BackgroundHealthKind.UP_TO_DATE, null, null) }),
            BackgroundRun(now - 5 * 3_600_000, listOf(BackgroundRunVault("/v", "VoxVault", BackgroundHealthKind.ATTENTION, null, RepositoryActionProblem.LOCAL_CHANGES))),
        ),
    )
}

@Composable
private fun BackgroundPreview(state: BackgroundSyncViewModel.State) =
    BackgroundSyncContent(state, false, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(name = "17 · Background Sync", heightDp = 1500)
@Composable private fun BackgroundOnPreview() = SyncMdTheme(darkTheme = false) { BackgroundPreview(previewState()) }

@Preview(name = "17 · Background Sync · running · dark", heightDp = 1500)
@Composable private fun BackgroundRunningDarkPreview() = SyncMdTheme(darkTheme = true) { BackgroundPreview(previewState(running = true)) }
