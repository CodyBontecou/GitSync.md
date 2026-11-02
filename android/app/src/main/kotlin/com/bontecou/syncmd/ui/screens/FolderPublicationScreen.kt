package com.bontecou.syncmd.ui.screens

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.publication.*
import com.bontecou.syncmd.ui.components.DeviceFolderButton
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.components.AddVaultBusyLine
import com.bontecou.syncmd.ui.components.AddVaultFootnote
import com.bontecou.syncmd.ui.components.AddVaultMonoLtr
import com.bontecou.syncmd.ui.components.AddVaultNote
import com.bontecou.syncmd.ui.components.AddVaultProgress
import com.bontecou.syncmd.ui.components.AddVaultSectionLabel
import com.bontecou.syncmd.ui.components.AddVaultTextButton
import com.bontecou.syncmd.ui.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.ui.viewmodels.*

/** A pushed task: closing preserves durable progress; publication success removes this route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPublicationScreen(onBack: () -> Unit, onManageAccounts: () -> Unit,
    register: suspend (FolderPublicationRecord) -> Unit, onReady: (String) -> Unit,
    model: FolderPublicationViewModel = hiltViewModel()) {
    val state by model.state.collectAsState()
    val locked = state.busy || state.loadFailed
    val accounts by model.auth.accounts.collectAsState()
    val available = accounts.filter { it.connected && it.id.startsWith("github:") && !it.login.isNullOrBlank() }
    val record = state.record
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.cancel() }
    }
    // Do not consume restored form values against a temporary null record during the initial read.
    if (state.work == PublicationWork.LOAD && state.records.isEmpty() && state.selected != null) {
        BackHandler { model.cancel(); onBack() }
        Scaffold(modifier = Modifier.testTag("publication-screen"), containerColor = Tide.colors.paper, topBar = {
            WrappingTopBar(title = stringResource(R.string.publication_title), backLabel = stringResource(R.string.publication_back),
                onBack = { model.cancel(); onBack() }, titleModifier = Modifier.testTag("publication-title"), backModifier = Modifier.testTag("publication-back"))
        }) { padding -> Column(Modifier.padding(padding).padding(horizontal = Tide.gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AddVaultProgress(); AddVaultBusyLine(stringResource(R.string.publication_work_load))
        } }
        return
    }
    val editor = "${record?.id}:${state.editorVersion}"
    var accountId by rememberSaveable(editor) { mutableStateOf(record?.account?.id?.let { "github:$it" } ?: model.auth.activeAccountId()) }
    var name by rememberSaveable(editor) { mutableStateOf(record?.repositoryName.orEmpty()) }
    var author by rememberSaveable(editor) { mutableStateOf(record?.input?.authorName.orEmpty()) }
    var email by rememberSaveable(editor) { mutableStateOf(record?.input?.authorEmail.orEmpty()) }
    var message by rememberSaveable(editor) { mutableStateOf(record?.input?.message.orEmpty()) }
    var privateRepo by rememberSaveable(editor) { mutableStateOf(record?.isPrivate ?: true) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var sensitive by remember { mutableStateOf<String?>(null) }
    var exitToSaved by remember { mutableStateOf(false) }
    val review = record?.phase == FolderPublicationPhase.REVIEW
    val prepared = record?.phase == FolderPublicationPhase.PREPARED
    val validName = name.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && name !in setOf(".", "..")
    val validAuthor = runCatching { com.bontecou.syncmd.services.repositories.RepositoryIdentity.validated(author, email) }.isSuccess
    val dirty = record != null && ((review || prepared) && (name != record.repositoryName || privateRepo != record.isPrivate) ||
        review && (author != record.input.authorName || email != record.input.authorEmail || message != record.input.message || accountId != "github:${record.account.id}"))
    fun replacement(): FolderPublicationRecord? {
        val old = record ?: return null
        val selected = available.singleOrNull { it.id == accountId } ?: return null
        val id = selected.id.removePrefix("github:").toLongOrNull() ?: return null
        return old.copy(account = PublicationAccount(id, selected.login!!), repositoryName = name, isPrivate = privateRepo,
            input = old.input.copy(authorName = author, authorEmail = email, message = message))
    }
    fun leave(saved: Boolean) {
        if (state.busy && state.critical) return
        if (dirty) { exitToSaved = saved; confirm = "leave" }
        else { if (state.busy) model.cancel(); if (saved) model.select(null) else onBack() }
    }
    fun saveDraft(onSaved: () -> Unit = {}) {
        if (review) replacement()?.let { model.saveReview(it, onSaved) }
        else if (prepared) model.updateDestination(name, privateRepo, onSaved)
    }
    BackHandler { leave(false) }
    val c = Tide.colors
    Scaffold(modifier = Modifier.testTag("publication-screen"), containerColor = c.paper, bottomBar = {
        if (state.busy) Column(Modifier.fillMaxWidth().background(c.paper)) {
            TideHairline()
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Tide.gutter, vertical = 14.dp)) { PublicationActivity(state, model::cancel) }
        }
    }, topBar = {
        WrappingTopBar(title = stringResource(R.string.publication_title), backLabel = stringResource(R.string.publication_back),
            onBack = { leave(false) }, backEnabled = !state.critical, titleModifier = Modifier.testTag("publication-title"), backModifier = Modifier.testTag("publication-back"))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("publication-list"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (record == null) {
                item {
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PublicationStepEyebrow(1)
                    Text(stringResource(R.string.publication_scope), style = TideType.sans(15.sp).copy(lineHeight = 21.sp), color = c.inkMid,
                        modifier = Modifier.padding(horizontal = 4.dp))
                    AddVaultFootnote(stringResource(R.string.publication_limits))
                  }
                }
                item {
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AddVaultSectionLabel(stringResource(R.string.publication_accounts), Modifier.padding(top = 6.dp))
                    if (available.isEmpty()) AddVaultNote(stringResource(R.string.publication_sign_in), TideBannerTone.AHEAD)
                    if (available.isNotEmpty()) PublicationAccountCard(available.map { it.id to it.login!! }, accountId, !locked) { accountId = it }
                    AddVaultTextButton(stringResource(R.string.publication_manage_accounts), onClick = onManageAccounts, enabled = !state.busy, color = c.behind)
                    DeviceFolderButton(stringResource(R.string.publication_choose), !locked && available.any { it.id == accountId },
                        onFolder = { uri -> accountId?.let { model.chooseFolder(uri, it) } }, onError = model::error, primary = true)
                  }
                }
                item { AddVaultSectionLabel(stringResource(R.string.publication_saved), Modifier.padding(top = 10.dp)) }
                if (state.records.isEmpty() && !state.busy) item { AddVaultFootnote(stringResource(R.string.publication_none)) }
                items(state.records, key = { it.id }) { saved ->
                    PublicationSavedCard("${saved.account.login}/${saved.repositoryName}", stringResource(phaseLabel(saved.phase)), saved.input.review.path,
                        enabled = !state.busy, onClick = { model.select(saved.id) }, modifier = Modifier.testTag("publication-resume-${saved.id}"))
                }
            } else {
                item {
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { PublicationStepEyebrow(publicationStep(record.phase)) }
                        AddVaultTextButton(stringResource(R.string.publication_saved), onClick = { leave(true) }, enabled = !state.busy, color = c.behind)
                    }
                    Text(stringResource(phaseLabel(record.phase)), style = TideType.display(28.sp), color = c.ink,
                        modifier = Modifier.padding(horizontal = 4.dp).testTag("publication-phase"))
                    TideCard(padding = 16.dp) {
                        Text(stringResource(R.string.publication_folder), style = TideType.sans(13.sp, FontWeight.Medium), color = c.inkFaint)
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer { Text(record.input.review.path, modifier = Modifier.testTag("publication-folder"), style = AddVaultMonoLtr, color = c.ink) }
                        if (record.phase != FolderPublicationPhase.COMPLETED) {
                            Spacer(Modifier.height(12.dp))
                            DeviceFolderButton(stringResource(R.string.publication_reconnect), !locked,
                                onFolder = model::reconnect, onError = model::error, initialUri = record.folderUri?.let(Uri::parse))
                        }
                    }
                  }
                }
                if (review) {
                    item {
                      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AddVaultSectionLabel(stringResource(R.string.publication_accounts), Modifier.padding(top = 6.dp))
                        if (available.isNotEmpty()) PublicationAccountCard(available.map { it.id to it.login!! }, accountId, !locked) { accountId = it }
                        AddVaultTextButton(stringResource(R.string.publication_manage_accounts), onClick = onManageAccounts, enabled = !state.busy && !dirty, color = c.behind)
                        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.publication_name)) }, enabled = !locked,
                            colors = tideFieldColors(), shape = Tide.fieldShape,
                            textStyle = TideType.mono(14.sp).copy(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false),
                            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("publication-name"))
                        OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.publication_author)) }, enabled = !locked,
                            colors = tideFieldColors(), shape = Tide.fieldShape, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("publication-author"))
                        OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.publication_email)) }, enabled = !locked,
                            colors = tideFieldColors(), shape = Tide.fieldShape, textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("publication-email"))
                        OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.publication_message)) }, enabled = !locked,
                            colors = tideFieldColors(), shape = Tide.fieldShape, modifier = Modifier.fillMaxWidth().testTag("publication-message"))
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("publication-private").toggleable(privateRepo, enabled = !locked, role = Role.Switch,
                            onValueChange = { if (it) privateRepo = true else confirm = "public" }), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(stringResource(R.string.publication_private), style = TideType.body, color = c.ink); Text(stringResource(if (privateRepo) R.string.publication_private_detail else R.string.publication_public_detail), style = TideType.sans(13.sp), color = c.inkFaint) }
                            Switch(privateRepo, null, colors = tideSwitchColors())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TideOutlineButton(stringResource(R.string.publication_refresh), onClick = model::refresh, enabled = !locked && !dirty, height = 48.dp, modifier = Modifier.testTag("publication-refresh"))
                            if (dirty) AddVaultTextButton(stringResource(R.string.publication_save), onClick = { replacement()?.let { model.saveReview(it) } }, enabled = !locked && validName && replacement() != null,
                                color = c.behind, modifier = Modifier.testTag("publication-save-review"))
                        }
                        TidePrimaryButton(stringResource(R.string.publication_prepare), onClick = { confirm = "prepare" }, enabled = !locked && validName && validAuthor && message.isNotBlank() && message.length <= 65536 && '\u0000' !in message && replacement() != null && record.input.selected.isNotEmpty(),
                            modifier = Modifier.padding(top = 4.dp).testTag("publication-prepare"))
                        AddVaultTextButton(stringResource(R.string.publication_discard), onClick = { confirm = "discard" }, enabled = !locked, color = c.danger,
                            modifier = Modifier.fillMaxWidth().testTag("publication-discard"))
                      }
                    }
                } else {
                    item {
                      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TideCard(padding = 16.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.publication_destination, record.account.login, record.repositoryName),
                                    style = TideType.mono(14.sp, FontWeight.SemiBold).copy(textDirection = TextDirection.Ltr), color = c.ink, modifier = Modifier.weight(1f))
                                TidePill(stringResource(if (record.isPrivate) R.string.publication_private_label else R.string.publication_public_label),
                                    if (record.isPrivate) c.inkMid else c.ahead, if (record.isPrivate) c.well else c.ahead.copy(alpha = 0.12f))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(if (record.isPrivate) R.string.publication_private_detail else R.string.publication_public_detail), style = TideType.sans(13.sp), color = c.inkMid)
                        }
                        if (prepared) {
                            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.publication_name)) }, enabled = !locked,
                                colors = tideFieldColors(), shape = Tide.fieldShape,
                                textStyle = TideType.mono(14.sp).copy(textDirection = TextDirection.Ltr), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false),
                                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("publication-name"))
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("publication-private").toggleable(privateRepo, enabled = !locked,
                                role = Role.Switch, onValueChange = { if (it) privateRepo = true else confirm = "public" }), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.publication_private), Modifier.weight(1f), style = TideType.body, color = c.ink); Switch(privateRepo, null, colors = tideSwitchColors())
                            }
                            if (dirty) AddVaultTextButton(stringResource(R.string.publication_save_destination), onClick = { saveDraft() }, enabled = !locked && validName, color = c.behind,
                                modifier = Modifier.testTag("publication-save-destination"))
                        }
                        if (state.error == null) record.problem?.let { AddVaultNote(PublicationApiException(it).message.orEmpty(), TideBannerTone.DANGER) }
                        record.prepared?.let { SelectionContainer { Text(stringResource(R.string.publication_commit, it.commit), style = AddVaultMonoLtr, color = c.inkMid, modifier = Modifier.padding(horizontal = 4.dp)) } }
                        AddVaultFootnote(stringResource(R.string.publication_pending_help))
                        AddVaultTextButton(stringResource(R.string.publication_manage_accounts), onClick = onManageAccounts, enabled = !state.busy, color = c.behind)
                        when (record.phase) {
                            FolderPublicationPhase.PREPARING -> TidePrimaryButton(stringResource(R.string.publication_prepare), onClick = { model.prepare(record) }, enabled = !locked,
                                modifier = Modifier.testTag("publication-prepare"))
                            FolderPublicationPhase.CREATION_UNKNOWN, FolderPublicationPhase.CREATING_REMOTE -> {
                                AddVaultNote(stringResource(R.string.publication_unknown), TideBannerTone.AHEAD)
                                TidePrimaryButton(stringResource(R.string.publication_check_destination), onClick = model::reconcile, enabled = !locked, modifier = Modifier.testTag("publication-reconcile"))
                            }
                            else -> TidePrimaryButton(stringResource(when(record.phase) {
                                    FolderPublicationPhase.PREPARED -> R.string.publication_publish
                                    FolderPublicationPhase.PUSH_UNKNOWN -> R.string.publication_retry_upload
                                    FolderPublicationPhase.PUBLISHED -> R.string.publication_finish
                                    FolderPublicationPhase.COMPLETED -> R.string.publication_open
                                    else -> R.string.publication_upload
                                }), onClick = { if (record.phase == FolderPublicationPhase.PREPARED) confirm = "publish" else model.publish(register, onReady) }, enabled = !locked && !dirty,
                                modifier = Modifier.testTag("publication-publish"))
                        }
                      }
                    }
                }
                if (!state.busy && state.error != null) item { PublicationActivity(state, model::cancel) }
                if (record.phase == FolderPublicationPhase.REVIEW) {
                    item {
                      Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AddVaultSectionLabel(stringResource(R.string.publication_files))
                        val selected = record.input.review.files.filter { it.path in record.input.selected }
                        Text(stringResource(R.string.publication_selected, selected.size, Formatter.formatFileSize(context, selected.sumOf { it.size })),
                            style = TideType.mono(12.5.sp, FontWeight.Medium), color = c.ink, modifier = Modifier.padding(horizontal = 4.dp).testTag("publication-selection"))
                        record.input.review.warnings.forEach { AddVaultNote(it, TideBannerTone.AHEAD) }
                      }
                    }
                    items(record.input.review.files, key = { it.path }) { file ->
                        val checked = file.path in record.input.selected
                        Row(Modifier.fillMaxWidth().clip(Tide.fieldShape).background(c.card, Tide.fieldShape).heightIn(min = 52.dp).toggleable(checked, enabled = file.eligible && !locked, role = Role.Checkbox,
                            onValueChange = { if (it && file.sensitive) sensitive = file.path else model.toggle(file.path) }).testTag("publication-file-${file.path}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, null, enabled = file.eligible && !locked, colors = CheckboxDefaults.colors(checkedColor = c.ink, uncheckedColor = c.lineSoft, checkmarkColor = c.paper,
                                disabledCheckedColor = c.inkFaint, disabledUncheckedColor = c.line))
                            Spacer(Modifier.width(4.dp))
                            Column(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 4.dp)) {
                                Text(file.path, style = TideType.mono(13.sp).copy(textDirection = TextDirection.Ltr), color = if (file.eligible) c.ink else c.inkFaint)
                                Text(stringResource(R.string.publication_file_size, Formatter.formatFileSize(context, file.size), stringResource(when {
                                    file.ignored -> R.string.publication_file_ignored
                                    file.size > FolderPublicationLimits.FILE_BYTES -> R.string.publication_file_large
                                    file.issue != null -> R.string.publication_file_unsupported
                                    file.sensitive -> R.string.publication_file_sensitive
                                    else -> R.string.publication_file_regular
                                })), style = TideType.sans(12.sp), color = if (file.sensitive || file.issue != null) c.ahead else c.inkFaint)
                            }
                        }
                    }
                }
            }
            if (record == null && !state.busy && state.error != null) item { PublicationActivity(state, model::cancel) }
            item { AddVaultTextButton(stringResource(R.string.publication_reload), onClick = model::reload, enabled = !state.busy && !dirty, color = c.inkMid,
                modifier = Modifier.fillMaxWidth().testTag("publication-reload")) }
        }
    }
    if (confirm != null) {
        val kind = confirm
        val destructive = kind == "discard" || kind == "public"
        TideDialog(onDismissRequest = { confirm = null },
            icon = { if (destructive) TideModalIcon() else TideModalIcon(glyph = "↑", tint = Tide.colors.behind) },
            title = stringResource(when(kind) {
            "public" -> R.string.publication_public_title; "prepare" -> R.string.publication_prepare_title
            "discard" -> R.string.publication_discard_title
            "leave" -> R.string.publication_leave_title; else -> R.string.publication_publish_title
        }), text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("publication-confirm-scroll")) { Text(when(kind) {
                "public" -> stringResource(R.string.publication_public_warning)
                "prepare" -> stringResource(R.string.publication_prepare_warning)
                "leave" -> stringResource(R.string.publication_leave_warning)
                "discard" -> stringResource(R.string.publication_discard_warning)
                else -> record?.let { stringResource(R.string.publication_publish_warning, it.account.login, it.repositoryName,
                    stringResource(if (it.isPrivate) R.string.publication_private_label else R.string.publication_public_label)) }.orEmpty()
            }) }
        }, confirmButton = { PublicationConfirmPill(stringResource(when(kind) {
            "public" -> R.string.publication_public_confirm; "prepare" -> R.string.publication_prepare
            "leave" -> R.string.publication_save_close; "discard" -> R.string.publication_discard
            else -> R.string.publication_publish
        }), destructive = destructive, enabled = !locked && (kind != "leave" || validName && (prepared || replacement() != null)), onClick = {
            confirm = null
            when(kind) {
                "public" -> privateRepo = false
                "prepare" -> replacement()?.let(model::prepare)
                "discard" -> model.discardReview()
                "leave" -> saveDraft { if (exitToSaved) model.select(null) else onBack() }
                else -> model.publish(register, onReady)
            }
        }, modifier = Modifier.testTag("publication-confirm")) }, dismissButton = {
            Column(Modifier.fillMaxWidth()) {
                if (kind == "leave") AddVaultTextButton(stringResource(R.string.publication_discard_close), onClick = { confirm = null; if (exitToSaved) model.select(null) else onBack() },
                    color = Tide.colors.danger, modifier = Modifier.fillMaxWidth().testTag("publication-leave-unsaved"))
                AddVaultTextButton(stringResource(R.string.publication_cancel), onClick = { confirm = null }, modifier = Modifier.fillMaxWidth().testTag("publication-confirm-cancel"))
            }
        })
    }
    sensitive?.let { path -> TideDialog(onDismissRequest = { sensitive = null }, icon = { TideModalIcon(tint = Tide.colors.ahead) }, title = stringResource(R.string.publication_sensitive_title),
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("publication-sensitive-scroll")) {
            Text(stringResource(R.string.publication_sensitive_warning, path))
        } },
        confirmButton = { PublicationConfirmPill(stringResource(R.string.publication_include), destructive = false, enabled = true, onClick = { sensitive = null; model.toggle(path) },
            modifier = Modifier.testTag("publication-include-sensitive")) },
        dismissButton = { AddVaultTextButton(stringResource(R.string.publication_cancel), onClick = { sensitive = null }, modifier = Modifier.fillMaxWidth().testTag("publication-sensitive-cancel")) }) }
    state.candidate?.let { candidate -> TideDialog(onDismissRequest = model::clearCandidate, icon = { TideModalIcon(glyph = "?", tint = Tide.colors.behind) }, title = stringResource(R.string.publication_adopt_title),
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("publication-adopt-scroll")) {
            Text(stringResource(R.string.publication_adopt_warning, candidate.owner.login, candidate.name,
                stringResource(if(candidate.isPrivate) R.string.publication_private_label else R.string.publication_public_label), candidate.id, candidate.htmlUrl))
        } },
        confirmButton = { PublicationConfirmPill(stringResource(R.string.publication_adopt), destructive = false, enabled = !locked, onClick = model::adopt,
            modifier = Modifier.testTag("publication-adopt")) },
        dismissButton = { AddVaultTextButton(stringResource(R.string.publication_cancel), onClick = model::clearCandidate, modifier = Modifier.fillMaxWidth().testTag("publication-adopt-cancel")) }) }
}

/** Mono "Step 2 of 3" eyebrow, as on iOS FolderPublicationView. */
@Composable private fun PublicationStepEyebrow(step: Int) {
    Text(stringResource(R.string.tide_onboarding_publication_step, step), style = TideType.mono(12.sp, FontWeight.Medium), color = Tide.colors.inkFaint,
        modifier = Modifier.padding(horizontal = 4.dp))
}

