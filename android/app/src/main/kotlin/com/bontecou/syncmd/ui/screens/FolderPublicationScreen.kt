package com.bontecou.syncmd.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.publication.FolderPublicationLimits
import com.bontecou.syncmd.services.publication.FolderPublicationPhase
import com.bontecou.syncmd.services.publication.FolderPublicationRecord
import com.bontecou.syncmd.services.publication.PublicationAccount
import com.bontecou.syncmd.services.publication.PublicationApiException
import com.bontecou.syncmd.ui.components.AddVaultStepBars
import com.bontecou.syncmd.ui.components.addVaultFriendlyPath
import com.bontecou.syncmd.ui.components.addVaultInitials
import com.bontecou.syncmd.ui.components.addVaultSize
import com.bontecou.syncmd.ui.components.addVaultSizePair
import com.bontecou.syncmd.ui.components.rememberAddVaultFolderPicker
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Avatar
import com.bontecou.syncmd.ui.theme.M3Badge
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Chevron
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3ProgressBar
import com.bontecou.syncmd.ui.theme.M3RadioIndicator
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3Spinner
import com.bontecou.syncmd.ui.theme.M3Switch
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.theme.m3GroupedItem
import com.bontecou.syncmd.ui.viewmodels.FolderPublicationViewModel
import com.bontecou.syncmd.ui.viewmodels.PublicationWork

/**
 * Board PublishFolder: choose a folder (step 1), review files and destination (2), then the prepared
 * commit is published (3); saved progress can be resumed. A pushed task: closing preserves durable
 * progress; publication success removes this route.
 */
