package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.ui.components.SettingsCard
import com.bontecou.syncmd.ui.components.SettingsChipFlow
import com.bontecou.syncmd.ui.components.SettingsSectionWithAction
import com.bontecou.syncmd.ui.components.relativeAgo
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Badge
import com.bontecou.syncmd.ui.theme.M3BottomActionBar
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Chevron
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Divider
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3RadioIndicator
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3SettingsRow
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.viewmodels.RepositoryActionsViewModel

// ─── Widget & launcher shortcuts (board Shortcuts: manage · requested · pin · revoke) ──

/**
 * Pick a vault, review its branch and upstream, choose one Git action and run it once or pin it as a
 * launcher shortcut; manage launcher shortcuts and authorized callback links; open Pull All.
 */
@Composable
fun RepositoryActionsScreen(model: RepositoryActionsViewModel, onBack: () -> Unit, onPullAll: () -> Unit = {}) {
    val state by model.state.collectAsState()
    var actionName by rememberSaveable { mutableStateOf(RepositoryAction.PULL.name) }
    var message by rememberSaveable { mutableStateOf("Update from GitSync.md") }
    var appliedShortcutRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<RepositoryActionsViewModel.Confirmation?>(null) }
    var pinConfirmation by remember { mutableStateOf<RepositoryActionsViewModel.Confirmation?>(null) }
    var backgroundShortcut by rememberSaveable { mutableStateOf(true) }
    var revoke by remember { mutableStateOf<String?>(null) }
    var revokeGrant by remember { mutableStateOf<String?>(null) }
    var resetGrants by remember { mutableStateOf<String?>(null) }
    var resetShortcutResults by remember { mutableStateOf(false) }
    val action = RepositoryAction.valueOf(actionName)
    val writes = action in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC)
    val reviewedAction = state.plan?.let { RepositoryActionsViewModel.Confirmation(it, action, message, state.shortcutGeneration) }
    val owner = LocalLifecycleOwner.current
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(state.shortcut?.id, state.shortcutRequestId) {
        val requestId = state.shortcutRequestId
        state.shortcut?.let {
            // Recreation restores the user's draft; only a new accepted delivery replaces it.
            if (requestId != null && appliedShortcutRequestId != requestId) {
                actionName = it.action.name; message = it.message; appliedShortcutRequestId = requestId
            }
        }
    }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { confirmation = null; pinConfirmation = null; revoke = null; revokeGrant = null; resetGrants = null; resetShortcutResults = false; model.stop() }
            if (event == Lifecycle.Event.ON_RESUME && com.bontecou.syncmd.automation.RepositoryShortcutRequests.pending.value == null &&
                currentState.ready && !currentState.busy && currentState.shortcutItems.any { it.record.enabled && (!it.active || it.record.background) }) model.refreshShortcuts()
            else if (event == Lifecycle.Event.ON_RESUME && currentState.ready && !currentState.busy) model.refreshGrants()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.stop() }
    }
    BackHandler { model.stop(); onBack() }
    val c = M3.colors
    val messageValid = message.isNotBlank() && message.length <= 65536 && '\u0000' !in message
    val enabledShortcuts = state.shortcutItems.count { it.record.enabled }
    Column(Modifier.fillMaxSize().background(c.surface).testTag("repository-actions-screen")) {
        M3TopBar(stringResource(R.string.settings_m3_sc_title), subtitle = pluralStringResource(R.plurals.settings_m3_sc_subtitle, enabledShortcuts, enabledShortcuts),
            onBack = { model.stop(); onBack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            state.shortcutNotice?.let { notice ->
                M3Note(stringResource(when (notice) {
                    "requested" -> R.string.settings_m3_sc_requested
                    "revoked" -> R.string.repository_shortcut_revoked
                    "busy" -> R.string.repository_shortcut_busy
                    else -> R.string.repository_shortcut_unsupported
                }), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    tone = if (notice == "requested" || notice == "revoked") M3NoteTone.Info else M3NoteTone.Muted, icon = R.drawable.ic_m3_info)
            }
            M3SectionLabel(stringResource(R.string.settings_m3_sc_pull_all_title))
            PullAllCard(onPullAll)
            M3SectionLabel(stringResource(R.string.settings_m3_sc_new_title))
            if (!state.busy && state.targets.isEmpty() && !state.failed) M3Note(stringResource(R.string.pull_all_empty), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                tone = M3NoteTone.Muted)
            SettingsChipFlow {
                state.targets.forEach { target ->
                    RadioChip(target.name, state.plan?.target?.path == target.path, !state.busy, Modifier.testTag("repository-action-select-${target.path}")) {
                        confirmation = null; model.select(target)
                    }
                }
            }
            state.plan?.let { plan -> ReviewCard(plan, !state.busy) { model.select(plan.target) } }
            if (state.plan != null) {
                Text(stringResource(R.string.settings_m3_sc_action), Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), style = M3Type.section, color = c.primary)
                M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                    RepositoryAction.values().forEachIndexed { index, choice ->
                        ActionRadioRow(stringResource(actionShortLabel(choice)), stringResource(actionSubtitle(choice)), action == choice, !state.busy, index > 0,
                            Modifier.testTag("repository-action-${choice.name}")) { actionName = choice.name }
                    }
                }
                if (writes) {
                    M3Note(stringResource(R.string.settings_m3_sc_writes), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Ahead,
                        icon = R.drawable.ic_m3_warning)
                    M3TextField(message, { message = it }, stringResource(R.string.repository_actions_message),
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).testTag("repository-action-message"), enabled = !state.busy)
                }
            }
            if (state.busy) M3IndeterminateProgressBar(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
            if (state.stopped) M3Note(stringResource(R.string.repository_actions_stopped), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Muted)
            if (state.failed) M3Note(stringResource(R.string.repository_actions_failed), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Warn)
            state.result?.let { result -> ResultCard(result) }

            SettingsSectionWithAction(stringResource(R.string.settings_m3_sc_launcher), stringResource(R.string.settings_m3_sc_refresh), model::refreshShortcuts,
                Modifier.padding(top = 8.dp), enabled = !state.busy)
            if (state.shortcutItems.isEmpty()) Text(stringResource(R.string.settings_m3_sc_no_shortcuts), Modifier.padding(start = 20.dp, end = 20.dp),
                style = M3Type.bodyMedium, color = c.onVar)
            if (state.shortcutItems.any { it.resultsUnavailable }) M3Note(stringResource(R.string.shortcut_results_unavailable),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), tone = M3NoteTone.Warn)
            state.shortcutItems.forEach { item ->
                SettingsCard(Modifier.testTag("repository-shortcut-${item.record.id}"), top = 8.dp) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.settings_m3_sc_item_title, stringResource(actionShortLabel(item.record.action)), item.record.plan.target.name),
                                Modifier.weight(1f), style = M3Type.bodyStrong, color = c.on)
                            when {
                                !item.record.enabled -> M3Badge(stringResource(R.string.settings_m3_sc_revoked), M3Tone.Muted)
                                item.active -> M3Badge(stringResource(R.string.settings_m3_sc_pinned), M3Tone.Ok)
                                else -> M3Badge(stringResource(R.string.settings_m3_sc_waiting), M3Tone.Muted)
                            }
                        }
                        Text(stringResource(if (item.record.background) R.string.settings_m3_sc_runs_closed else R.string.settings_m3_sc_runs_open),
                            style = M3Type.bodySmall, color = c.onVar)
                        item.lastRun?.let { run ->
                            val label = when (run.state) {
                                com.bontecou.syncmd.automation.ShortcutRunState.QUEUED,
                                com.bontecou.syncmd.automation.ShortcutRunState.RUNNING -> R.string.shortcut_background_running
                                com.bontecou.syncmd.automation.ShortcutRunState.COMPLETE -> run.outcome?.let(::resultLabel) ?: R.string.shortcut_background_attention
                                com.bontecou.syncmd.automation.ShortcutRunState.INTERRUPTED -> R.string.shortcut_background_interrupted
                                com.bontecou.syncmd.automation.ShortcutRunState.ATTENTION -> run.outcome?.let(::resultLabel) ?: R.string.shortcut_background_attention
                            }
                            Text(stringResource(label), Modifier.testTag("shortcut-last-run-${item.record.id}"), style = M3Type.meta, color = runTint(run.state, run.outcome))
                        }
                    }
                    M3Divider(Modifier.padding(horizontal = 20.dp))
                    if (item.record.enabled) DangerRow(stringResource(R.string.settings_m3_sc_revoke), !state.busy) { revoke = item.record.id }
                    else M3ListRow(stringResource(R.string.repository_shortcut_forget), minHeight = 52.dp, enabled = !state.busy,
                        onClick = { model.forgetShortcut(item.record.id) }, contentPadding = PaddingValues(start = 20.dp, end = 16.dp))
                }
            }
            SettingsSectionWithAction(stringResource(R.string.settings_m3_sc_links), stringResource(R.string.settings_m3_sc_refresh), model::refreshGrants,
                Modifier.padding(top = 8.dp), enabled = !state.busy)
            if (state.callbackGrants.isEmpty() && !state.callbackGrantFailed) Text(stringResource(R.string.settings_m3_sc_no_links), Modifier.padding(start = 20.dp, end = 20.dp),
                style = M3Type.bodyMedium, color = c.onVar)
            if (state.callbackResultsFailed) M3Note(stringResource(R.string.callback_background_results_unavailable), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                tone = M3NoteTone.Warn)
            if (state.callbackGrantFailed) M3Note(stringResource(R.string.callback_grant_unreadable), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), tone = M3NoteTone.Warn,
                action = { M3Button(stringResource(R.string.callback_grant_reset), { resetGrants = state.callbackGrantRecoveryVersion }, Modifier.testTag("callback-grant-reset"),
                    style = M3ButtonStyle.Outlined, destructive = true, enabled = !state.busy && state.callbackGrantRecoveryVersion != null) })
            state.callbackGrants.forEach { grant ->
                SettingsCard(Modifier.testTag("callback-grant-${grant.scope.id}"), top = 8.dp) {
                    SelectionContainer {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.settings_m3_sc_item_title, stringResource(actionShortLabel(grant.scope.action)), grant.scope.plan.target.name),
                                    Modifier.weight(1f), style = M3Type.bodyStrong, color = c.on)
                                if (grant.scope.enabled) M3Badge(stringResource(R.string.settings_m3_sc_authorized), M3Tone.Ok)
                                else M3Badge(stringResource(R.string.callback_grant_disabled), M3Tone.Muted)
                            }
                            Text(stringResource(R.string.settings_m3_sc_link_identity, grant.scope.id.take(8),
                                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(grant.scope.created))),
                                style = M3Type.meta, color = c.onVar)
                            Text(listOfNotNull(stringResource(R.string.settings_m3_sc_branch, grant.scope.plan.branch ?: "HEAD"),
                                listOfNotNull(grant.request.success, grant.request.error).firstOrNull()?.let {
                                    stringResource(R.string.settings_m3_sc_returns_to, RepositoryCallbackProtocol.displayDestination(it)) }).joinToString(" · "),
                                style = M3Type.meta.copy(textDirection = TextDirection.ContentOrLtr), color = c.onVar)
                            if (grant.scope.background) Text(stringResource(R.string.shortcut_background_option), style = M3Type.meta, color = c.onVar)
                            state.callbackRuns[grant.scope.id]?.let { run ->
                                val label = if (run.callbackProblem == com.bontecou.syncmd.services.automation.CallbackProblem.BUSY) R.string.callback_background_busy else when (run.state) {
                                    com.bontecou.syncmd.automation.ShortcutRunState.QUEUED,
                                    com.bontecou.syncmd.automation.ShortcutRunState.RUNNING -> R.string.callback_background_running
                                    com.bontecou.syncmd.automation.ShortcutRunState.COMPLETE,
                                    com.bontecou.syncmd.automation.ShortcutRunState.ATTENTION -> run.outcome?.let(::resultLabel) ?: R.string.callback_background_attention
                                    com.bontecou.syncmd.automation.ShortcutRunState.INTERRUPTED -> R.string.shortcut_background_interrupted
                                }
                                Text(stringResource(label), Modifier.testTag("callback-last-run-${grant.scope.id}"), style = M3Type.meta, color = runTint(run.state, run.outcome))
                            }
                            Text(stringResource(R.string.settings_m3_sc_link_private), Modifier.padding(top = 4.dp), style = M3Type.bodySmall, color = c.tertiary)
                        }
                    }
                    M3Divider(Modifier.padding(horizontal = 20.dp))
                    if (grant.scope.enabled) DangerRow(stringResource(R.string.settings_m3_sc_revoke_link), !state.busy) { revokeGrant = grant.scope.id }
                    else M3ListRow(stringResource(R.string.callback_grant_forget), minHeight = 52.dp, enabled = !state.busy,
                        onClick = { model.forgetGrant(grant.scope.id) }, contentPadding = PaddingValues(start = 20.dp, end = 16.dp))
                }
            }
            if (state.shortcutItems.any { it.lastRun != null || it.resultsUnavailable } || state.callbackRuns.isNotEmpty() || state.callbackResultsFailed)
                M3Button(stringResource(R.string.settings_m3_sc_clear_results), { resetShortcutResults = true }, Modifier.padding(start = 8.dp, top = 12.dp).testTag("shortcut-results-reset"),
                    style = M3ButtonStyle.Text, icon = R.drawable.ic_m3_delete, destructive = true, enabled = !state.busy)
            Spacer(Modifier.height(32.dp))
        }
        M3BottomActionBar {
            if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                M3IndeterminateProgressBar(Modifier.weight(1f))
                M3Button(stringResource(if (state.stopping) R.string.pull_all_stopping else R.string.repository_actions_stop), model::stop, style = M3ButtonStyle.Text,
                    enabled = !state.stopping)
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                M3Button(stringResource(R.string.repository_shortcut_add), { pinConfirmation = reviewedAction?.copy() },
                    Modifier.weight(1f).testTag("repository-shortcut-add"), style = M3ButtonStyle.Tonal, size = M3ButtonSize.Large, icon = R.drawable.ic_m3_shortcut,
                    enabled = state.pinSupported && state.plan != null && (!writes || messageValid))
                M3Button(stringResource(R.string.settings_m3_sc_run_once), {
                    reviewedAction?.copy()?.let { request -> if (request.action in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC)) confirmation = request else model.run(request) }
                }, Modifier.weight(1f).testTag("repository-action-run"), size = M3ButtonSize.Large, enabled = state.plan != null && (!writes || messageValid))
            }
        }
    }
    confirmation?.let { request ->
        M3Dialog(onDismissRequest = { confirmation = null }, title = stringResource(actionLabel(request.action)), content = {
            Text(request.plan.target.name, style = M3Type.bodyStrong, color = c.on)
            ConfirmationDetails(request)
            Text(stringResource(R.string.settings_m3_sc_writes), Modifier.padding(top = 8.dp))
        }) {
            M3DialogAction(stringResource(R.string.repository_actions_cancel), { confirmation = null })
            M3DialogAction(stringResource(R.string.repository_actions_confirm), { confirmation = null; model.run(request) }, Modifier.testTag("repository-action-confirm"),
                filled = true, enabled = !state.busy)
        }
    }
    pinConfirmation?.let { request ->
        M3Dialog(onDismissRequest = { pinConfirmation = null }, title = stringResource(R.string.repository_shortcut_add), content = {
            Text(stringResource(R.string.settings_m3_sc_item_title, stringResource(actionShortLabel(request.action)), request.plan.target.name), style = M3Type.bodyStrong, color = c.on)
            Text(listOfNotNull(request.plan.branch, request.plan.remote?.let { r -> request.plan.remoteBranch?.let { "$r/$it" } }).joinToString(" · "),
                style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.onVar)
            M3ListCard(Modifier.padding(top = 12.dp), color = c.surface, radius = 12.dp) {
                M3SettingsRow(stringResource(R.string.shortcut_background_option), Modifier.testTag("shortcut-background-choice"),
                    subtitle = stringResource(R.string.settings_m3_sc_background_sub), checked = backgroundShortcut, onCheckedChange = { backgroundShortcut = it })
            }
            Text(stringResource(R.string.settings_m3_sc_pin_consent, request.plan.target.name, request.plan.branch ?: "HEAD"), Modifier.padding(top = 12.dp))
            if (request.action in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC))
                Text(stringResource(R.string.settings_m3_sc_pin_writes, request.message), Modifier.padding(top = 8.dp), color = c.tertiary)
        }) {
            M3DialogAction(stringResource(R.string.repository_actions_cancel), { pinConfirmation = null })
            M3DialogAction(stringResource(R.string.repository_shortcut_request), { pinConfirmation = null; model.pin(request, backgroundShortcut) },
                Modifier.testTag("repository-shortcut-confirm"), filled = true, enabled = !state.busy)
        }
    }
    revoke?.let { id ->
        M3Dialog(onDismissRequest = { revoke = null }, title = stringResource(R.string.settings_m3_sc_revoke_title), icon = R.drawable.ic_m3_warning, iconTint = c.error,
            text = stringResource(R.string.settings_m3_sc_revoke_body)) {
            M3DialogAction(stringResource(R.string.repository_actions_cancel), { revoke = null })
            M3DialogAction(stringResource(R.string.settings_m3_sc_revoke), { revoke = null; model.revokeShortcut(id) }, Modifier.testTag("repository-shortcut-revoke-confirm"),
                destructive = true)
        }
    }
    revokeGrant?.let { id ->
        M3Dialog(onDismissRequest = { revokeGrant = null }, title = stringResource(R.string.callback_grant_revoke), icon = R.drawable.ic_m3_warning, iconTint = c.error,
            content = { Column(Modifier.testTag("callback-grant-revoke-scope")) { Text(stringResource(R.string.callback_grant_revoke_scope)) } }) {
            M3DialogAction(stringResource(R.string.repository_actions_cancel), { revokeGrant = null })
            M3DialogAction(stringResource(R.string.settings_m3_sc_revoke_link), { revokeGrant = null; model.revokeGrant(id) }, Modifier.testTag("callback-grant-revoke-confirm"),
                destructive = true)
        }
    }
    if (resetShortcutResults) M3Dialog(onDismissRequest = { resetShortcutResults = false }, title = stringResource(R.string.settings_m3_sc_clear_results),
        icon = R.drawable.ic_m3_delete, iconTint = c.error, text = stringResource(R.string.shortcut_results_clear_scope)) {
        M3DialogAction(stringResource(R.string.repository_actions_cancel), { resetShortcutResults = false })
        M3DialogAction(stringResource(R.string.settings_m3_sc_clear), { resetShortcutResults = false; model.clearShortcutResults() },
            Modifier.testTag("shortcut-results-reset-confirm"), destructive = true)
    }
    resetGrants?.let { version ->
        M3Dialog(onDismissRequest = { resetGrants = null }, title = stringResource(R.string.callback_grant_reset), icon = R.drawable.ic_m3_warning, iconTint = c.error,
            content = { Column(Modifier.testTag("callback-grant-reset-scope")) { Text(stringResource(R.string.callback_grant_reset_scope)) } }) {
            M3DialogAction(stringResource(R.string.repository_actions_cancel), { resetGrants = null })
            M3DialogAction(stringResource(R.string.callback_grant_reset), { resetGrants = null; model.resetUnreadableGrants(version) },
                Modifier.testTag("callback-grant-reset-confirm"), destructive = true)
        }
    }
}

