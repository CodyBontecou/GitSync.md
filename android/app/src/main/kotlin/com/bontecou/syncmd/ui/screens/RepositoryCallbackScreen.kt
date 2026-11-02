package com.bontecou.syncmd.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
import com.bontecou.syncmd.ui.components.VaultOpsBanner
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsHeading
import com.bontecou.syncmd.ui.components.VaultOpsNote
import com.bontecou.syncmd.ui.components.VaultOpsOutlineButton
import com.bontecou.syncmd.ui.components.VaultOpsProgress
import com.bontecou.syncmd.ui.components.VaultOpsSelectMark
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.components.VaultOpsToggleRow
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.components.vaultOpsSlice
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.repositories.RepositoryDiscovery
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.RepositoryCallbackViewModel

/**
 * An app asked GitSync.md to run one Git action: show who, what and where,
 * run it only after review, then report back (Tide review → run → result).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositoryCallbackScreen(model: RepositoryCallbackViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = remember(context) { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull() }
    var grantConfirmation by remember { mutableStateOf(false) }
    var grantReturn by remember { mutableStateOf(true) }
    var grantBackground by remember { mutableStateOf(false) }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.foreground(true)
            if (event == Lifecycle.Event.ON_PAUSE) model.foreground(false)
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) { grantConfirmation = false; model.cancel(background = true) }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); if (activity?.isChangingConfigurations != true) model.cancel(background = true) }
    }
    fun close() { model.dismiss(); onBack() }
    BackHandler { close() }
    val c = Tide.colors
    Scaffold(containerColor = c.paper, contentColor = c.ink, topBar = {
        WrappingTopBar(title = stringResource(R.string.callback_title), backLabel = stringResource(R.string.pull_all_back),
            onBack = ::close, titleModifier = Modifier.testTag("callback-title"), backModifier = Modifier.testTag("callback-back"))
    }, bottomBar = {
        Column(Modifier.fillMaxWidth().background(c.paper)) {
            TideHairline()
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Tide.gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy) VaultOpsProgress()
                when (state.phase) {
                    RepositoryCallbackViewModel.Phase.REVIEW -> {
                        VaultOpsButton(stringResource(R.string.callback_run), model::runReviewed, Modifier.testTag("callback-run"),
                            enabled = state.review != null && !state.issuing)
                        VaultOpsTextButton(stringResource(R.string.callback_cancel), { model.cancel() }, Modifier.fillMaxWidth().testTag("callback-cancel"))
                    }
                    RepositoryCallbackViewModel.Phase.PREPARING, RepositoryCallbackViewModel.Phase.RUNNING ->
                        VaultOpsOutlineButton(stringResource(if (state.phase == RepositoryCallbackViewModel.Phase.RUNNING) R.string.repository_actions_stop else R.string.callback_cancel),
                            { model.cancel(background = state.phase == RepositoryCallbackViewModel.Phase.RUNNING) }, Modifier.testTag("callback-cancel"))
                    else -> {
                        if (state.destination != null && !state.returned) VaultOpsButton(stringResource(R.string.callback_return), model::returnToCaller,
                            Modifier.testTag("callback-return"))
                        VaultOpsOutlineButton(stringResource(R.string.callback_close), ::close, Modifier.testTag("callback-close"))
                    }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("callback-screen"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { VaultOpsNote(stringResource(if (state.authorizationRequested) R.string.callback_authorized_scope else R.string.callback_scope), Modifier.padding(horizontal = 4.dp)) }
            if (state.rejectedIncoming) item { VaultOpsBanner(stringResource(R.string.callback_busy), danger = true) }
            if (state.phase == RepositoryCallbackViewModel.Phase.EMPTY) item { TideCard { VaultOpsNote(stringResource(R.string.callback_expired)) } }
            if (state.busy) item {
                VaultOpsProgress(stringResource(if (state.phase == RepositoryCallbackViewModel.Phase.RUNNING) R.string.callback_running else R.string.callback_preparing),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.request?.let { request ->
                item {
                    TideCard(padding = 16.dp) {
                        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.callback_action), style = TideType.sans(13.sp, FontWeight.SemiBold), color = c.inkFaint)
                            Text(stringResource(callbackActionLabel(request.action)), style = TideType.title, color = c.ink)
                            request.repository?.let { Text(it, style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr), color = c.inkMid) }
                            if (request.action in setOf(RepositoryAction.PUSH, RepositoryAction.SYNC)) {
                                Spacer(Modifier.height(4.dp))
                                VaultOpsBanner(stringResource(R.string.repository_actions_write_scope), tone = TideBannerTone.AHEAD)
                                Text(stringResource(R.string.repository_actions_message), style = TideType.sans(13.sp, FontWeight.SemiBold), color = c.inkFaint)
                                Text(request.message, Modifier.testTag("callback-message"), style = TideType.copy, color = c.ink)
                            }
                        } }
                    }
                }
                if (state.candidates.size > 1 && state.phase == RepositoryCallbackViewModel.Phase.REVIEW) item { VaultOpsHeading(stringResource(R.string.callback_choose)) }
                if (state.phase == RepositoryCallbackViewModel.Phase.REVIEW) itemsIndexed(state.candidates, key = { _, it -> it.path }) { index, target ->
                    val chosen = state.review?.plan?.target?.path == target.path
                    Row(Modifier.vaultOpsSlice(index, state.candidates.size).heightIn(min = 56.dp)
                        .selectable(selected = chosen, role = Role.RadioButton, onClick = { model.select(target) })
                        .testTag("callback-select-${target.path}").padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VaultOpsSelectMark(chosen, radio = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(target.name, style = TideType.body, color = c.ink)
                            Text(target.path, style = TideType.mono(11.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                        }
                    }
                }
                state.review?.let { review -> item {
                    TideCard(padding = 16.dp) {
                        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(review.plan.target.name, style = TideType.title, color = c.ink)
                            Text(review.plan.target.path, style = TideType.mono(11.5.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                            Text(stringResource(R.string.repository_actions_branch, review.plan.branch ?: "HEAD", review.plan.head.orEmpty()), style = TideType.mono(12.5.sp), color = c.ink)
                            Text(stringResource(R.string.repository_actions_destination, review.plan.remote.orEmpty(), review.plan.remoteBranch.orEmpty()), style = TideType.mono(12.5.sp), color = c.behind)
                            listOfNotNull(review.plan.fetchUrl, review.plan.pushUrl).distinct().forEach { url ->
                                RepositoryDiscovery.redactRemoteUrl(url)?.let { Text(it, style = TideType.mono(11.5.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint) }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.callback_connection), style = TideType.sans(14.sp), color = c.inkMid)
                        } }
                    }
                } }
                if (state.phase == RepositoryCallbackViewModel.Phase.REVIEW && state.review != null) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (request.success != null || request.error != null) {
                            TideCard(padding = 0.dp) {
                                Box(Modifier.padding(horizontal = 16.dp)) {
                                    VaultOpsToggleRow(state.automaticReturn, model::automaticReturn, stringResource(R.string.callback_auto_return),
                                        Modifier.testTag("callback-auto-return"), enabled = !state.issuing)
                                }
                            }
                        }
                        if (state.canIssue) VaultOpsOutlineButton(stringResource(R.string.callback_create_link), { grantConfirmation = true },
                            Modifier.testTag("callback-create-link"), enabled = !state.issuing)
                        if (state.grantCopied) VaultOpsBanner(stringResource(R.string.callback_link_copied, state.issuedGrantId.orEmpty().take(8)), tone = TideBannerTone.SYNCED)
                        if (state.grantFailed) VaultOpsBanner(stringResource(R.string.callback_link_failed), danger = true)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VaultOpsHeading(stringResource(R.string.callback_destinations))
                        VaultOpsNote(stringResource(R.string.callback_return_scope), Modifier.padding(horizontal = 4.dp))
                        TideCard(padding = 16.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                request.success?.let { Text(stringResource(R.string.callback_success_destination, RepositoryCallbackProtocol.displayDestination(it)), Modifier.testTag("callback-destination"),
                                    style = TideType.mono(12.sp), color = c.ink) }
                                (request.error ?: request.success)?.let { Text(stringResource(R.string.callback_error_destination, RepositoryCallbackProtocol.displayDestination(it)),
                                    style = TideType.mono(12.sp), color = c.inkMid) }
                                if (request.success == null && request.error == null) Text(stringResource(R.string.callback_no_destination), style = TideType.copy, color = c.inkMid)
                            }
                        }
                    }
                }
            }
            state.response?.let { response -> item {
                TideCard(padding = 16.dp) {
                    SelectionContainer { Column(Modifier.testTag("callback-result").semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(if (response.success) R.string.callback_success else R.string.callback_error), style = TideType.title,
                            color = if (response.success) c.synced else c.danger)
                        response.values["message"]?.let { Text(it, style = TideType.copy, color = c.inkMid) }
                        response.values["branch"]?.let { Text(stringResource(R.string.callback_result_branch, it), style = TideType.mono(12.5.sp), color = c.ink) }
                        response.values["sha"]?.let { Text(stringResource(R.string.callback_result_commit, it), style = TideType.mono(12.sp), color = c.inkFaint) }
                        response.values["changes"]?.let { Text(stringResource(R.string.callback_result_changes, it), style = TideType.mono(12.5.sp), color = c.ink) }
                        if (response.values["updated"] == "true" || response.values["pull_updated"] == "true") Text(stringResource(R.string.callback_result_pulled), style = TideType.copy, color = c.behind)
                        if (response.values["push_skipped"] == "true") Text(stringResource(R.string.callback_result_skipped), style = TideType.copy, color = c.inkMid)
                        if (response.values["commit_saved"] == "true") Text(stringResource(R.string.callback_result_saved), style = TideType.copy, color = c.ahead)
                    } }
                }
            } }
            if (state.deliveryFailed) item { VaultOpsBanner(stringResource(R.string.callback_delivery_failed), danger = true) }
            if (state.returned) item { VaultOpsBanner(stringResource(R.string.callback_returned), tone = TideBannerTone.SYNCED) }
        }
    }
    if (grantConfirmation) VaultOpsSheet(onDismissRequest = { grantConfirmation = false },
        title = stringResource(R.string.callback_create_link), titleModifier = Modifier.testTag("callback-grant-title"),
        icon = { TideModalIcon(glyph = "⌁", tint = c.ink) },
        confirmButton = { VaultOpsButton(stringResource(R.string.callback_copy_authorized), { grantConfirmation = false; model.issueLink(grantReturn && !grantBackground, grantBackground) },
            Modifier.testTag("callback-create-link-confirm")) },
        dismissButton = { VaultOpsTextButton(stringResource(R.string.repository_actions_cancel), { grantConfirmation = false }, Modifier.fillMaxWidth().testTag("callback-create-link-cancel")) }) {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).testTag("callback-grant-scroll"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.review?.plan?.target?.name.orEmpty(), style = TideType.title, color = c.ink)
            Text(stringResource(callbackActionLabel(state.request?.action)), style = TideType.body, color = c.ink)
            Text(stringResource(R.string.callback_grant_consent), Modifier.testTag("callback-grant-consent"))
            TideCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    VaultOpsToggleRow(grantBackground, { grantBackground = it }, stringResource(R.string.shortcut_background_option), Modifier.testTag("callback-background-choice"))
                    TideHairline()
                    VaultOpsToggleRow(grantReturn && !grantBackground, { grantReturn = it }, stringResource(R.string.callback_grant_return),
                        Modifier.testTag("callback-grant-return"), enabled = !grantBackground)
                }
            }
            if (grantBackground) Text(stringResource(R.string.callback_background_consent))
        }
    }
}

private fun callbackActionLabel(action: RepositoryAction?) = when (action) {
    RepositoryAction.STATUS -> R.string.repository_actions_status; RepositoryAction.PULL -> R.string.repository_actions_pull
    RepositoryAction.PUSH -> R.string.repository_actions_push; RepositoryAction.SYNC -> R.string.repository_actions_sync
    null -> R.string.callback_unknown_action
}
