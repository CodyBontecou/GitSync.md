package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.ui.viewmodels.RepositoryActionsViewModel
import com.bontecou.syncmd.ui.components.VaultOpsBanner
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsButtonStyle
import com.bontecou.syncmd.ui.components.VaultOpsDangerIcon
import com.bontecou.syncmd.ui.components.VaultOpsHeading
import com.bontecou.syncmd.ui.components.VaultOpsNote
import com.bontecou.syncmd.ui.components.VaultOpsOutlineButton
import com.bontecou.syncmd.ui.components.VaultOpsProgress
import com.bontecou.syncmd.ui.components.VaultOpsRow
import com.bontecou.syncmd.ui.components.VaultOpsSelectMark
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.components.VaultOpsToggleRow
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.components.vaultOpsSlice
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors

/**
 * Repository actions: pick a vault, review its branch and upstream, choose one
 * Git action and run it; manage home-screen shortcuts and callback links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositoryActionsScreen(model: RepositoryActionsViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    var actionName by rememberSaveable { mutableStateOf(RepositoryAction.STATUS.name) }
    var message by rememberSaveable { mutableStateOf("Update from GitSync.md") }
    var appliedShortcutRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<RepositoryActionsViewModel.Confirmation?>(null) }
    var pinConfirmation by remember { mutableStateOf<RepositoryActionsViewModel.Confirmation?>(null) }
    var backgroundShortcut by rememberSaveable { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<String?>(null) }
    var revokeGrant by remember { mutableStateOf<String?>(null) }
    var resetGrants by remember { mutableStateOf<String?>(null) }
    var resetShortcutResults by remember { mutableStateOf(false) }
    val action = RepositoryAction.valueOf(actionName)
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
    val c = Tide.colors
    Scaffold(containerColor = c.paper, contentColor = c.ink, topBar = { WrappingTopBar(title = stringResource(R.string.repository_actions_title),
        backLabel = stringResource(R.string.pull_all_back), onBack = { model.stop(); onBack() }) }, bottomBar = {
        Column(Modifier.fillMaxWidth().background(c.paper)) {
            TideHairline()
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Tide.gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy) {
                    VaultOpsProgress()
                    VaultOpsOutlineButton(stringResource(if (state.stopping) R.string.pull_all_stopping else R.string.repository_actions_stop), model::stop,
                        enabled = !state.stopping)
                } else {
                    if (state.pinSupported && state.plan != null) VaultOpsOutlineButton(stringResource(R.string.repository_shortcut_add), { pinConfirmation = reviewedAction?.copy() },
                        Modifier.testTag("repository-shortcut-add"), enabled = message.isNotBlank() || action in setOf(RepositoryAction.STATUS, RepositoryAction.PULL))
                    VaultOpsButton(stringResource(R.string.repository_actions_run), {
                        reviewedAction?.copy()?.let { request -> if (request.action in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC)) confirmation = request else model.run(request) }
                    }, Modifier.testTag("repository-action-run"), enabled = state.plan != null && (action !in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC) ||
                        (message.isNotBlank() && message.length <= 65536 && '\u0000' !in message)))
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("repository-actions-screen"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 24.dp)) {
            item(key = "scope") { VaultOpsNote(stringResource(R.string.repository_actions_scope), Modifier.padding(horizontal = 4.dp)) }
            item(key = "reload") { VaultOpsOutlineButton(stringResource(R.string.repository_actions_reload), model::reload, Modifier.padding(top = 12.dp), enabled = !state.busy) }
            if (!state.busy && state.targets.isEmpty() && !state.failed) item(key = "empty") {
                TideCard(Modifier.padding(top = 12.dp)) { VaultOpsNote(stringResource(R.string.pull_all_empty)) }
            }
            if (state.targets.isNotEmpty()) item(key = "targets-gap") { Spacer(Modifier.height(16.dp)) }
            itemsIndexed(state.targets, key = { _, it -> "target-${it.path}" }) { index, target ->
                val chosen = state.plan?.target?.path == target.path
                ChoiceRow(target.name, chosen, !state.busy, Modifier.vaultOpsSlice(index, state.targets.size).testTag("repository-action-select-${target.path}")) {
                    confirmation = null; model.select(target)
                }
            }
            state.plan?.let { plan ->
                item(key = "review-${plan.target.path}") {
                    TideCard(Modifier.padding(top = 16.dp), padding = 0.dp) {
                        SelectionContainer {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(plan.target.name, style = TideType.title, color = c.ink)
                                Text(plan.target.path, style = TideType.mono(11.5.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                                Text(stringResource(R.string.repository_actions_branch, plan.branch ?: "HEAD", plan.head.orEmpty()), style = TideType.mono(12.5.sp), color = c.ink)
                                Text(stringResource(R.string.repository_actions_destination, plan.remote.orEmpty(), plan.remoteBranch.orEmpty()), style = TideType.mono(12.5.sp), color = c.behind)
                                listOfNotNull(plan.fetchUrl, plan.pushUrl).distinct().forEach { url ->
                                    com.bontecou.syncmd.services.repositories.RepositoryDiscovery.redactRemoteUrl(url)?.let {
                                        Text(it, style = TideType.mono(11.5.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                                    }
                                }
                            }
                        }
                        TideHairline(Modifier.padding(horizontal = 16.dp))
                        VaultOpsRow(stringResource(R.string.repository_actions_review), { model.select(plan.target) }, enabled = !state.busy)
                    }
                }
                item(key = "actions-gap") { Spacer(Modifier.height(16.dp)) }
                val choices = RepositoryAction.values().toList()
                itemsIndexed(choices, key = { _, it -> "action-${it.name}" }) { index, choice ->
                    ChoiceRow(stringResource(actionLabel(choice)), action == choice, !state.busy,
                        Modifier.vaultOpsSlice(index, choices.size).testTag("repository-action-${choice.name}")) { actionName = choice.name }
                }
                if (action in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC)) item(key = "message") {
                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VaultOpsBanner(stringResource(R.string.repository_actions_write_scope), tone = TideBannerTone.AHEAD)
                        OutlinedTextField(value = message, onValueChange = { message = it }, enabled = !state.busy,
                            label = { Text(stringResource(R.string.repository_actions_message)) }, shape = Tide.fieldShape, colors = tideFieldColors(),
                            textStyle = TideType.sans(16.sp), modifier = Modifier.fillMaxWidth().testTag("repository-action-message"))
                    }
                }
            }
            if (state.stopped) item(key = "stopped") { VaultOpsBanner(stringResource(R.string.repository_actions_stopped), modifier = Modifier.padding(top = 16.dp)) }
            if (state.failed) item(key = "failed") { VaultOpsBanner(stringResource(R.string.repository_actions_failed), modifier = Modifier.padding(top = 16.dp), danger = true) }
            state.result?.let { result -> item(key = "result") {
                val tint = outcomeTint(result.outcome)
                TideCard(Modifier.padding(top = 16.dp)) {
                    SelectionContainer {
                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("repository-action-result"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(resultLabel(result.outcome)), style = TideType.title, color = tint)
                            result.problem?.let { Text(stringResource(problemLabel(it)), style = TideType.copy, color = c.inkMid) }
                            result.changes?.let { Text(stringResource(R.string.repository_actions_changes, it), style = TideType.mono(12.5.sp), color = c.ink) }
                            result.head?.let { Text(it, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint) }
                        }
                    }
                }
            } }
            state.shortcutNotice?.let { notice -> item(key = "shortcut-notice") {
                VaultOpsBanner(stringResource(when (notice) {
                    "requested" -> R.string.repository_shortcut_requested
                    "revoked" -> R.string.repository_shortcut_revoked
                    "busy" -> R.string.repository_shortcut_busy
                    else -> R.string.repository_shortcut_unsupported
                }), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, Modifier.padding(top = 16.dp),
                    tone = if (notice == "requested" || notice == "revoked") TideBannerTone.SYNCED else TideBannerTone.NEUTRAL)
            } }
            if (state.shortcutItems.isNotEmpty()) item(key = "shortcut-management") {
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VaultOpsHeading(stringResource(R.string.repository_shortcut_manage))
                    TideCard(padding = 0.dp) { VaultOpsRow(stringResource(R.string.repository_shortcut_refresh), model::refreshShortcuts, enabled = !state.busy) }
                    if (state.shortcutItems.any { it.resultsUnavailable }) {
                        VaultOpsBanner(stringResource(R.string.shortcut_results_unavailable), danger = true)
                        VaultOpsOutlineButton(stringResource(R.string.shortcut_results_clear), { resetShortcutResults = true }, Modifier.testTag("shortcut-results-reset"), danger = true)
                    }
                }
            }
            items(state.shortcutItems, key = { "shortcut-${it.record.id}" }) { item ->
                TideCard(Modifier.padding(top = 10.dp).testTag("repository-shortcut-${item.record.id}"), padding = 0.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.record.plan.target.name, style = TideType.title, color = c.ink)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(actionLabel(item.record.action)), style = TideType.body, color = c.ink)
                            if (item.active) TidePill(stringResource(R.string.repository_shortcut_active), c.synced, c.synced.copy(alpha = 0.12f))
                            else TidePill(stringResource(R.string.repository_shortcut_inactive), c.inkMid, c.well)
                        }
                        if (item.record.background) Text(stringResource(R.string.shortcut_background_option), style = TideType.meta, color = c.inkFaint)
                        item.lastRun?.let { run ->
                            val label = when (run.state) {
                                com.bontecou.syncmd.automation.ShortcutRunState.QUEUED,
                                com.bontecou.syncmd.automation.ShortcutRunState.RUNNING -> R.string.shortcut_background_running
                                com.bontecou.syncmd.automation.ShortcutRunState.COMPLETE -> run.outcome?.let(::resultLabel) ?: R.string.shortcut_background_attention
                                com.bontecou.syncmd.automation.ShortcutRunState.INTERRUPTED -> R.string.shortcut_background_interrupted
                                com.bontecou.syncmd.automation.ShortcutRunState.ATTENTION -> run.outcome?.let(::resultLabel) ?: R.string.shortcut_background_attention
                            }
                            Text(stringResource(label), style = TideType.mono(12.sp, FontWeight.Medium), color = runTint(run.state, run.outcome),
                                modifier = Modifier.testTag("shortcut-last-run-${item.record.id}"))
                        }
                    }
                    TideHairline(Modifier.padding(horizontal = 16.dp))
                    if (item.record.enabled) VaultOpsRow(stringResource(R.string.repository_shortcut_revoke), { revoke = item.record.id }, enabled = !state.busy, destructive = true)
                    else VaultOpsRow(stringResource(R.string.repository_shortcut_forget), { model.forgetShortcut(item.record.id) }, enabled = !state.busy)
                }
            }
            item(key = "callback-management") {
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VaultOpsHeading(stringResource(R.string.callback_grant_manage))
                    TideCard(padding = 0.dp) { VaultOpsRow(stringResource(R.string.callback_grant_refresh), model::refreshGrants, enabled = !state.busy) }
                    if (state.callbackResultsFailed) {
                        VaultOpsBanner(stringResource(R.string.callback_background_results_unavailable), danger = true)
                        VaultOpsOutlineButton(stringResource(R.string.shortcut_results_clear), { resetShortcutResults = true }, Modifier.testTag("callback-results-reset"), danger = true)
                    }
                    if (state.callbackGrantFailed) {
                        VaultOpsBanner(stringResource(R.string.callback_grant_unreadable), danger = true)
                        VaultOpsOutlineButton(stringResource(R.string.callback_grant_reset), { resetGrants = state.callbackGrantRecoveryVersion },
                            Modifier.testTag("callback-grant-reset"), enabled = !state.busy && state.callbackGrantRecoveryVersion != null, danger = true)
                    }
                }
            }
            items(state.callbackGrants, key = { "callback-grant-${it.scope.id}" }) { grant ->
                TideCard(Modifier.padding(top = 10.dp).testTag("callback-grant-${grant.scope.id}"), padding = 0.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(grant.scope.plan.target.name, style = TideType.title, color = c.ink)
                        Text(grant.scope.plan.target.path, style = TideType.mono(11.5.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                        Text(stringResource(R.string.callback_result_branch, grant.scope.plan.branch ?: "HEAD"), style = TideType.mono(12.sp), color = c.inkMid)
                        Text(stringResource(R.string.callback_grant_identity, grant.scope.id.take(8),
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(grant.scope.created))),
                            style = TideType.mono(12.sp), color = c.inkMid)
                        Text(stringResource(actionLabel(grant.scope.action)), style = TideType.body, color = c.ink)
                        listOfNotNull(grant.request.success, grant.request.error).distinct().forEach {
                            Text(RepositoryCallbackProtocol.displayDestination(it), style = TideType.mono(11.5.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (grant.scope.enabled) TidePill(stringResource(R.string.callback_grant_enabled), c.synced, c.synced.copy(alpha = 0.12f))
                            else TidePill(stringResource(R.string.callback_grant_disabled), c.inkMid, c.well)
                        }
                        if (grant.scope.background) Text(stringResource(R.string.shortcut_background_option), style = TideType.meta, color = c.inkFaint)
                        state.callbackRuns[grant.scope.id]?.let { run ->
                            val label = if (run.callbackProblem == com.bontecou.syncmd.services.automation.CallbackProblem.BUSY) R.string.callback_background_busy else when (run.state) {
                                com.bontecou.syncmd.automation.ShortcutRunState.QUEUED,
                                com.bontecou.syncmd.automation.ShortcutRunState.RUNNING -> R.string.callback_background_running
                                com.bontecou.syncmd.automation.ShortcutRunState.COMPLETE,
                                com.bontecou.syncmd.automation.ShortcutRunState.ATTENTION -> run.outcome?.let(::resultLabel) ?: R.string.callback_background_attention
                                com.bontecou.syncmd.automation.ShortcutRunState.INTERRUPTED -> R.string.shortcut_background_interrupted
                            }
                            Text(stringResource(label), Modifier.testTag("callback-last-run-${grant.scope.id}"), style = TideType.mono(12.sp, FontWeight.Medium),
                                color = runTint(run.state, run.outcome))
                        }
                    }
                    TideHairline(Modifier.padding(horizontal = 16.dp))
                    if (grant.scope.enabled) VaultOpsRow(stringResource(R.string.callback_grant_revoke), { revokeGrant = grant.scope.id }, enabled = !state.busy, destructive = true)
                    else VaultOpsRow(stringResource(R.string.callback_grant_forget), { model.forgetGrant(grant.scope.id) }, enabled = !state.busy)
                }
            }
        }
    }
    confirmation?.let { request ->
        VaultOpsSheet(onDismissRequest = { confirmation = null }, title = stringResource(actionLabel(request.action)),
            confirmButton = { VaultOpsButton(stringResource(R.string.repository_actions_confirm), { confirmation = null; model.run(request) },
                Modifier.testTag("repository-action-confirm"), enabled = !state.busy) },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.repository_actions_cancel), { confirmation = null }, Modifier.fillMaxWidth()) }) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(request.plan.target.name, style = TideType.title, color = c.ink)
                VaultOpsBanner(stringResource(R.string.repository_actions_write_scope), tone = TideBannerTone.AHEAD)
                ConfirmationDetails(request)
            }
        }
    }
    pinConfirmation?.let { request ->
        VaultOpsSheet(onDismissRequest = { pinConfirmation = null }, title = stringResource(R.string.repository_shortcut_add),
            confirmButton = { VaultOpsButton(stringResource(R.string.repository_shortcut_request), { pinConfirmation = null; model.pin(request, backgroundShortcut) },
                Modifier.testTag("repository-shortcut-confirm"), enabled = !state.busy) },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.repository_actions_cancel), { pinConfirmation = null }, Modifier.fillMaxWidth()) }) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(actionLabel(request.action)), style = TideType.title, color = c.ink)
                Text(request.plan.target.name, style = TideType.body, color = c.ink)
                TideCard(padding = 0.dp) {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        VaultOpsToggleRow(backgroundShortcut, { backgroundShortcut = it }, stringResource(R.string.shortcut_background_option),
                            Modifier.testTag("shortcut-background-choice"))
                    }
                }
                Text(stringResource(if (backgroundShortcut) R.string.shortcut_background_consent else R.string.repository_shortcut_consent))
                if (request.action in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC)) VaultOpsBanner(stringResource(R.string.repository_actions_write_scope), tone = TideBannerTone.AHEAD)
                ConfirmationDetails(request)
            }
        }
    }
    revoke?.let { id ->
        VaultOpsSheet(onDismissRequest = { revoke = null }, title = stringResource(R.string.repository_shortcut_revoke), icon = { VaultOpsDangerIcon() },
            confirmButton = { VaultOpsButton(stringResource(R.string.repository_shortcut_revoke), { revoke = null; model.revokeShortcut(id) },
                Modifier.testTag("repository-shortcut-revoke-confirm"), style = VaultOpsButtonStyle.DANGER) },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.repository_actions_cancel), { revoke = null }, Modifier.fillMaxWidth()) }) {
            Text(stringResource(R.string.repository_shortcut_revoke_scope))
        }
    }
    revokeGrant?.let { id ->
        VaultOpsSheet(onDismissRequest = { revokeGrant = null }, title = stringResource(R.string.callback_grant_revoke), icon = { VaultOpsDangerIcon() },
            confirmButton = { VaultOpsButton(stringResource(R.string.callback_grant_revoke), { revokeGrant = null; model.revokeGrant(id) },
                Modifier.testTag("callback-grant-revoke-confirm"), style = VaultOpsButtonStyle.DANGER) },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.repository_actions_cancel), { revokeGrant = null }, Modifier.fillMaxWidth()) }) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).testTag("callback-grant-revoke-scope")) {
                Text(stringResource(R.string.callback_grant_revoke_scope))
            }
        }
    }
    if (resetShortcutResults) VaultOpsSheet(onDismissRequest = { resetShortcutResults = false }, title = stringResource(R.string.shortcut_results_clear),
        icon = { VaultOpsDangerIcon() },
        confirmButton = { VaultOpsButton(stringResource(R.string.shortcut_results_clear), { resetShortcutResults = false; model.clearShortcutResults() },
            Modifier.testTag("shortcut-results-reset-confirm"), style = VaultOpsButtonStyle.DANGER) },
        dismissButton = { VaultOpsTextButton(stringResource(R.string.repository_actions_cancel), { resetShortcutResults = false }, Modifier.fillMaxWidth()) }) {
        Text(stringResource(R.string.shortcut_results_clear_scope))
    }
    resetGrants?.let { version ->
        VaultOpsSheet(onDismissRequest = { resetGrants = null }, title = stringResource(R.string.callback_grant_reset), icon = { VaultOpsDangerIcon() },
            confirmButton = { VaultOpsButton(stringResource(R.string.callback_grant_reset), { resetGrants = null; model.resetUnreadableGrants(version) },
                Modifier.testTag("callback-grant-reset-confirm"), style = VaultOpsButtonStyle.DANGER) },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.repository_actions_cancel), { resetGrants = null }, Modifier.fillMaxWidth()) }) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).testTag("callback-grant-reset-scope")) {
                Text(stringResource(R.string.callback_grant_reset_scope))
            }
        }
    }
}

/** Single-choice row (target or action): radio mark + label; label is the row's only text. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    Row(modifier.heightIn(min = 52.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VaultOpsSelectMark(selected, radio = true, enabled = enabled)
        Text(label, style = TideType.body, color = Tide.colors.ink.copy(alpha = if (enabled) 1f else 0.45f), modifier = Modifier.weight(1f))
    }
}

@Composable private fun ConfirmationDetails(request: RepositoryActionsViewModel.Confirmation) {
    val plan = request.plan
    val c = Tide.colors
    Text(plan.target.path, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
    Text(stringResource(R.string.repository_actions_branch, plan.branch ?: "HEAD", plan.head.orEmpty()), style = TideType.mono(12.5.sp), color = c.ink)
    Text(stringResource(R.string.repository_actions_destination, plan.remote.orEmpty(), plan.remoteBranch.orEmpty()), style = TideType.mono(12.5.sp), color = c.behind)
    if (request.action in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC)) {
        Text(stringResource(R.string.repository_actions_message), style = TideType.sans(13.sp, FontWeight.SemiBold), color = c.inkFaint)
        Text(request.message, style = TideType.copy, color = c.ink)
    }
}

/** Green when it landed, orange when local work was kept, red when it stopped. */
@Composable
private fun outcomeTint(outcome: RepositoryActionOutcome): Color {
    val c = Tide.colors
    return when (outcome) {
        RepositoryActionOutcome.FAILED, RepositoryActionOutcome.BLOCKED -> c.danger
        RepositoryActionOutcome.ATTENTION, RepositoryActionOutcome.LOCAL_COMMIT_SAVED -> c.ahead
        RepositoryActionOutcome.STATUS -> c.ink
        else -> c.synced
    }
}

@Composable
private fun runTint(state: com.bontecou.syncmd.automation.ShortcutRunState, outcome: RepositoryActionOutcome?): Color = when (state) {
    com.bontecou.syncmd.automation.ShortcutRunState.QUEUED, com.bontecou.syncmd.automation.ShortcutRunState.RUNNING -> Tide.colors.behind
    com.bontecou.syncmd.automation.ShortcutRunState.INTERRUPTED -> Tide.colors.ahead
    else -> outcome?.let { outcomeTint(it) } ?: Tide.colors.ahead
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
