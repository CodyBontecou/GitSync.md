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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.services.automation.RepositoryAction
import com.bontecou.syncmd.services.automation.RepositoryActionOutcome
import com.bontecou.syncmd.services.automation.RepositoryActionResult
import com.bontecou.syncmd.services.push.PushHintResult
import com.bontecou.syncmd.services.push.PushInstallation
import com.bontecou.syncmd.ui.components.SettingsCard
import com.bontecou.syncmd.ui.components.SettingsChipFlow
import com.bontecou.syncmd.ui.components.SettingsDotLine
import com.bontecou.syncmd.ui.components.SettingsHint
import com.bontecou.syncmd.ui.components.SettingsIntro
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Badge
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Chevron
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Divider
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3Hero
import com.bontecou.syncmd.ui.theme.M3HeroStyle
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3InfoChip
import com.bontecou.syncmd.ui.theme.M3LeadingAvatar
import com.bontecou.syncmd.ui.theme.M3LeadingTile
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3ProgressTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3SettingsRow
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.viewmodels.PushSyncViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ─── Push Sync (boards PushSync: on · off · permission · existing · checked · remove,
//     and GitHubApp: connect · handoff · connected · notSelected · cancelled · owner · unlink) ──

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PushSyncScreen(model: PushSyncViewModel, onBack: () -> Unit, onOpenRepository: (String) -> Unit = {}) {
    val state by model.state.collectAsState(); val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var unlink by remember { mutableStateOf<PushInstallation?>(null) }
    var requestingPermission by rememberSaveable { mutableStateOf(false) }
    var showExisting by rememberSaveable { mutableStateOf(false) }
    var existingAccount by rememberSaveable { mutableStateOf("") }
    // GitHubApp › handoff: the browser opened for the GitHub App; this screen checks again on return.
    var handoff by rememberSaveable { mutableStateOf(false) }
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
                        (!waitingForAction || completed.error == null)) { model.reload() }
                }
            }
            if (event == Lifecycle.Event.ON_STOP) { resumeReload?.cancel(); unlink = null; model.stopPull() }
        }
        owner.lifecycle.addObserver(observer); onDispose { resumeReload?.cancel(); owner.lifecycle.removeObserver(observer) }
    }
    // A link callback or an error ends the handoff.
    LaunchedEffect(state.installations, state.error) { if (state.error != null) handoff = false }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.setEnabled(true) else model.permissionDenied()
        requestingPermission = false
    }
    BackHandler { model.stopPull(); onBack() }
    PushSyncContent(
        state = state,
        handoff = handoff,
        showExisting = showExisting,
        existingAccount = existingAccount,
        onBack = { model.stopPull(); onBack() },
        onOpenRepository = onOpenRepository,
        onToggle = { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33 && !state.allowed) { requestingPermission = true; permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            else model.setEnabled(enabled)
        },
        onOpenSettings = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
        onStop = { handoff = false; model.stop() },
        onLink = { handoff = true; model.link() },
        onToggleExisting = { showExisting = !showExisting },
        onExistingAccount = { if (it.length <= 101) existingAccount = it },
        onExistingDone = { keyboard?.hide(); focus.clearFocus() },
        onLinkExisting = { handoff = true; model.linkExisting(existingAccount) },
        onRefresh = { handoff = false; model.refresh() },
        onManage = { open(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it.htmlURL))) },
        onUnlink = { unlink = it },
    )
    unlink?.let { installation ->
        M3Dialog(onDismissRequest = { unlink = null }, title = stringResource(R.string.settings_m3_push_remove_title), icon = R.drawable.ic_m3_link_off,
            iconTint = M3.colors.error, text = stringResource(R.string.settings_m3_push_remove_body, installation.login)) {
            M3DialogAction(stringResource(R.string.repository_actions_cancel), { unlink = null })
            M3DialogAction(stringResource(R.string.settings_m3_push_remove), { unlink = null; model.unlink(installation.id) }, Modifier.testTag("push-unlink-confirm"),
                destructive = true)
        }
    }
}