private fun publicationStep(phase: FolderPublicationPhase): Int = when (phase) {
    FolderPublicationPhase.REVIEW, FolderPublicationPhase.PREPARING -> 2
    else -> 3
}

/** Verified accounts as radio rows in one card. */
@Composable private fun PublicationAccountCard(accounts: List<Pair<String, String>>, selectedId: String?, enabled: Boolean, choose: (String) -> Unit) {
    TideCard(padding = 0.dp) {
        accounts.forEachIndexed { i, (id, login) ->
            if (i > 0) TideHairline(Modifier.padding(start = 52.dp))
            AccountChoice("@$login", id == selectedId, enabled) { choose(id) }
        }
    }
}

@Composable private fun AccountChoice(label: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    val c = Tide.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = c.ink, unselectedColor = c.lineSoft,
            disabledSelectedColor = c.inkFaint, disabledUnselectedColor = c.line))
        Text(label, Modifier.weight(1f).padding(start = 4.dp), style = TideType.mono(14.sp, FontWeight.Medium).copy(textDirection = TextDirection.Ltr),
            color = if (enabled) c.ink else c.inkFaint)
    }
}

/** A saved publication you can resume: destination, phase, original folder. */
@Composable private fun PublicationSavedCard(destination: String, phase: String, path: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tide.colors
    Row(modifier.fillMaxWidth().clip(Tide.cardShape).background(c.card, Tide.cardShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else 0.5f).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(destination, style = TideType.sans(16.sp, FontWeight.SemiBold).copy(textDirection = TextDirection.Ltr), color = c.ink)
            Text(phase, style = TideType.sans(13.sp, FontWeight.Medium), color = c.ahead)
            Text(path, style = TideType.meta.copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
        }
        TideChevron()
    }
}