/** Pull All widget and Quick Settings tile previews, how to add them, and a way into Pull All itself. */
@Composable
private fun PullAllCard(onPullAll: () -> Unit) {
    val c = M3.colors
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).background(c.inv, RoundedCornerShape(20.dp)).padding(14.dp)
                .semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.settings_m3_sc_pull_all), style = M3Type.bodyStrong, color = c.onInv)
                Text(stringResource(R.string.settings_m3_sc_ff_only), style = M3Type.caption, color = c.invMeta)
                Row(Modifier.padding(top = 10.dp).background(c.onInv, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    M3Icon(R.drawable.ic_m3_arrow_downward, tint = c.inv, size = 16.dp)
                    Text(stringResource(R.string.settings_m3_sc_pull_all_repos), style = M3Type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.inv)
                }
            }
            Row(Modifier.weight(1f).background(c.pc, RoundedCornerShape(20.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                M3Icon(R.drawable.ic_m3_cloud_sync, tint = c.onPc)
                Column {
                    Text(stringResource(R.string.settings_m3_sc_pull_all), style = M3Type.bodyStrong, color = c.onPc)
                    Text("GitSync.md", style = M3Type.caption, color = c.onPc)
                }
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HowTo(R.drawable.ic_m3_widgets, R.string.settings_m3_sc_widget_lead, R.string.settings_m3_sc_widget_how)
            HowTo(R.drawable.ic_m3_settings, R.string.settings_m3_sc_tile_lead, R.string.settings_m3_sc_tile_how)
            Text(stringResource(R.string.settings_m3_sc_pull_all_note), style = M3Type.bodySmall, color = c.onVar)
        }
        M3ListRow(stringResource(R.string.settings_m3_sc_open_pull_all), Modifier.testTag("shortcuts-open-pull-all"), divider = true,
            meta = stringResource(R.string.settings_m3_sc_open_pull_all_sub), metaMono = false, leading = { M3Icon(R.drawable.ic_m3_arrow_downward) },
            trailing = { M3Chevron() }, onClick = onPullAll, contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
    }
}

@Composable
private fun HowTo(icon: Int, lead: Int, body: Int) {
    val c = M3.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        M3Icon(icon, tint = c.onVar, size = 18.dp)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(stringResource(lead)) }
            append(" "); append(stringResource(body))
        }, style = M3Type.bodySmall, color = c.on)
    }
}

