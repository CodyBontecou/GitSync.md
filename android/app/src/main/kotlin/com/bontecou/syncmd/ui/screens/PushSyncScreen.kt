package com.bontecou.syncmd.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.services.automation.RepositoryActionOutcome
import com.bontecou.syncmd.services.push.PushHintResult
import com.bontecou.syncmd.services.push.PushInstallation
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors
import com.bontecou.syncmd.ui.viewmodels.PushSyncViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// iOS PushSyncSettingsContent — toggle card, GitHub connections, disclosure.

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PushSyncScreen(model: PushSyncViewModel, onBack: () -> Unit, onOpenRepository: (String) -> Unit = {}) {
    val state by model.state.collectAsState(); val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var unlink by remember { mutableStateOf<PushInstallation?>(null) }
    var requestingPermission by rememberSaveable { mutableStateOf(false) }
    var showExisting by rememberSaveable { mutableStateOf(false) }
    var existingAccount by rememberSaveable { mutableStateOf("") }
    val awaitingPermission by rememberUpdatedState(requestingPermission)
    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { model.openFailed() }
        catch (_: SecurityException) { model.openFailed() }
    }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(owner, model) {
        var resumeReload: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !awaitingPermission) {
                resumeReload?.cancel()
                val waitingForAction = model.state.value.busy
                resumeReload = scope.launch {
                    val completed = model.state.first { it.ready && !it.busy }
                    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !awaitingPermission &&
                        (!waitingForAction || completed.error == null)) model.reload()
                }
            }
            if (event == Lifecycle.Event.ON_STOP) { resumeReload?.cancel(); unlink = null; model.stopPull() }
        }
        owner.lifecycle.addObserver(observer); onDispose { resumeReload?.cancel(); owner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.setEnabled(true) else model.permissionDenied()
        requestingPermission = false
    }
    BackHandler { model.stopPull(); onBack() }
    PushSyncContent(
        state = state,
        showExisting = showExisting,
        existingAccount = existingAccount,
        onBack = { model.stopPull(); onBack() },
        onOpenRepository = onOpenRepository,
        onToggle = { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33 && !state.allowed) { requestingPermission = true; permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            else model.setEnabled(enabled)
        },
        onOpenSettings = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
        onStop = model::stop,
        onLink = model::link,
        onToggleExisting = { showExisting = !showExisting },
        onExistingAccount = { if (it.length <= 101) existingAccount = it },
        onExistingDone = { keyboard?.hide(); focus.clearFocus() },
        onLinkExisting = { model.linkExisting(existingAccount) },
        onRefresh = model::refresh,
        onManage = { open(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it.htmlURL))) },
        onUnlink = { unlink = it },
    )
    unlink?.let { installation ->
        TideDialog(
            onDismissRequest = { unlink = null },
            icon = { TideModalIcon() },
            title = stringResource(R.string.push_unlink),
            text = { Text(stringResource(R.string.push_unlink_scope)) },
            confirmButton = {
                BDestructiveButton(stringResource(R.string.push_unlink), Modifier.testTag("push-unlink-confirm"),
                    onClick = { unlink = null; model.unlink(installation.id) })
            },
            dismissButton = { BGhostButton(stringResource(R.string.repository_actions_cancel), Modifier.fillMaxWidth(), onClick = { unlink = null }) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PushSyncContent(
    state: PushSyncViewModel.State,
    showExisting: Boolean,
    existingAccount: String,
    onBack: () -> Unit,
    onOpenRepository: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onStop: () -> Unit,
    onLink: () -> Unit,
    onToggleExisting: () -> Unit,
    onExistingAccount: (String) -> Unit,
    onExistingDone: () -> Unit,
    onLinkExisting: () -> Unit,
    onRefresh: () -> Unit,
    onManage: (PushInstallation) -> Unit,
    onUnlink: (PushInstallation) -> Unit,
) {
    val c = Tide.colors
    val canLink = !state.busy && state.configured && state.registeredAt > 0
    Scaffold(containerColor = c.paper, topBar = {
        WrappingTopBar(title = stringResource(R.string.push_title), backLabel = stringResource(R.string.pull_all_back), onBack = onBack)
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("push-sync-screen"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.checked?.let { count -> item(key = "summary") {
                Text(stringResource(R.string.push_result, count, state.updated, state.attention),
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("push-sync-result"),
                    style = TideType.mono(12.sp), color = c.inkMid)
            } }
            items(state.results, key = { "result:${it.target.path}" }) { result -> PushResultCard(result, onOpenRepository) }

            item(key = "scope") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.push_scope), Modifier.padding(horizontal = 4.dp), style = TideType.copy, color = c.inkMid)
                    SettingsAreaSection(null) {
                        SettingsAreaSwitchRow(stringResource(R.string.push_enable), state.enabled,
                            enabled = !state.busy && (state.enabled || state.configured), tag = "push-sync-toggle", onChange = onToggle)
                    }
                }
            }
            if (!state.configured) item(key = "unavailable") { SettingsAreaNotice(stringResource(R.string.push_unavailable), tint = c.inkMid) }
            if (!state.allowed) item(key = "permission") {
                SettingsAreaNotice(stringResource(R.string.push_permission), tint = c.ahead) {
                    SettingsAreaPill(stringResource(R.string.push_open_settings), onOpenSettings, style = SettingsPillStyle.OUTLINE)
                }
            }
            if (state.busy) item(key = "busy") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsAreaProgress()
                    SettingsAreaPill(stringResource(R.string.repository_actions_stop), onStop, style = SettingsPillStyle.OUTLINE)
                }
            }
            state.error?.let { error -> item(key = "error") {
                SettingsAreaNotice(stringResource(when (error) {
                    "permission" -> R.string.push_permission
                    "owner" -> R.string.push_owner
                    "cancelled" -> R.string.push_cancelled
                    "stopped" -> R.string.push_stopped
                    "no_match" -> R.string.push_no_match
                    "open" -> R.string.push_open_failed
                    else -> R.string.push_failed
                }), tint = c.danger, textModifier = Modifier.testTag("push-sync-error"))
            } }
            if (state.enabled) {
                item(key = "registration") {
                    val registered = state.registeredAt > 0
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(if (registered) c.synced else c.ahead, androidx.compose.foundation.shape.CircleShape))
                        Text(stringResource(if (registered) R.string.push_registered else R.string.push_registration_pending),
                            style = TideType.mono(11.5.sp), color = if (registered) c.inkFaint else c.ahead)
                    }
                }
                item(key = "link") {
                    SettingsAreaSection(null) {
                        SettingsAreaRow(stringResource(R.string.push_link), Modifier.testTag("push-link"), titleTint = c.behind,
                            accessory = SettingsAccessory.EXTERNAL, enabled = canLink, onClick = onLink)
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.push_existing), Modifier.testTag("push-existing-toggle"),
                            accessory = SettingsAccessory.CHEVRON, enabled = !state.busy && state.registeredAt > 0, onClick = onToggleExisting)
                        if (showExisting) {
                            SettingsAreaSeparator()
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.push_existing_scope), style = TideType.sans(12.5.sp), color = c.inkMid)
                                OutlinedTextField(value = existingAccount, onValueChange = onExistingAccount,
                                    label = { Text(stringResource(R.string.push_existing_account)) }, enabled = !state.busy, singleLine = true,
                                    textStyle = TideType.mono(15.sp).copy(textDirection = TextDirection.Ltr),
                                    shape = Tide.fieldShape, colors = tideFieldColors(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { onExistingDone() }),
                                    modifier = Modifier.fillMaxWidth().testTag("push-existing-account"))
                                BPrimaryButton(stringResource(R.string.push_existing_connect), Modifier.testTag("push-existing-connect"),
                                    isDisabled = !(canLink && runCatching { com.bontecou.syncmd.services.push.PushSyncProtocol.existingAccount(existingAccount) }.isSuccess),
                                    onClick = onLinkExisting)
                            }
                        }
                    }
                }
                item(key = "refresh") { SettingsAreaPill(stringResource(R.string.push_refresh), onRefresh, enabled = !state.busy) }
                if (state.connectionsChecked && state.installations.isEmpty() && !state.busy) item(key = "no-connections") {
                    Text(stringResource(R.string.push_no_connections), Modifier.padding(horizontal = 4.dp),
                        style = TideType.sans(13.sp, FontWeight.Medium), color = c.ahead)
                }
                if (state.installations.isNotEmpty()) item(key = "installations") {
                    SettingsAreaSection(null) {
                        state.installations.forEachIndexed { index, installation ->
                            if (index > 0) SettingsAreaSeparator()
                            PushInstallationRow(installation, busy = state.busy, onManage = { onManage(installation) }, onUnlink = { onUnlink(installation) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PushInstallationRow(installation: PushInstallation, busy: Boolean, onManage: () -> Unit, onUnlink: () -> Unit) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(26.dp).background(c.well, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).background(if (installation.suspended) c.danger else c.synced, androidx.compose.foundation.shape.CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("@${installation.login}", style = TideType.mono(13.sp, FontWeight.SemiBold).copy(textDirection = TextDirection.Ltr), color = c.ink)
                Text(stringResource(if (installation.suspended) R.string.push_suspended else if (installation.allRepositories) R.string.push_all_repositories else R.string.push_selected_repositories),
                    style = TideType.sans(12.5.sp), color = if (installation.suspended) c.danger else c.inkMid)
            }
        }
        FlowRow(Modifier.padding(start = 30.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsAreaPill(stringResource(R.string.push_manage_github), onManage, style = SettingsPillStyle.BEHIND)
            SettingsAreaPill(stringResource(R.string.push_unlink), onUnlink, style = SettingsPillStyle.DANGER_TEXT, enabled = !busy)
        }
    }
}

@Composable
private fun PushResultCard(result: PushHintResult, onOpenRepository: (String) -> Unit) {
    val c = Tide.colors
    val shape = RoundedCornerShape(Tide.radiusCard)
    val updated = result.result.pullUpdated
    val current = result.result.outcome == RepositoryActionOutcome.UP_TO_DATE
    Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape)) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(result.target.name, style = TideType.sans(17.sp, FontWeight.SemiBold), color = c.ink)
            Text(stringResource(if (updated) R.string.push_result_updated else if (current) R.string.push_result_current else R.string.push_result_review),
                style = TideType.sans(13.sp, FontWeight.Medium), color = if (updated || current) c.synced else c.ahead)
        }
        SettingsAreaSeparator()
        SettingsAreaRow(stringResource(R.string.push_open_repository), Modifier.testTag("push-open-repository"),
            accessory = SettingsAccessory.CHEVRON, onClick = { onOpenRepository(result.target.path) })
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewInstallations = listOf(
    PushInstallation(1, "codybontecou", false, false, "https://github.com/settings/installations/1", false, 0),
    PushInstallation(2, "isolated-tech", true, true, "https://github.com/settings/installations/2", true, 0),
)

@Composable
private fun PushPreview(state: PushSyncViewModel.State, existing: Boolean = false) =
    PushSyncContent(state, existing, "isolated-tech", {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(name = "Push · off")
@Composable private fun PushOffPreview() = SyncMdTheme(darkTheme = false) { PushPreview(PushSyncViewModel.State(ready = true, configured = true)) }

@Preview(name = "Push · off, permission denied · dark")
@Composable private fun PushPermissionDarkPreview() = SyncMdTheme(darkTheme = true) {
    PushPreview(PushSyncViewModel.State(ready = true, configured = true, allowed = false, error = "permission"))
}

@Preview(name = "Push · linked", heightDp = 1200)
@Composable private fun PushLinkedPreview() = SyncMdTheme(darkTheme = false) {
    PushPreview(PushSyncViewModel.State(ready = true, enabled = true, configured = true, registeredAt = 1, installations = previewInstallations, connectionsChecked = true), existing = true)
}

@Preview(name = "Push · linked · dark", heightDp = 1200)
@Composable private fun PushLinkedDarkPreview() = SyncMdTheme(darkTheme = true) {
    PushPreview(PushSyncViewModel.State(ready = true, enabled = true, configured = true, registeredAt = 1, installations = previewInstallations, connectionsChecked = true))
}

@Preview(name = "Push · pending, no connections")
@Composable private fun PushPendingPreview() = SyncMdTheme(darkTheme = false) {
    PushPreview(PushSyncViewModel.State(ready = true, enabled = true, configured = true, connectionsChecked = true))
}

@Preview(name = "Push · busy, failed, unavailable · dark")
@Composable private fun PushBusyDarkPreview() = SyncMdTheme(darkTheme = true) {
    PushPreview(PushSyncViewModel.State(ready = true, busy = true, enabled = true, configured = false, error = "failed"))
}