@Composable
private fun PushSyncContent(
    state: PushSyncViewModel.State,
    handoff: Boolean,
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
    val c = M3.colors
    val canLink = !state.busy && state.configured && state.registeredAt > 0
    val subtitle = if (state.enabled) pluralStringResource(R.plurals.settings_m3_push_connections, state.installations.size, state.installations.size)
        else stringResource(R.string.settings_m3_off)
    Column(Modifier.fillMaxSize().background(c.surface).testTag("push-sync-screen")) {
        M3TopBar(stringResource(R.string.push_title), subtitle = subtitle, onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            state.checked?.let { count ->
                Text(stringResource(R.string.settings_m3_push_checked, count, state.updated, state.attention),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp).testTag("push-sync-result"), style = M3Type.meta, color = c.onVar)
                state.results.forEach { result -> PushResultCard(result, onOpenRepository) }
            }
            SettingsIntro(stringResource(R.string.settings_m3_push_intro), Modifier.padding(top = if (state.checked != null) 12.dp else 0.dp))
            SettingsCard(top = 12.dp) {
                M3SettingsRow(stringResource(R.string.push_enable), Modifier.testTag("push-sync-toggle"), icon = R.drawable.ic_m3_notifications,
                    subtitle = stringResource(R.string.settings_m3_push_channel_sub), checked = state.enabled, onCheckedChange = onToggle,
                    enabled = !state.busy && (state.enabled || state.configured))
            }
            if (!state.configured) M3Note(stringResource(R.string.push_unavailable), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Muted)
            if (!state.allowed) M3Note(stringResource(R.string.settings_m3_push_permission), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                tone = M3NoteTone.Warn, icon = R.drawable.ic_m3_notifications, action = {
                    M3Button(stringResource(R.string.push_open_settings), onOpenSettings, style = M3ButtonStyle.Tonal, icon = R.drawable.ic_m3_settings)
                })
            if (handoff && state.enabled) HandoffCard(onStop)
            else if (state.busy) Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                M3IndeterminateProgressBar()
                M3Button(stringResource(R.string.repository_actions_stop), onStop, Modifier.padding(top = 4.dp), style = M3ButtonStyle.Text, icon = R.drawable.ic_m3_stop)
            }
            state.error?.let { error -> ErrorNote(error) }
            if (state.enabled) {
                val registered = state.registeredAt > 0
                SettingsDotLine(stringResource(if (registered) R.string.settings_m3_push_registered else R.string.push_registration_pending),
                    if (registered) c.ok else c.ahead, ink = if (registered) c.onVar else c.tertiary)
                M3Group(stringResource(R.string.settings_m3_push_connection)) {
                    M3ListRow(stringResource(if (state.error == "cancelled") R.string.settings_m3_push_connect_again else R.string.push_link), Modifier.testTag("push-link"),
                        meta = stringResource(R.string.settings_m3_push_connect_sub), metaMono = false, metaStyle = M3Type.bodySmall,
                        leading = { M3LeadingTile(R.drawable.ic_github) }, trailing = { M3Icon(R.drawable.ic_m3_open_in_new, size = 20.dp) },
                        enabled = canLink, onClick = onLink)
                    M3ListRow(stringResource(R.string.push_existing), Modifier.testTag("push-existing-toggle"), divider = true,
                        trailing = { M3Chevron() },
                        enabled = !state.busy && state.registeredAt > 0, onClick = onToggleExisting)
                    if (showExisting) Column(Modifier.fillMaxWidth().m3TopDivider(true, c.outlineVar).padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(stringResource(R.string.settings_m3_push_existing_scope), style = M3Type.bodyMedium, color = c.onVar)
                        M3TextField(existingAccount, onExistingAccount, stringResource(R.string.push_existing_account), Modifier.padding(top = 16.dp).testTag("push-existing-account"),
                            mono = true, enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onExistingDone() }))
                        M3Button(stringResource(R.string.push_existing_connect), onLinkExisting, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("push-existing-connect"),
                            enabled = canLink && runCatching { com.bontecou.syncmd.services.push.PushSyncProtocol.existingAccount(existingAccount) }.isSuccess)
                    }
                }
                M3Button(stringResource(R.string.push_refresh), onRefresh, Modifier.padding(start = 8.dp, top = 4.dp), style = M3ButtonStyle.Text,
                    icon = R.drawable.ic_m3_refresh, enabled = !state.busy)
                if (state.connectionsChecked && state.installations.isEmpty() && !state.busy) Text(stringResource(R.string.settings_m3_push_no_connections),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp), style = M3Type.bodyMedium, color = c.onVar)
                if (state.installations.isNotEmpty()) M3Group(stringResource(R.string.settings_m3_push_connected)) {
                    state.installations.forEachIndexed { index, installation ->
                        InstallationRow(installation, state.busy, index > 0, { onManage(installation) }, { onUnlink(installation) })
                    }
                }
                M3SectionLabel(stringResource(R.string.settings_m3_push_example_title))
                ExampleNotification()
                Text(stringResource(R.string.settings_m3_push_example_note), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), style = M3Type.bodySmall, color = c.onVar)
            }
            M3SectionLabel(stringResource(R.string.settings_m3_push_never))
            SettingsChipFlow {
                listOf(R.string.settings_m3_push_never_commit, R.string.settings_m3_push_never_upload, R.string.settings_m3_push_never_switch,
                    R.string.settings_m3_push_never_merge).forEach { M3InfoChip(stringResource(it)) }
            }
            SettingsHint(stringResource(R.string.settings_m3_push_never_hint))
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** GitHubApp › handoff: "Finish on GitHub", this phone ⋯ GitHub App, waiting bar, Stop action. */
@Composable
private fun HandoffCard(onStop: () -> Unit) {
    val c = M3.colors
    M3Hero(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("push-handoff"), style = M3HeroStyle.PrimaryContainer) {
        Text(stringResource(R.string.settings_m3_push_handoff_title), style = M3Type.bodyStrong.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.onPc)
        Text(stringResource(R.string.settings_m3_push_handoff_body), Modifier.padding(top = 4.dp), style = M3Type.bodyMedium, color = c.onPc)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_m3_push_this_phone), style = M3Type.metaSmall, color = c.onPc)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) { repeat(3) { Box(Modifier.size(6.dp).background(c.onPc, CircleShape)) } }
            Text(stringResource(R.string.settings_m3_push_github_app), style = M3Type.metaSmall, color = c.onPc)
        }
        M3IndeterminateProgressBar(Modifier.padding(top = 12.dp), tone = M3ProgressTone.PrimaryContainer, description = stringResource(R.string.settings_m3_push_waiting))
        M3Button(stringResource(R.string.settings_m3_push_stop), onStop, Modifier.padding(top = 8.dp), style = M3ButtonStyle.OutlinedContent, icon = R.drawable.ic_m3_stop)
    }
}