@Composable
fun FolderPublicationScreen(onBack: () -> Unit, onManageAccounts: () -> Unit,
    register: suspend (FolderPublicationRecord) -> Unit, onReady: (String) -> Unit,
    model: FolderPublicationViewModel = hiltViewModel(), resume: String? = null) {
    val state by model.state.collectAsState()
    val upload by model.upload.collectAsState()
    // Publishing creates a new vault, so locked installs unlock first (as on iOS).
    val unlockGate = com.bontecou.syncmd.ui.util.rememberUnlockGate()
    val locked = state.busy || state.loadFailed
    val accounts by model.auth.accounts.collectAsState()
    val available = accounts.filter { it.connected && it.id.startsWith("github:") && !it.login.isNullOrBlank() }
    val record = state.record
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.cancel() }
    }
    // "Resume publishing" from Add a vault opens one saved publication once its records load.
    var resumed by rememberSaveable { mutableStateOf(false) }
    if (resume != null && !resumed && !state.busy && state.records.any { it.id == resume }) { resumed = true; if (state.selected != resume) model.select(resume) }
    val c = M3.colors
    // Do not consume restored form values against a temporary null record during the initial read.
    if (state.work == PublicationWork.LOAD && state.records.isEmpty() && state.selected != null) {
        BackHandler { model.cancel(); onBack() }
        Column(Modifier.fillMaxSize().background(c.surface).testTag("publication-screen")) {
            M3TopBar(stringResource(R.string.add_publish_title), Modifier.testTag("publication-title"), onBack = { model.cancel(); onBack() })
            M3IndeterminateProgressBar(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), description = stringResource(R.string.publication_work_load))
        }
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
    val chooseFolder = rememberAddVaultFolderPicker(onFolder = { uri -> accountId?.let { model.chooseFolder(uri, it) } }, onError = model::error, requireAccess = false)
    val reconnectFolder = rememberAddVaultFolderPicker(onFolder = model::reconnect, onError = model::error, initialUri = record?.folderUri?.let(Uri::parse), requireAccess = false)
    BackHandler { leave(false) }
    val step = when (record?.phase) { null -> 1; FolderPublicationPhase.REVIEW, FolderPublicationPhase.PREPARING -> 2; else -> 3 }
    val selectedFiles = record?.input?.review?.files?.filter { it.path in record.input.selected }.orEmpty()
    val selectedBytes = selectedFiles.sumOf { it.size }
    Box(Modifier.fillMaxSize().background(c.surface).testTag("publication-screen")) {
      Column(Modifier.fillMaxSize()) {
        M3TopBar(stringResource(R.string.add_publish_title), Modifier.testTag("publication-title"), onBack = { leave(false) })
        LazyColumn(Modifier.weight(1f).imePadding().testTag("publication-list"), contentPadding = PaddingValues(bottom = if (state.busy) 140.dp else 40.dp)) {
            item(key = "steps") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.add_publish_step, step), style = M3Type.badge, color = c.primary)
                    AddVaultStepBars(step, Modifier.weight(1f))
                    if (record != null) M3Button(stringResource(R.string.add_publish_saved_progress), { leave(true) }, enabled = !state.busy, style = M3ButtonStyle.Text)
                }
            }
            if (record == null) {
                item(key = "intro") {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(stringResource(R.string.add_publish_intro), style = M3Type.body, color = c.onVar, modifier = Modifier.padding(top = 12.dp))
                        Text(stringResource(R.string.add_publish_limits), style = M3Type.caption, color = c.onVar, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                item(key = "account") {
                    PublicationAccounts(available.map { it.id to it.login!! }, accountId, !locked, onChoose = { accountId = it }, onManage = onManageAccounts, manageEnabled = !state.busy)
                }
                item(key = "choose") {
                    M3Button(stringResource(R.string.add_publish_choose), chooseFolder, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp).testTag("device-folder-picker"),
                        size = M3ButtonSize.Hero, icon = R.drawable.ic_m3_folder, enabled = !locked && available.any { it.id == accountId })
                }
                if (state.records.isNotEmpty()) {
                    item(key = "saved-title") { M3SectionLabel(stringResource(R.string.add_publish_saved_progress), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) }
                    item(key = "saved") {
                        M3ListCard(Modifier.padding(horizontal = 16.dp)) {
                            state.records.forEachIndexed { index, saved ->
                                Row(Modifier.fillMaxWidth().m3TopDivider(index > 0, c.outlineVar)
                                    .then(Modifier.selectable(false, enabled = !state.busy, role = Role.Button) { model.select(saved.id) })
                                    .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp).testTag("publication-resume-${saved.id}"),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(listOf(saved.account.login, saved.repositoryName).joinToString("/"), style = M3Type.code.copy(fontSize = M3Type.bodyMedium.fontSize, fontWeight = FontWeight.SemiBold), color = c.on)
                                        Text(stringResource(phaseLabel(saved.phase)), style = M3Type.bodyMedium,
                                            color = if (saved.phase == FolderPublicationPhase.REVIEW) c.onVar else c.ahead)
                                        Text(addVaultFriendlyPath(context, saved.input.review.path, "/"), style = M3Type.meta, color = c.onVar)
                                    }
                                    M3Chevron()
                                }
                            }
                        }
                    }
                }
            } else {
                item(key = "phase") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(phaseLabel(record.phase)), style = M3Type.headline, color = c.on,
                            modifier = Modifier.padding(start = 4.dp, top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("publication-phase"))
                        Column(Modifier.fillMaxWidth().padding(top = 16.dp).background(c.cLow, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Text(stringResource(R.string.add_publish_original), style = M3Type.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.onVar)
                            SelectionContainer { Text(addVaultFriendlyPath(context, record.input.review.path, "/"), style = M3Type.code, color = c.on,
                                modifier = Modifier.padding(top = 2.dp).testTag("publication-folder")) }
                            if (record.phase != FolderPublicationPhase.COMPLETED) M3Button(stringResource(R.string.add_publish_reconnect), reconnectFolder,
                                Modifier.padding(top = 12.dp), style = M3ButtonStyle.Tonal, icon = R.drawable.ic_m3_folder, enabled = !locked)
                        }
                    }
                }
                if (review) {
                    item(key = "review-account") {
                        PublicationAccounts(available.map { it.id to it.login!! }, accountId, !locked, onChoose = { accountId = it }, onManage = onManageAccounts,
                            manageEnabled = !state.busy && !dirty)
                    }
                    item(key = "review-fields") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            M3TextField(name, { name = it }, stringResource(R.string.add_publish_repo_name), Modifier.testTag("publication-name"), mono = true, enabled = !locked,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false))
                            M3TextField(author, { author = it }, stringResource(R.string.add_publish_author_name), Modifier.testTag("publication-author"), enabled = !locked)
                            M3TextField(email, { email = it }, stringResource(R.string.add_publish_author_email), Modifier.testTag("publication-email"), mono = true, enabled = !locked,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                            M3TextField(message, { message = it }, stringResource(R.string.add_publish_message), Modifier.heightIn(min = 72.dp).testTag("publication-message"),
                                singleLine = false, minLines = 2, enabled = !locked)
                        }
                    }
                    item(key = "review-private") { PrivateSwitch(privateRepo, !locked) { if (it) privateRepo = true else confirm = "public" } }
                    item(key = "review-actions") {
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            M3Button(stringResource(R.string.add_publish_refresh), model::refresh, Modifier.testTag("publication-refresh"), style = M3ButtonStyle.Outlined,
                                size = M3ButtonSize.Large, icon = R.drawable.ic_m3_sync, enabled = !locked && !dirty)
                            if (dirty) M3Button(stringResource(R.string.add_publish_save_review), { replacement()?.let { model.saveReview(it) } }, Modifier.testTag("publication-save-review"),
                                style = M3ButtonStyle.Text, size = M3ButtonSize.Large, enabled = !locked && validName && replacement() != null)
                        }
                    }
                    item(key = "files-title") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            M3SectionLabel(stringResource(R.string.add_publish_files), Modifier.weight(1f), contentPadding = PaddingValues(0.dp))
                            Text(stringResource(R.string.add_publish_selected, selectedFiles.size, addVaultSize(selectedBytes)),
                                style = M3Type.meta.copy(fontWeight = FontWeight.Medium), color = c.on, modifier = Modifier.testTag("publication-selection"))
                        }
                    }
                    if (record.input.review.warnings.isNotEmpty()) item(key = "warnings") {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            record.input.review.warnings.forEach { M3Note(it, tone = M3NoteTone.Ahead) }
                        }
                    }
                    itemsIndexed(record.input.review.files, key = { _, file -> file.path }) { index, file ->
                        val checked = file.path in record.input.selected
                        val count = record.input.review.files.size
                        Row(Modifier.padding(horizontal = 16.dp).m3GroupedItem(index, count, c.cLow).fillMaxWidth().m3TopDivider(index > 0, c.outlineVar)
                            .toggleable(checked, enabled = file.eligible && !locked, role = Role.Checkbox,
                                onValueChange = { if (it && file.sensitive) sensitive = file.path else model.toggle(file.path) })
                            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp).testTag("publication-file-${file.path}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, null, enabled = file.eligible && !locked,
                                colors = CheckboxDefaults.colors(checkedColor = c.primary, uncheckedColor = c.onVar, checkmarkColor = c.onPrimary))
                            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                Text(file.path, style = M3Type.code, color = if (file.eligible) c.on else c.onVar)
                                val size = addVaultSize(file.size)
                                Text(when {
                                    file.ignored -> stringResource(R.string.add_publish_file_ignored, size)
                                    file.size > FolderPublicationLimits.FILE_BYTES -> stringResource(R.string.add_publish_file_large, size)
                                    file.issue != null -> stringResource(R.string.add_publish_file_issue, size, stringResource(R.string.publication_file_unsupported))
                                    file.sensitive -> stringResource(R.string.add_publish_file_sensitive, size)
                                    else -> stringResource(R.string.add_publish_file_regular, size)
                                }, style = M3Type.caption.copy(fontWeight = if (file.sensitive || file.issue != null) FontWeight.SemiBold else FontWeight.Normal),
                                    color = if (file.sensitive || file.issue != null) c.ahead else c.onVar)
                            }
                        }
                    }
                    item(key = "review-commit") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
                            M3Button(stringResource(R.string.add_publish_prepare), { confirm = "prepare" }, Modifier.fillMaxWidth().testTag("publication-prepare"), size = M3ButtonSize.Hero,
                                enabled = !locked && validName && validAuthor && message.isNotBlank() && message.length <= 65536 && '\u0000' !in message && replacement() != null &&
                                    record.input.selected.isNotEmpty())
                            M3Button(stringResource(R.string.add_publish_discard), { confirm = "discard" }, Modifier.fillMaxWidth().padding(top = 4.dp).testTag("publication-discard"),
                                style = M3ButtonStyle.Text, destructive = true, enabled = !locked)
                        }
                    }
                } else {
                    item(key = "destination") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                            Column(Modifier.fillMaxWidth().background(c.cLow, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(listOf(record.account.login, record.repositoryName).joinToString("/"), style = M3Type.code.copy(fontSize = M3Type.bodyMedium.fontSize, fontWeight = FontWeight.SemiBold),
                                        color = c.on, modifier = Modifier.weight(1f, fill = false))
                                    M3Badge(stringResource(if (record.isPrivate) R.string.add_meta_private else R.string.add_meta_public), if (record.isPrivate) M3Tone.Muted else M3Tone.Ahead)
                                }
                                Text(stringResource(if (record.isPrivate) R.string.add_publish_private_on else R.string.add_publish_private_off), style = M3Type.bodySmall,
                                    color = c.onVar, modifier = Modifier.padding(top = 6.dp))
                            }
                            if (prepared) {
                                M3TextField(name, { name = it }, stringResource(R.string.add_publish_repo_name), Modifier.padding(top = 16.dp).testTag("publication-name"), mono = true,
                                    enabled = !locked, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrect = false))
                            }
                        }
                    }
                    if (prepared) item(key = "prepared-private") {
                        Column {
                            PrivateSwitch(privateRepo, !locked) { if (it) privateRepo = true else confirm = "public" }
                            if (dirty) M3Button(stringResource(R.string.publication_save_destination), { saveDraft() }, Modifier.padding(start = 16.dp).testTag("publication-save-destination"),
                                style = M3ButtonStyle.Text, enabled = !locked && validName)
                        }
                    }
                    item(key = "dest-status") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (record.phase == FolderPublicationPhase.CREATION_UNKNOWN || record.phase == FolderPublicationPhase.CREATING_REMOTE)
                                M3Note(stringResource(R.string.add_publish_unknown_note), tone = M3NoteTone.Ahead, icon = R.drawable.ic_m3_warning)
                            if (state.error == null) record.problem?.let { M3Note(PublicationApiException(it).message.orEmpty(), tone = M3NoteTone.Warn) }
                            record.prepared?.let { SelectionContainer { Text(stringResource(R.string.add_publish_saved_commit, it.commit.take(7)), style = M3Type.meta, color = c.onVar,
                                modifier = Modifier.padding(horizontal = 4.dp)) } }
                            if (record.phase != FolderPublicationPhase.COMPLETED) {
                                Text(stringResource(R.string.add_publish_keep_note), style = M3Type.caption, color = c.onVar, modifier = Modifier.padding(horizontal = 4.dp))
                                M3Button(stringResource(R.string.add_publish_manage_accounts), onManageAccounts, style = M3ButtonStyle.Text, enabled = !state.busy)
                            }
                            when (record.phase) {
                                FolderPublicationPhase.COMPLETED -> M3Button(stringResource(R.string.add_publish_open), { unlockGate.require { model.publish(register, onReady) } },
                                    Modifier.fillMaxWidth().testTag("publication-publish"), size = M3ButtonSize.Hero, enabled = !locked)
                                FolderPublicationPhase.PREPARING -> M3Button(stringResource(R.string.add_publish_prepare), { model.prepare(record) },
                                    Modifier.fillMaxWidth().testTag("publication-prepare"), size = M3ButtonSize.Hero, enabled = !locked)
                                FolderPublicationPhase.CREATION_UNKNOWN, FolderPublicationPhase.CREATING_REMOTE -> M3Button(stringResource(R.string.add_publish_check), model::reconcile,
                                    Modifier.fillMaxWidth().testTag("publication-reconcile"), size = M3ButtonSize.Hero, icon = R.drawable.ic_m3_search, enabled = !locked)
                                else -> M3Button(stringResource(when (record.phase) {
                                        FolderPublicationPhase.PREPARED -> R.string.add_publish_publish
                                        FolderPublicationPhase.PUBLISHED -> R.string.publication_finish
                                        else -> R.string.add_publish_continue
                                    }), { if (record.phase == FolderPublicationPhase.PREPARED) confirm = "publish" else unlockGate.require { model.publish(register, onReady) } },
                                    Modifier.fillMaxWidth().testTag("publication-publish"), size = M3ButtonSize.Hero, icon = R.drawable.ic_m3_publish, enabled = !locked && !dirty)
                            }
                        }
                    }
                }
            }
            if (!state.busy && state.error != null) item(key = "error") {
                M3Note(state.error.orEmpty(), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).testTag("publication-error").semantics { liveRegion = LiveRegionMode.Polite },
                    tone = M3NoteTone.Warn)
            }
            item(key = "reload") {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                    M3Button(stringResource(R.string.add_publish_reload), model::reload, Modifier.testTag("publication-reload"), style = M3ButtonStyle.Text,
                        enabled = !state.busy && !dirty)
                }
            }
        }
      }
      if (state.busy) PublicationActivity(state, upload, selectedBytes.takeIf { it > 0 }, model::cancel, Modifier.align(Alignment.BottomCenter))
    }
    if (confirm != null) {
        val kind = confirm
        val dismiss = { confirm = null }
        when (kind) {
            "public" -> M3Dialog(dismiss, stringResource(R.string.add_publish_public_title), icon = R.drawable.ic_m3_warning, iconTint = c.error,
                text = stringResource(R.string.add_publish_public_body)) {
                M3DialogAction(stringResource(R.string.add_publish_cancel), dismiss, Modifier.testTag("publication-confirm-cancel"))
                M3DialogAction(stringResource(R.string.add_publish_public_confirm), { confirm = null; privateRepo = false }, Modifier.testTag("publication-confirm"),
                    destructive = true, filled = true, enabled = !locked)
            }
            "publish" -> M3Dialog(dismiss, stringResource(R.string.add_publish_confirm_title), icon = R.drawable.ic_m3_publish,
                text = record?.let { stringResource(R.string.add_publish_confirm_body, "${it.account.login}/${it.repositoryName}",
                    stringResource(if (it.isPrivate) R.string.add_meta_private else R.string.add_meta_public)) },
                content = record?.let { r -> { Text(stringResource(R.string.add_publish_confirm_dest, "${r.account.login}/${r.repositoryName}",
                    stringResource(if (r.isPrivate) R.string.add_meta_private else R.string.add_meta_public), r.prepared?.commit?.take(7).orEmpty(),
                    androidx.compose.ui.res.pluralStringResource(R.plurals.add_clone_files, selectedFiles.size, selectedFiles.size), addVaultSize(selectedBytes)),
                    style = M3Type.meta, color = c.on, modifier = Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) } }) {
                M3DialogAction(stringResource(R.string.add_publish_cancel), dismiss, Modifier.testTag("publication-confirm-cancel"))
                M3DialogAction(stringResource(R.string.add_publish_confirm), { confirm = null; unlockGate.require { model.publish(register, onReady) } },
                    Modifier.testTag("publication-confirm"), filled = true, enabled = !locked)
            }
            else -> M3Dialog(dismiss, stringResource(when (kind) {
                    "prepare" -> R.string.publication_prepare_title; "discard" -> R.string.publication_discard_title; else -> R.string.publication_leave_title
                }), icon = if (kind == "discard") R.drawable.ic_m3_delete else R.drawable.ic_m3_info, iconTint = if (kind == "discard") c.error else c.primary,
                stackedActions = kind == "leave",
                content = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("publication-confirm-scroll")) {
                    Text(stringResource(when (kind) {
                        "prepare" -> R.string.publication_prepare_warning; "discard" -> R.string.publication_discard_warning; else -> R.string.publication_leave_warning
                    }))
                } }) {
                if (kind == "leave") {
                    M3Button(stringResource(R.string.publication_save_close), { confirm = null; saveDraft { if (exitToSaved) model.select(null) else onBack() } },
                        Modifier.fillMaxWidth().testTag("publication-confirm"), enabled = !locked && validName && (prepared || replacement() != null))
                    M3Button(stringResource(R.string.publication_discard_close), { confirm = null; if (exitToSaved) model.select(null) else onBack() },
                        Modifier.fillMaxWidth().testTag("publication-leave-unsaved"), style = M3ButtonStyle.Text, destructive = true)
                    M3Button(stringResource(R.string.add_publish_cancel), dismiss, Modifier.fillMaxWidth().testTag("publication-confirm-cancel"), style = M3ButtonStyle.Text)
                } else {
                    M3DialogAction(stringResource(R.string.add_publish_cancel), dismiss, Modifier.testTag("publication-confirm-cancel"))
                    M3DialogAction(stringResource(if (kind == "prepare") R.string.add_publish_prepare else R.string.add_publish_discard), {
                        confirm = null
                        if (kind == "prepare") replacement()?.let(model::prepare) else model.discardReview()
                    }, Modifier.testTag("publication-confirm"), destructive = kind == "discard", filled = true, enabled = !locked)
                }
            }
        }
    }
    sensitive?.let { path ->
        M3Dialog({ sensitive = null }, stringResource(R.string.publication_sensitive_title), icon = R.drawable.ic_m3_warning, iconTint = c.tertiary,
            content = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("publication-sensitive-scroll")) {
                Text(stringResource(R.string.publication_sensitive_warning, path)) } }) {
            M3DialogAction(stringResource(R.string.add_publish_cancel), { sensitive = null }, Modifier.testTag("publication-sensitive-cancel"))
            M3DialogAction(stringResource(R.string.publication_include), { sensitive = null; model.toggle(path) }, Modifier.testTag("publication-include-sensitive"), filled = true)
        }
    }
    state.candidate?.let { candidate ->
        M3Dialog(model::clearCandidate, stringResource(R.string.publication_adopt_title), icon = R.drawable.ic_m3_help,
            content = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("publication-adopt-scroll")) {
                Text(stringResource(R.string.publication_adopt_warning, candidate.owner.login, candidate.name,
                    stringResource(if (candidate.isPrivate) R.string.publication_private_label else R.string.publication_public_label), candidate.id, candidate.htmlUrl))
            } }) {
            M3DialogAction(stringResource(R.string.add_publish_cancel), model::clearCandidate, Modifier.testTag("publication-adopt-cancel"))
            M3DialogAction(stringResource(R.string.publication_adopt), model::adopt, Modifier.testTag("publication-adopt"), filled = true, enabled = !locked)
        }
    }
}