/** Dialog confirm pill (ink, or danger when destructive) with real enabled semantics. */
@Composable private fun PublicationConfirmPill(label: String, destructive: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tide.colors
    val fill = if (destructive) c.danger else c.ink
    Box(modifier.fillMaxWidth().heightIn(min = 56.dp).clip(CircleShape).background(fill.copy(alpha = if (enabled) 1f else 0.3f), CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(label, style = TideType.button, color = if (destructive) Color.White else c.paper)
    }
}

@Composable private fun PublicationActivity(state: FolderPublicationViewModel.State, cancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.busy) {
            AddVaultProgress()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                    AddVaultBusyLine(stringResource(if (state.stopping) R.string.publication_stopping else when(state.work) {
                        PublicationWork.INSPECT -> R.string.publication_work_inspect; PublicationWork.SAVE -> R.string.publication_work_save
                        PublicationWork.REFRESH -> R.string.publication_work_refresh; PublicationWork.PREPARE -> R.string.publication_work_prepare
                        PublicationWork.CREATE -> R.string.publication_work_create; PublicationWork.UPLOAD -> R.string.publication_work_upload
                        PublicationWork.REGISTER -> R.string.publication_work_register; PublicationWork.RECONCILE -> R.string.publication_work_check
                        PublicationWork.ADOPT -> R.string.publication_work_adopt; PublicationWork.RECONNECT -> R.string.publication_work_reconnect
                        else -> R.string.publication_work_load
                    }))
                }
                TideOutlineButton(stringResource(R.string.publication_stop), onClick = cancel, enabled = !state.stopping, height = 48.dp,
                    modifier = Modifier.testTag("publication-stop"))
            }
        }
        state.error?.let { AddVaultNote(it, TideBannerTone.DANGER, textModifier = Modifier.testTag("publication-error").semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

private fun phaseLabel(phase: FolderPublicationPhase): Int = when(phase) {
    FolderPublicationPhase.REVIEW -> R.string.publication_phase_review
    FolderPublicationPhase.PREPARING -> R.string.publication_phase_preparing
    FolderPublicationPhase.PREPARED -> R.string.publication_phase_prepared
    FolderPublicationPhase.CREATING_REMOTE, FolderPublicationPhase.CREATION_UNKNOWN -> R.string.publication_phase_creation_unknown
    FolderPublicationPhase.REMOTE_CREATED -> R.string.publication_phase_remote
    FolderPublicationPhase.PUSHING, FolderPublicationPhase.PUSH_UNKNOWN -> R.string.publication_phase_push_unknown
    FolderPublicationPhase.PUBLISHED -> R.string.publication_phase_published
    FolderPublicationPhase.COMPLETED -> R.string.publication_phase_completed
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable private fun PublicationBlocksPreview() {
    Column(Modifier.background(Tide.colors.paper).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PublicationStepEyebrow(1)
        PublicationAccountCard(listOf("github:1" to "octocat", "github:2" to "hubot"), "github:1", enabled = true) {}
        PublicationSavedCard("octocat/field-notes", "Ready to publish", "/storage/emulated/0/Documents/Field notes", enabled = true, onClick = {})
        PublicationConfirmPill("Publish", destructive = false, enabled = true, onClick = {})
        PublicationConfirmPill("Discard review", destructive = true, enabled = false, onClick = {})
    }
}

@Preview(name = "Publication blocks · light", heightDp = 640) @Composable
private fun PublicationBlocksLight() = SyncMdTheme(darkTheme = false) { PublicationBlocksPreview() }

@Preview(name = "Publication blocks · dark", heightDp = 640) @Composable
private fun PublicationBlocksDark() = SyncMdTheme(darkTheme = true) { PublicationBlocksPreview() }
