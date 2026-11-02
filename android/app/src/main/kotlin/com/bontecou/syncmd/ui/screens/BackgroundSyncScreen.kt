package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.services.background.*
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.BackgroundSyncViewModel

// iOS PremiumSettingsView (Background Sync) — grouped Tide cards.

@Composable
fun BackgroundSyncScreen(model: BackgroundSyncViewModel, onBack: () -> Unit, onOpenRepository: (String) -> Unit = {}) {
    val state by model.state.collectAsState()
    var publish by remember { mutableStateOf<Triple<Boolean, Boolean, Long>?>(null) }
    var reset by remember { mutableStateOf<String?>(null) }
    var wifi by remember { mutableStateOf(false) }; var power by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(state.review) {
        val policy = state.configuration.policies[state.review?.target?.path]
        wifi = policy?.wifiOnly ?: false; power = policy?.externalPowerOnly ?: false
    }
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
        onBack = { model.stopReview(); onBack() },
        onRun = model::runNow,
        onStop = model::stopWork,
        onPull = { model.modes(it, state.configuration.push, state.configuration.generation) },
        onPush = {
            if (it) publish = Triple(state.configuration.pull, true, state.configuration.generation)
            else model.modes(state.configuration.pull, false, state.configuration.generation)
        },
        onReset = { reset = state.recovery },
        onReload = model::reload,
        onExclude = model::exclude,
        onForget = model::forget,
        onReview = model::review,
        onOpen = { onOpenRepository(it.target.path) },
    )
    publish?.let { request ->
        TideDialog(
            onDismissRequest = { publish = null },
            icon = { TideModalIcon("↑", Tide.colors.ahead) },
            title = stringResource(R.string.background_push_confirm),
            text = { Text(stringResource(R.string.background_push_detail)) },
            confirmButton = {
                BPrimaryButton(stringResource(R.string.background_allow_push), Modifier.testTag("background-confirm-push"),
                    onClick = { publish = null; model.modes(request.first, request.second, request.third) })
            },
            dismissButton = { BGhostButton(stringResource(android.R.string.cancel), Modifier.fillMaxWidth(), onClick = { publish = null }) },
        )
    }
    state.review?.let { plan ->
        val c = Tide.colors
        TideDialog(
            onDismissRequest = model::dismissReview,
            title = plan.target.name,
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(plan.target.path, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                    Text(stringResource(R.string.background_binding, plan.branch.orEmpty(), plan.remote.orEmpty(), plan.remoteBranch.orEmpty()),
                        style = TideType.sans(14.sp), color = c.inkMid)
                    listOfNotNull(plan.fetchUrl, plan.pushUrl).distinct().forEach { address ->
                        com.bontecou.syncmd.services.repositories.RepositoryDiscovery.redactRemoteUrl(address)?.let {
                            Text(it, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkMid)
                        }
                    }
                    SettingsAreaSection(null, Modifier.padding(top = 6.dp)) {
                        SettingsAreaSwitchRow(stringResource(R.string.background_wifi), wifi, enabled = !state.busy, tag = "background-wifi") { wifi = it }
                        SettingsAreaSeparator()
                        SettingsAreaSwitchRow(stringResource(R.string.background_power), power, enabled = !state.busy, tag = "background-power") { power = it }
                    }
                }
            },
            confirmButton = {
                BPrimaryButton(stringResource(R.string.background_include), Modifier.testTag("background-confirm-include"),
                    isDisabled = state.busy, onClick = { model.include(wifi, power) })
            },
            dismissButton = {
                SettingsAreaPill(stringResource(android.R.string.cancel), model::dismissReview, Modifier.fillMaxWidth(),
                    style = SettingsPillStyle.TEXT, enabled = !state.busy)
            },
        )
    }
    reset?.let { version ->
        TideDialog(
            onDismissRequest = { reset = null },
            icon = { TideModalIcon() },
            title = stringResource(R.string.background_reset),
            text = { Text(stringResource(R.string.background_reset_detail)) },
            confirmButton = {
                BDestructiveButton(stringResource(R.string.background_reset), Modifier.testTag("background-confirm-reset"),
                    onClick = { reset = null; model.reset(version) })
            },
            dismissButton = { BGhostButton(stringResource(android.R.string.cancel), Modifier.fillMaxWidth(), onClick = { reset = null }) },
        )
    }
}