/** Radio pill ("◉ VoxVault"): 40dp, 20dp corners, outlineVar border; selected fills sc. */
@Composable
private fun RadioChip(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val c = M3.colors
    val shape = RoundedCornerShape(20.dp)
    Row(modifier.heightIn(min = 40.dp).clip(shape).then(if (selected) Modifier.background(c.sc, shape) else Modifier.border(1.dp, c.outlineVar, shape))
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(start = 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        M3RadioIndicator(selected)
        Text(label, style = M3Type.chip, color = if (selected) c.onSc else c.on)
    }
}

/** Action radio row: ring, 16/24 title over a 13/18 subtitle. */
@Composable
private fun ActionRadioRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean, divider: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val c = M3.colors
    Row(modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .heightIn(min = 64.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        M3RadioIndicator(selected)
        Column(Modifier.weight(1f)) {
            Text(title, style = M3Type.body, color = c.on)
            Text(subtitle, style = M3Type.bodySmall, color = c.onVar)
        }
    }
}

@Composable
private fun ReviewCard(plan: RepositoryActionPlan, enabled: Boolean, onReview: () -> Unit) {
    val c = M3.colors
    SettingsCard(top = 12.dp) {
        SelectionContainer {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
                Text(plan.target.name, style = M3Type.bodyStrong.copy(fontWeight = FontWeight.SemiBold), color = c.on)
                Text(stringResource(R.string.settings_m3_sc_branch_reviewed, plan.branch ?: "HEAD", plan.head?.take(7).orEmpty()), style = M3Type.meta, color = c.on)
                Text(stringResource(R.string.settings_m3_sc_upstream, plan.remote.orEmpty(), plan.remoteBranch.orEmpty()), style = M3Type.meta, color = c.primary)
                listOfNotNull(plan.fetchUrl, plan.pushUrl).distinct().forEach { url ->
                    com.bontecou.syncmd.services.repositories.RepositoryDiscovery.redactRemoteUrl(url)?.let {
                        Text(it, style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.onVar)
                    }
                }
            }
        }
        M3Divider(Modifier.padding(horizontal = 20.dp))
        M3ListRow(stringResource(R.string.settings_m3_sc_review_again), leading = { M3Icon(R.drawable.ic_m3_refresh) }, minHeight = 52.dp, enabled = enabled,
            onClick = onReview, contentPadding = PaddingValues(start = 20.dp, end = 16.dp))
    }
}

