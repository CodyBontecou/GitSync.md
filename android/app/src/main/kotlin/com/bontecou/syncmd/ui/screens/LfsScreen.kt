package com.bontecou.syncmd.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.VaultOpsBanner
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsDangerIcon
import com.bontecou.syncmd.ui.components.VaultOpsHeading
import com.bontecou.syncmd.ui.components.VaultOpsNote
import com.bontecou.syncmd.ui.components.VaultOpsOutlineButton
import com.bontecou.syncmd.ui.components.VaultOpsProgress
import com.bontecou.syncmd.ui.components.VaultOpsSelectMark
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.components.vaultOpsSlice
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.LfsViewModel
import com.bontecou.syncmd.services.git.lfs.LfsTrackingPlan

private data class ExactTrackingConfirmation(val plan: LfsTrackingPlan, val selected: Set<String>, val lfs: Boolean)

/** Per-vault Git LFS: tracking review, tracked objects, downloads and server locks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LfsScreen(path: String, onBack: () -> Unit, model: LfsViewModel = hiltViewModel()) {
    val state by model.state.collectAsState()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var choice by remember(path) { mutableStateOf<ExactTrackingConfirmation?>(null) }
    var lockChoice by remember(path) { mutableStateOf<LockConfirmation?>(null) }
    LaunchedEffect(path) { model.load(path) }
    LaunchedEffect(state.plan) { selected = state.plan?.candidates?.map { it.path }?.toSet().orEmpty() }
    BackHandler(state.busy && state.blockBack) { /* Complete a checked mutation before leaving. */ }
    val c = Tide.colors
    Scaffold(containerColor = c.paper, contentColor = c.ink, topBar = {
        WrappingTopBar(title = "Git LFS", backLabel = stringResource(R.string.publication_back), onBack = onBack,
            backEnabled = !state.busy || !state.blockBack)
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("lfs-screen"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp)) {
            item(key = "intro") { VaultOpsNote(stringResource(R.string.ui_git_lfs_keeps_large_file_contents_outside_4b4afd49), Modifier.padding(horizontal = 4.dp)) }
            if (state.busy) item(key = "loading") { VaultOpsProgress(modifier = Modifier.padding(top = 14.dp)) }
            state.notice?.let { notice -> item(key = "notice") { Box(Modifier.padding(top = 14.dp)) { LfsOperationNotice(notice) } } }
            if (state.refreshRequired) item(key = "refresh-required") {
                VaultOpsBanner(stringResource(R.string.lfs_action_refresh_required), Modifier.testTag("lfs-refresh-required"), Modifier.padding(top = 14.dp),
                    tone = com.bontecou.syncmd.ui.theme.TideBannerTone.AHEAD)
            }
            item(key = "reload") {
                VaultOpsOutlineButton(stringResource(R.string.ui_reload_lfs_state_046eb2f5), { model.load(path) },
                    Modifier.padding(top = 14.dp).testTag("lfs-reload"), enabled = !state.busy)
            }
            if (!state.refreshRequired) {
                val candidates = state.plan?.candidates.orEmpty()
                item(key = "tracking-title") { VaultOpsHeading(stringResource(R.string.ui_tracking_review_8d316853), Modifier.padding(top = 22.dp, bottom = 8.dp)) }
                if (candidates.isEmpty()) item(key = "tracking-empty") {
                    TideCard { VaultOpsNote(stringResource(R.string.ui_no_new_binary_or_large_files_need_a_tracki_8935ebaf)) }
                }
                itemsIndexed(candidates, key = { _, it -> "candidate:${it.path}" }) { index, candidate ->
                    val checked = candidate.path in selected
                    Row(Modifier.vaultOpsSlice(index, candidates.size).heightIn(min = 56.dp)
                        .toggleable(value = checked, enabled = !state.busy, role = Role.Checkbox,
                            onValueChange = { on -> selected = if (on) selected + candidate.path else selected - candidate.path })
                        .testTag("lfs-select-${candidate.path}").padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VaultOpsSelectMark(checked, enabled = !state.busy)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(candidate.path, style = TideType.mono(13.sp, FontWeight.Medium).copy(textDirection = TextDirection.Ltr), color = c.ink)
                            Text(Formatter.formatFileSize(LocalContext.current, candidate.size), style = TideType.meta, color = c.inkFaint)
                        }
                    }
                }
                if (candidates.isNotEmpty()) item(key = "tracking-actions") {
                    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VaultOpsButton(stringResource(R.string.ui_track_selected_files_with_lfs_77617dd9),
                            { state.plan?.let { choice = ExactTrackingConfirmation(it, selected.toSet(), true) } },
                            Modifier.testTag("lfs-track"), enabled = !state.busy && selected.isNotEmpty())
                        LfsExtensionReviewButton(selected, state.busy, model::reviewExtensions)
                        VaultOpsOutlineButton(stringResource(R.string.ui_keep_selected_files_in_regular_git_7de351fc),
                            { state.plan?.let { choice = ExactTrackingConfirmation(it, selected.toSet(), false) } },
                            Modifier.testTag("lfs-regular-git"), enabled = !state.busy && selected.isNotEmpty())
                        Text(stringResource(R.string.ui_this_changes_gitattributes_files_and_the_s_686aaa3b), style = TideType.meta, color = c.inkFaint,
                            modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
                item(key = "objects-title") { VaultOpsHeading(stringResource(R.string.ui_tracked_objects_a265fdb0), Modifier.padding(top = 22.dp, bottom = 8.dp)) }
                if (state.files.isEmpty()) item(key = "objects-empty") {
                    TideCard { VaultOpsNote(stringResource(R.string.ui_no_lfs_pointers_in_the_current_index_612c2995)) }
                }
                itemsIndexed(state.files, key = { _, it -> "object:${it.path}" }) { index, file ->
                    SelectionContainer {
                        Column(Modifier.vaultOpsSlice(index, state.files.size).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LfsFileSummary(file)
                        }
                    }
                }
                item(key = "download") {
                    VaultOpsButton(stringResource(R.string.ui_download_missing_file_contents_f54939ee), model::hydrate,
                        Modifier.padding(top = 14.dp).testTag("lfs-download"), enabled = !state.busy && state.files.any { !it.hydrated && !it.modified })
                }
                lfsLockControls(state, model) { lockChoice = it }
            }
        }
    }
    state.extensionReview?.let { review -> LfsExtensionConfirmation(review, state.busy,
        onCancel = model::dismissExtensionReview, onConfirm = { model.applyExtensions(review) }) }
    lockChoice?.let { pending -> LfsLockConfirmation(pending, state.locks == pending.review, state.busy,
        onCancel = { lockChoice = null }, onConfirm = {
            lockChoice = null
            if (pending.lock == null) model.createLock(pending.review, pending.path)
            else model.releaseLock(pending.review, pending.lock, pending.force)
        }) }
    state.error?.let { error ->
        VaultOpsSheet(onDismissRequest = model::clearError, title = stringResource(R.string.ui_git_lfs_needs_attention_01f551e1),
            icon = { VaultOpsDangerIcon() },
            confirmButton = { VaultOpsButton(stringResource(R.string.ui_ok_565339bc), model::clearError) }) {
            Text(error.asString(), modifier = Modifier.testTag("lfs-error"))
        }
    }
    choice?.let { pending -> LfsTrackingConfirmation(pending.selected, pending.lfs,
        canApply = !state.busy && state.plan == pending.plan,
        onCancel = { choice = null },
        onConfirm = { choice = null; model.decide(pending.selected, pending.lfs, pending.plan) }) }
}