@Composable
private fun BackgroundSyncContent(
    state: BackgroundSyncViewModel.State,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onPull: (Boolean) -> Unit,
    onPush: (Boolean) -> Unit,
    onReset: () -> Unit,
    onReload: () -> Unit,
    onExclude: (BackgroundSyncViewModel.Row) -> Unit,
    onForget: (BackgroundSyncViewModel.Row) -> Unit,
    onReview: (BackgroundSyncViewModel.Row) -> Unit,
    onOpen: (BackgroundSyncViewModel.Row) -> Unit,
) {
    val c = Tide.colors
    val editable = !state.busy && state.recovery == null
    Scaffold(containerColor = c.paper, topBar = {
        WrappingTopBar(title = stringResource(R.string.background_title), backLabel = stringResource(R.string.pull_all_back), onBack = onBack)
    }, bottomBar = {
        Column(Modifier.fillMaxWidth().background(c.paper)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Tide.gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.running || state.queued) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TidePulse(color = if (state.running) c.behind else c.inkMid)
                        Text(stringResource(if (state.running) R.string.background_running else R.string.background_queued),
                            style = TideType.sans(14.sp, FontWeight.Medium), color = c.inkMid)
                    }
                    BSecondaryButton(stringResource(R.string.background_stop), Modifier.testTag("background-stop"), isDisabled = state.busy, onClick = onStop)
                } else {
                    BPrimaryButton(stringResource(R.string.background_run), Modifier.testTag("background-run"),
                        isDisabled = !(state.configuration.active && !state.busy && state.recovery == null), onClick = onRun)
                }
            }
        }
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("background-screen"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "scope") {
                Text(stringResource(R.string.background_scope), Modifier.padding(horizontal = 4.dp), style = TideType.copy, color = c.inkMid)
            }
            item(key = "mode-state") { BackgroundStatusCard(state.configuration.active) }
            item(key = "modes") {
                SettingsAreaSection(null) {
                    SettingsAreaSwitchRow(stringResource(R.string.background_pull), state.configuration.pull, enabled = editable, tag = "background-pull", onChange = onPull)
                    SettingsAreaSeparator()
                    SettingsAreaSwitchRow(stringResource(R.string.background_push), state.configuration.push, enabled = editable, tag = "background-push", onChange = onPush)
                }
            }
            if (state.busy) item(key = "busy") { SettingsAreaProgress() }
            if (state.failed) item(key = "error") {
                SettingsAreaNotice(stringResource(R.string.background_error), tint = c.danger, textModifier = Modifier.testTag("background-error"))
            }
            if (state.recovery != null) item(key = "recovery") {
                SettingsAreaNotice(stringResource(R.string.background_reset_detail), tint = c.danger) {
                    SettingsAreaPill(stringResource(R.string.background_reset), onReset, Modifier.testTag("background-reset"),
                        style = SettingsPillStyle.DANGER_OUTLINE, enabled = !state.busy)
                }
            }
            item(key = "reload") {
                SettingsAreaPill(stringResource(R.string.background_reload), onReload, enabled = !state.busy)
            }
            if (state.ready && state.rows.isEmpty()) item(key = "empty") {
                val shape = RoundedCornerShape(Tide.radiusCard)
                Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape).padding(horizontal = 18.dp, vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.background_empty), style = TideType.sans(14.sp), color = c.inkMid, textAlign = TextAlign.Center)
                }
            }
            items(state.rows, key = { it.target.path }) { row ->
                BackgroundRepositoryCard(row, state.busy, state.recovery == null, onExclude, onForget, onReview, onOpen)
            }
        }
    }
}