@Composable
private fun ResultCard(result: RepositoryActionResult) {
    val c = M3.colors
    SettingsCard(top = 16.dp) {
        SelectionContainer {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("repository-action-result")) {
                Text(stringResource(resultLabel(result.outcome)), style = M3Type.bodyStrong.copy(fontWeight = FontWeight.SemiBold), color = outcomeTint(result.outcome))
                result.problem?.let { Text(stringResource(problemLabel(it)), style = M3Type.bodyMedium, color = c.onVar) }
                result.changes?.let { Text(stringResource(R.string.repository_actions_changes, it), style = M3Type.meta, color = c.on) }
                result.head?.let { Text(it, style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.onVar) }
            }
        }
    }
}

@Composable
private fun DangerRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = M3.colors
    M3ListRow(label, leading = { M3Icon(R.drawable.ic_m3_link_off, tint = c.error) }, titleColor = c.error, minHeight = 52.dp, enabled = enabled,
        onClick = onClick, contentPadding = PaddingValues(start = 20.dp, end = 16.dp))
}

@Composable private fun ConfirmationDetails(request: RepositoryActionsViewModel.Confirmation) {
    val plan = request.plan
    val c = M3.colors
    Text(plan.target.path, Modifier.padding(top = 8.dp), style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.onVar)
    Text(stringResource(R.string.repository_actions_branch, plan.branch ?: "HEAD", plan.head.orEmpty()), style = M3Type.meta, color = c.on)
    Text(stringResource(R.string.repository_actions_destination, plan.remote.orEmpty(), plan.remoteBranch.orEmpty()), style = M3Type.meta, color = c.primary)
    if (request.action in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC)) {
        Text(stringResource(R.string.repository_actions_message), Modifier.padding(top = 8.dp), style = M3Type.section, color = c.onVar)
        Text(request.message, color = c.on)
    }
}