/** "GitHub account" radio card (avatar + @login per verified account) and "Manage accounts". */
@Composable
private fun PublicationAccounts(accounts: List<Pair<String, String>>, selectedId: String?, enabled: Boolean, onChoose: (String) -> Unit,
    onManage: () -> Unit, manageEnabled: Boolean) {
    val c = M3.colors
    Column {
        M3SectionLabel(stringResource(R.string.add_publish_account), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp))
        if (accounts.isEmpty()) M3Note(stringResource(R.string.add_publish_no_account), Modifier.padding(horizontal = 16.dp), tone = M3NoteTone.Ahead)
        else M3ListCard(Modifier.padding(horizontal = 16.dp)) {
            accounts.forEachIndexed { index, (id, login) ->
                Row(Modifier.fillMaxWidth().m3TopDivider(index > 0, c.outlineVar)
                    .selectable(id == selectedId, enabled = enabled, role = Role.RadioButton) { onChoose(id) }
                    .heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    M3RadioIndicator(id == selectedId)
                    M3Avatar(addVaultInitials(null, login), size = 32.dp)
                    Text("@" + login, style = M3Type.code.copy(fontSize = M3Type.bodyMedium.fontSize, fontWeight = FontWeight.Medium), color = c.on)
                }
            }
        }
        M3Button(stringResource(R.string.add_publish_manage_accounts), onManage, Modifier.padding(start = 12.dp, top = 4.dp), style = M3ButtonStyle.Text, enabled = manageEnabled)
    }
}