@Composable
private fun BackgroundStatusCard(active: Boolean) {
    val c = Tide.colors
    val shape = RoundedCornerShape(Tide.radiusCard)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(10.dp).background(if (active) c.synced else c.inkFaint, CircleShape))
            Text(stringResource(if (active) R.string.background_active else R.string.background_inactive),
                Modifier.weight(1f), style = TideType.sans(15.sp, FontWeight.Medium), color = c.ink)
            TidePill(
                stringResource(if (active) R.string.tide_settings_on else R.string.tide_settings_off),
                fg = if (active) c.synced else c.inkMid,
                bg = if (active) c.synced.copy(alpha = 0.12f) else c.well,
            )
        }
        SettingsAreaSeparator()
        Text(stringResource(R.string.background_schedule), Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            style = TideType.sans(12.5.sp), color = c.inkMid)
    }
}

@Composable
private fun BackgroundRepositoryCard(
    row: BackgroundSyncViewModel.Row,
    busy: Boolean,
    reviewable: Boolean,
    onExclude: (BackgroundSyncViewModel.Row) -> Unit,
    onForget: (BackgroundSyncViewModel.Row) -> Unit,
    onReview: (BackgroundSyncViewModel.Row) -> Unit,
    onOpen: (BackgroundSyncViewModel.Row) -> Unit,
) {
    val c = Tide.colors
    val shape = RoundedCornerShape(Tide.radiusCard)
    val included = row.policy?.included == true
    Column(Modifier.fillMaxWidth().testTag("background-row-${row.target.path}").clip(shape).background(c.card, shape)) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(row.target.name, Modifier.weight(1f), style = TideType.sans(17.sp, FontWeight.SemiBold), color = c.ink)
                Text(stringResource(if (included) R.string.background_included else R.string.background_excluded),
                    style = TideType.mono(12.sp, FontWeight.SemiBold), color = if (included) c.synced else c.inkMid)
            }
            Text(row.target.path, style = TideType.mono(11.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
            if (!row.available) {
                Text(stringResource(R.string.background_unavailable), Modifier.padding(top = 4.dp),
                    style = TideType.sans(13.sp, FontWeight.Medium), color = c.ahead)
            }
            row.policy?.let { policy ->
                Text(stringResource(R.string.background_binding, policy.branch, policy.remote, policy.upstream),
                    Modifier.padding(top = 4.dp), style = TideType.sans(12.5.sp), color = c.inkMid)
                Text(stringResource(healthLabel(policy.health.kind)), Modifier.padding(top = 4.dp).testTag("background-health-${row.target.path}"),
                    style = TideType.sans(13.sp, FontWeight.SemiBold), color = healthTint(policy.health.kind))
                if (policy.health.attemptedAt > 0) Text(stringResource(R.string.background_attempt, formatted(policy.health.attemptedAt)),
                    style = TideType.mono(11.sp), color = c.inkFaint)
                if (policy.health.transferredAt > 0) Text(stringResource(R.string.background_transfer, formatted(policy.health.transferredAt)),
                    style = TideType.mono(11.sp), color = c.inkFaint)
            }
        }
        SettingsAreaSeparator()
        SettingsAreaRow(
            stringResource(if (row.policy == null) R.string.background_review else R.string.background_review_again),
            Modifier.testTag("background-review-${row.target.path}"),
            accessory = SettingsAccessory.CHEVRON, enabled = row.available && !busy && reviewable, onClick = { onReview(row) },
        )
        if (row.available) {
            SettingsAreaSeparator()
            SettingsAreaRow(stringResource(R.string.background_open), accessory = SettingsAccessory.CHEVRON, onClick = { onOpen(row) })
        }
        row.policy?.let { policy ->
            if (policy.included) {
                SettingsAreaSeparator()
                SettingsAreaRow(stringResource(R.string.background_exclude), Modifier.testTag("background-exclude-${row.target.path}"),
                    enabled = !busy, onClick = { onExclude(row) })
            }
            SettingsAreaSeparator()
            SettingsAreaRow(stringResource(R.string.background_forget), titleTint = c.danger, enabled = !busy, onClick = { onForget(row) })
        }
    }
}