private fun actionShortLabel(action: RepositoryAction) = when (action) {
    RepositoryAction.STATUS -> R.string.settings_m3_sc_status
    RepositoryAction.PULL -> R.string.settings_m3_sc_pull
    RepositoryAction.PUSH -> R.string.settings_m3_sc_push
    RepositoryAction.SYNC -> R.string.settings_m3_sc_sync
}
private fun actionSubtitle(action: RepositoryAction) = when (action) {
    RepositoryAction.STATUS -> R.string.settings_m3_sc_status_sub
    RepositoryAction.PULL -> R.string.settings_m3_sc_pull_sub
    RepositoryAction.PUSH -> R.string.settings_m3_sc_push_sub
    RepositoryAction.SYNC -> R.string.settings_m3_sc_sync_sub
}

/** Green when it landed, orange when local work was kept, red when it stopped. */
@Composable
private fun outcomeTint(outcome: RepositoryActionOutcome): Color {
    val c = M3.colors
    return when (outcome) {
        RepositoryActionOutcome.FAILED, RepositoryActionOutcome.BLOCKED -> c.error
        RepositoryActionOutcome.ATTENTION, RepositoryActionOutcome.LOCAL_COMMIT_SAVED -> c.tertiary
        RepositoryActionOutcome.STATUS -> c.on
        else -> c.ok
    }
}