@Composable
private fun ErrorNote(error: String) {
    val (title, text, tone, icon) = when (error) {
        "cancelled" -> Quad(R.string.settings_m3_push_err_cancelled_title, R.string.settings_m3_push_err_cancelled, M3NoteTone.Ahead, R.drawable.ic_m3_close)
        "owner" -> Quad(R.string.settings_m3_push_err_owner_title, R.string.push_owner, M3NoteTone.Warn, R.drawable.ic_m3_person)
        "no_match" -> Quad(R.string.settings_m3_push_err_not_selected_title, R.string.settings_m3_push_err_not_selected, M3NoteTone.Warn, R.drawable.ic_m3_lock)
        "permission" -> Quad(null, R.string.push_permission, M3NoteTone.Warn, R.drawable.ic_m3_notifications)
        "stopped" -> Quad(null, R.string.push_stopped, M3NoteTone.Ahead, R.drawable.ic_m3_info)
        "open" -> Quad(null, R.string.push_open_failed, M3NoteTone.Warn, R.drawable.ic_m3_warning)
        else -> Quad(null, R.string.push_failed, M3NoteTone.Warn, R.drawable.ic_m3_warning)
    }
    M3Note(stringResource(text), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("push-sync-error"), tone = tone, icon = icon,
        title = title?.let { stringResource(it) })
}

private data class Quad(val a: Int?, val b: Int, val c: M3NoteTone, val d: Int)

@Composable
private fun InstallationRow(installation: PushInstallation, busy: Boolean, divider: Boolean, onManage: () -> Unit, onUnlink: () -> Unit) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            M3LeadingAvatar(installation.login.take(1).uppercase(), if (installation.suspended) M3Tone.Warn else M3Tone.Ok)
            Column(Modifier.weight(1f)) {
                Text("@${installation.login}", style = M3Type.bodyStrong.copy(textDirection = TextDirection.Ltr), color = c.on)
                Text(stringResource(when {
                    installation.suspended -> R.string.push_suspended
                    installation.allRepositories -> R.string.settings_m3_push_all_repos
                    else -> R.string.push_selected_repositories
                }), style = M3Type.bodySmall, color = if (installation.suspended) c.error else c.onVar)
            }
            M3Badge(stringResource(if (installation.suspended) R.string.settings_m3_push_suspended else R.string.settings_m3_push_active),
                if (installation.suspended) M3Tone.Warn else M3Tone.Ok)
        }
        Row(Modifier.padding(start = 56.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3Button(stringResource(R.string.push_manage_github), onManage, style = M3ButtonStyle.Tonal, size = M3ButtonSize.Small, icon = R.drawable.ic_m3_open_in_new)
            M3Button(stringResource(R.string.settings_m3_push_remove), onUnlink, style = M3ButtonStyle.Text, size = M3ButtonSize.Small, destructive = true, enabled = !busy)
        }
    }
}