@Composable
private fun PrivateSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = M3.colors
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 20.dp, vertical = 12.dp).testTag("publication-private"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.add_publish_private), style = M3Type.body, color = c.on)
            Text(stringResource(if (checked) R.string.add_publish_private_on else R.string.add_publish_private_off), style = M3Type.bodySmall, color = c.onVar)
        }
        M3Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The pinned bottom activity: progress, "Uploading the saved commit…" with Stop, and "1.4 MB / 2.3 MB · keep the app open". */
@Composable
private fun PublicationActivity(state: FolderPublicationViewModel.State, upload: com.bontecou.syncmd.services.git.TransferProgressUpdate?, totalBytes: Long?,
    cancel: () -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    val fraction = upload?.fraction?.takeIf { state.work == PublicationWork.UPLOAD }
    Column(modifier.fillMaxWidth().background(c.surface).m3TopDivider(true, c.outlineVar).navigationBarsPadding()
        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp)) {
        if (fraction != null) M3ProgressBar(fraction) else M3IndeterminateProgressBar()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                M3Spinner(color = c.onVar, size = 14.dp)
                Text(stringResource(if (state.stopping) R.string.publication_stopping else when (state.work) {
                    PublicationWork.UPLOAD -> R.string.add_publish_uploading
                    PublicationWork.CREATE -> R.string.add_publish_creating
                    PublicationWork.PREPARE -> R.string.add_publish_preparing
                    PublicationWork.REGISTER -> R.string.add_publish_registering
                    PublicationWork.INSPECT -> R.string.publication_work_inspect
                    PublicationWork.SAVE -> R.string.publication_work_save
                    PublicationWork.REFRESH -> R.string.publication_work_refresh
                    PublicationWork.RECONCILE -> R.string.publication_work_check
                    PublicationWork.ADOPT -> R.string.publication_work_adopt
                    PublicationWork.RECONNECT -> R.string.publication_work_reconnect
                    else -> R.string.publication_work_load
                }), style = M3Type.bodyMedium, color = c.on)
            }
            M3Button(stringResource(R.string.add_publish_stop), cancel, Modifier.testTag("publication-stop"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Medium,
                icon = R.drawable.ic_m3_stop, enabled = !state.stopping)
        }
        if (totalBytes != null && state.work in setOf(PublicationWork.UPLOAD, PublicationWork.CREATE)) Text(
            if (fraction != null) stringResource(R.string.add_publish_progress_bytes, addVaultSizePair((totalBytes * fraction).toLong(), totalBytes).substringBefore(" / "), addVaultSize(totalBytes))
            else stringResource(R.string.add_publish_progress_keep, addVaultSize(totalBytes)),
            style = M3Type.meta, color = c.onVar, modifier = Modifier.padding(top = 8.dp))
    }
}

private fun phaseLabel(phase: FolderPublicationPhase): Int = when (phase) {
    FolderPublicationPhase.REVIEW -> R.string.add_publish_phase_review
    FolderPublicationPhase.PREPARING -> R.string.publication_phase_preparing
    FolderPublicationPhase.PREPARED -> R.string.add_publish_phase_prepared
    FolderPublicationPhase.CREATING_REMOTE, FolderPublicationPhase.CREATION_UNKNOWN -> R.string.add_publish_phase_unknown
    FolderPublicationPhase.REMOTE_CREATED, FolderPublicationPhase.PUSHING, FolderPublicationPhase.PUSH_UNKNOWN -> R.string.add_publish_phase_created
    FolderPublicationPhase.PUBLISHED -> R.string.add_publish_phase_published
    FolderPublicationPhase.COMPLETED -> R.string.add_publish_phase_done
}