@Composable
private fun runTint(state: com.bontecou.syncmd.automation.ShortcutRunState, outcome: RepositoryActionOutcome?): Color = when (state) {
    com.bontecou.syncmd.automation.ShortcutRunState.QUEUED, com.bontecou.syncmd.automation.ShortcutRunState.RUNNING -> M3.colors.primary
    com.bontecou.syncmd.automation.ShortcutRunState.INTERRUPTED -> M3.colors.tertiary
    else -> outcome?.let { outcomeTint(it) } ?: M3.colors.tertiary
}

private fun actionLabel(action: RepositoryAction) = when (action) {
    RepositoryAction.STATUS -> R.string.repository_actions_status
    RepositoryAction.PULL -> R.string.repository_actions_pull
    RepositoryAction.PUSH -> R.string.repository_actions_push
    RepositoryAction.SYNC -> R.string.repository_actions_sync
}
private fun resultLabel(outcome: RepositoryActionOutcome) = when (outcome) {
    RepositoryActionOutcome.STATUS -> R.string.repository_actions_status_ready
    RepositoryActionOutcome.PULLED -> R.string.repository_actions_pulled
    RepositoryActionOutcome.PUSHED -> R.string.repository_actions_pushed
    RepositoryActionOutcome.PULLED_AND_PUSHED -> R.string.repository_actions_synced
    RepositoryActionOutcome.UP_TO_DATE -> R.string.pull_all_current
    RepositoryActionOutcome.NO_CHANGES -> R.string.repository_actions_no_changes
    RepositoryActionOutcome.BLOCKED -> R.string.repository_actions_blocked
    RepositoryActionOutcome.LOCAL_COMMIT_SAVED -> R.string.repository_actions_local_saved
    RepositoryActionOutcome.ATTENTION -> R.string.pull_all_attention
    RepositoryActionOutcome.FAILED -> R.string.repository_actions_failed
}
private fun problemLabel(problem: RepositoryActionProblem) = when (problem) {
    RepositoryActionProblem.STALE -> R.string.repository_actions_stale
    RepositoryActionProblem.UNSAFE -> R.string.repository_actions_unsafe
    RepositoryActionProblem.UNCONFIGURED -> R.string.repository_actions_unconfigured
    RepositoryActionProblem.LOCAL_CHANGES -> R.string.repository_actions_dirty
    RepositoryActionProblem.DIVERGED -> R.string.repository_actions_diverged
    RepositoryActionProblem.NEED_PULL -> R.string.repository_actions_behind
    RepositoryActionProblem.REMOTE_MISSING -> R.string.repository_actions_remote_missing
    RepositoryActionProblem.UNSETTLED -> R.string.repository_actions_unsettled
    RepositoryActionProblem.CONNECTION -> R.string.repository_actions_failed
}