/** "A nudge looks like this": notification mock with the channel, repo and message. */
@Composable
private fun ExampleNotification() {
    val c = M3.colors
    val description = stringResource(R.string.settings_m3_push_example_cd)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).background(c.cHigh, RoundedCornerShape(20.dp)).padding(16.dp)
        .semantics(mergeDescendants = true) { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).background(c.pc, CircleShape), contentAlignment = Alignment.Center) { M3Icon(R.drawable.ic_m3_sync, tint = c.onPc, size = 18.dp) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_m3_push_example_meta), style = M3Type.caption, color = c.onVar)
            Text(stringResource(R.string.settings_m3_push_example_repo), style = M3Type.bodyStrong, color = c.on)
            Text(stringResource(R.string.push_notification, "main"), style = M3Type.bodyMedium, color = c.onVar)
        }
    }
}

/** PushSync › checked: one card per vault the nudge checked. */
@Composable
private fun PushResultCard(result: PushHintResult, onOpenRepository: (String) -> Unit) {
    val c = M3.colors
    val updated = result.result.pullUpdated
    val current = result.result.outcome == RepositoryActionOutcome.UP_TO_DATE
    SettingsCard(top = 12.dp) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
            Text(result.target.name, style = M3Type.bodyStrong.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.on)
            Text(stringResource(if (updated) R.string.settings_m3_push_result_updated else if (current) R.string.push_result_current else R.string.push_result_review),
                style = M3Type.bodyMedium, color = if (updated || current) c.ok else c.tertiary)
            result.result.branch?.let { branch ->
                Text(listOfNotNull(branch, result.result.changes?.takeIf { !updated && !current && it > 0 }?.let {
                    pluralStringResource(R.plurals.settings_m3_push_local_changes, it, it) }).joinToString(" · "),
                    style = M3Type.meta, color = c.onVar)
            }
        }
        M3Divider(Modifier.padding(horizontal = 20.dp))
        M3ListRow(stringResource(R.string.settings_m3_push_open_vault), Modifier.testTag("push-open-repository"), trailing = { M3Chevron() },
            minHeight = 52.dp, onClick = { onOpenRepository(result.target.path) }, contentPadding = PaddingValues(start = 20.dp, end = 16.dp))
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewInstallations = listOf(
    PushInstallation(1, "CodyBontecou", false, true, "https://github.com/settings/installations/1", false, 0),
    PushInstallation(2, "isolated-tech", true, false, "https://github.com/settings/installations/2", true, 0),
)

@Composable
private fun PushPreview(state: PushSyncViewModel.State, handoff: Boolean = false, existing: Boolean = false) =
    PushSyncContent(state, handoff, existing, "isolated-tech", {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(name = "PushSync · on", heightDp = 1500)
@Composable private fun PushOnPreview() = SyncMdTheme(darkTheme = false) {
    PushPreview(PushSyncViewModel.State(ready = true, configured = true, enabled = true, registeredAt = 1, installations = previewInstallations, connectionsChecked = true))
}

@Preview(name = "PushSync · permission · dark")
@Composable private fun PushPermissionDarkPreview() = SyncMdTheme(darkTheme = true) {
    PushPreview(PushSyncViewModel.State(ready = true, configured = true, allowed = false))
}

@Preview(name = "GitHubApp · handoff", heightDp = 1200)
@Composable private fun PushHandoffPreview() = SyncMdTheme(darkTheme = false) {
    PushPreview(PushSyncViewModel.State(ready = true, configured = true, enabled = true, registeredAt = 1, busy = true), handoff = true)
}

@Preview(name = "PushSync · checked", heightDp = 1500)
@Composable private fun PushCheckedPreview() = SyncMdTheme(darkTheme = false) {
    PushPreview(PushSyncViewModel.State(ready = true, configured = true, enabled = true, registeredAt = 1, checked = 2, updated = 1, attention = 1,
        results = listOf(PushHintResult(PullAllTarget("/v", "VoxVault"), RepositoryActionResult(RepositoryAction.PULL, RepositoryActionOutcome.PULLED, "main", null, null, pullUpdated = true)),
            PushHintResult(PullAllTarget("/h", "health-md"), RepositoryActionResult(RepositoryAction.PULL, RepositoryActionOutcome.BLOCKED, "main", null, 2)))))
}