private fun formatted(time: Long) = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(time))
private fun healthLabel(kind: BackgroundHealthKind) = when (kind) {
    BackgroundHealthKind.NEVER -> R.string.background_never
    BackgroundHealthKind.WAIT_NETWORK -> R.string.background_wait_network
    BackgroundHealthKind.WAIT_WIFI -> R.string.background_wait_wifi
    BackgroundHealthKind.WAIT_POWER -> R.string.background_wait_power
    BackgroundHealthKind.WRONG_BRANCH -> R.string.background_wrong_branch
    BackgroundHealthKind.CHANGED -> R.string.background_changed
    BackgroundHealthKind.UNAVAILABLE -> R.string.background_unavailable
    BackgroundHealthKind.UP_TO_DATE -> R.string.background_current
    BackgroundHealthKind.UPDATED -> R.string.background_updated
    BackgroundHealthKind.ATTENTION -> R.string.background_attention
    BackgroundHealthKind.FAILED -> R.string.background_failed
    BackgroundHealthKind.STOPPED -> R.string.background_stopped
}

@Composable
private fun healthTint(kind: BackgroundHealthKind): Color = when (kind) {
    BackgroundHealthKind.UP_TO_DATE, BackgroundHealthKind.UPDATED -> Tide.colors.synced
    BackgroundHealthKind.NEVER, BackgroundHealthKind.STOPPED -> Tide.colors.inkMid
    BackgroundHealthKind.WAIT_NETWORK, BackgroundHealthKind.WAIT_WIFI, BackgroundHealthKind.WAIT_POWER -> Tide.colors.behind
    BackgroundHealthKind.WRONG_BRANCH, BackgroundHealthKind.CHANGED, BackgroundHealthKind.ATTENTION -> Tide.colors.ahead
    BackgroundHealthKind.UNAVAILABLE, BackgroundHealthKind.FAILED -> Tide.colors.danger
}

// ─── Previews ───────────────────────────────────────────────────────────────

private fun previewPolicy(kind: BackgroundHealthKind) = BackgroundPolicy(
    "00000000-0000-0000-0000-000000000001", PullAllTarget("/storage/emulated/0/Documents/Notes", "Notes"), "github:1",
    "main", "origin", "main", "https://github.com/me/notes.git", "https://github.com/me/notes.git", null,
    health = BackgroundHealth(kind, attemptedAt = 1_735_689_600_000L, transferredAt = 1_735_689_600_000L),
)

private fun previewState(active: Boolean, kind: BackgroundHealthKind = BackgroundHealthKind.UP_TO_DATE, running: Boolean = false,
    failed: Boolean = false, recovery: String? = null, empty: Boolean = false): BackgroundSyncViewModel.State {
    val policy = previewPolicy(kind)
    return BackgroundSyncViewModel.State(
        ready = true,
        configuration = BackgroundConfiguration(pull = active, push = false, policies = if (active) mapOf(policy.target.path to policy) else emptyMap()),
        rows = if (empty) emptyList() else listOf(
            BackgroundSyncViewModel.Row(policy.target, if (active) policy else null, true),
            BackgroundSyncViewModel.Row(PullAllTarget("/storage/emulated/0/Old", "Old vault"), null, false),
        ),
        running = running, failed = failed, recovery = recovery,
    )
}

@Composable
private fun BackgroundPreview(state: BackgroundSyncViewModel.State) =
    BackgroundSyncContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(name = "Background · on", heightDp = 1400)
@Composable private fun BackgroundOnPreview() = SyncMdTheme(darkTheme = false) { BackgroundPreview(previewState(true)) }

@Preview(name = "Background · on, running · dark", heightDp = 1400)
@Composable private fun BackgroundRunningDarkPreview() = SyncMdTheme(darkTheme = true) {
    BackgroundPreview(previewState(true, BackgroundHealthKind.WAIT_WIFI, running = true))
}

@Preview(name = "Background · off")
@Composable private fun BackgroundOffPreview() = SyncMdTheme(darkTheme = false) { BackgroundPreview(previewState(false)) }

@Preview(name = "Background · attention + error · dark", heightDp = 1400)
@Composable private fun BackgroundErrorDarkPreview() = SyncMdTheme(darkTheme = true) {
    BackgroundPreview(previewState(true, BackgroundHealthKind.ATTENTION, failed = true))
}

@Preview(name = "Background · recovery, empty")
@Composable private fun BackgroundRecoveryPreview() = SyncMdTheme(darkTheme = false) {
    BackgroundPreview(previewState(false, recovery = "v1", empty = true))
}
