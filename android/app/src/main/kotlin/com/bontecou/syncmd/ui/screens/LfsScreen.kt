package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.lfs.LfsDownloadProgress
import com.bontecou.syncmd.services.git.lfs.LfsTracking
import com.bontecou.syncmd.services.git.lfs.LfsTrackingPlan
import com.bontecou.syncmd.ui.components.LfsCheckbox
import com.bontecou.syncmd.ui.components.LfsMono
import com.bontecou.syncmd.ui.components.LfsSizes
import com.bontecou.syncmd.ui.components.LfsTabs
import com.bontecou.syncmd.ui.theme.*
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.LfsNotice
import com.bontecou.syncmd.ui.viewmodels.LfsViewModel
import java.io.File

private data class ExactTrackingConfirmation(val plan: LfsTrackingPlan, val selected: Set<String>, val lfs: Boolean)

/**
 * Board "Large files": a Files | Locks switch over the vault's Git LFS state. Files reviews new
 * large files (track with LFS, match extensions, keep in Git), lists tracked objects and downloads
 * missing contents with live progress; Locks checks ownership and creates or releases server locks.
 */
@Composable
fun LfsScreen(path: String, onBack: () -> Unit, model: LfsViewModel = hiltViewModel()) {
    val state by model.state.collectAsState()
    val download by model.download.collectAsState()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var choice by remember(path) { mutableStateOf<ExactTrackingConfirmation?>(null) }
    var lockChoice by remember(path) { mutableStateOf<LockConfirmation?>(null) }
    var tab by rememberSaveable(path) { mutableIntStateOf(0) }
    var hydrating by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) { model.load(path) }
    LaunchedEffect(state.plan) { selected = state.plan?.candidates?.map { it.path }?.toSet().orEmpty() }
    LaunchedEffect(state.busy) { if (!state.busy) hydrating = false }
    BackHandler(state.busy && state.blockBack) { /* Complete a checked mutation before leaving. */ }
    val c = M3.colors
    val branch = (state.plan?.branch ?: state.locks?.context?.branch)?.removePrefix("refs/heads/")
    val name = File(path).name
    val locks = state.locks?.let { if (it.verified) it.ours + it.theirs else it.listed }
    val lockedPaths = locks?.map { it.path }?.toSet().orEmpty()
    Column(Modifier.fillMaxSize().background(c.surface).testTag("lfs-screen")) {
        M3TopBar(stringResource(R.string.lfs_m3_title),
            subtitle = if (branch != null) stringResource(R.string.lfs_m3_subtitle, name, branch) else "$name · Git LFS",
            onBack = if (!state.busy || !state.blockBack) onBack else null) {
            M3IconButton(R.drawable.ic_m3_sync, stringResource(R.string.lfs_m3_reload), { model.load(path) },
                Modifier.testTag("lfs-reload"), enabled = !state.busy)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "tabs") {
                LfsTabs(listOf(stringResource(R.string.lfs_m3_tab_files) to state.files.size.toString(),
                    stringResource(R.string.lfs_m3_tab_locks) to locks?.size?.toString()), tab, { tab = it },
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("lfs-tabs"))
            }
            if (state.busy && !hydrating) item(key = "loading") { M3IndeterminateProgressBar(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
            if (tab == 0) filesTab(state, download, hydrating, selected, lockedPaths, locks?.size,
                onToggle = { file, on -> selected = if (on) selected + file else selected - file },
                onTrack = { lfs -> state.plan?.let { choice = ExactTrackingConfirmation(it, selected.toSet(), lfs) } },
                onExtensions = { model.reviewExtensions(selected.toSet()) },
                onReload = { model.load(path) },
                onDownload = { hydrating = true; model.hydrate() })
            else lfsLockControls(state, onCheck = { verify -> model.checkLocks(verify) }, confirm = { lockChoice = it })
            item(key = "bottom") { Spacer(Modifier.navigationBarsPadding()) }
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
        M3Dialog(onDismissRequest = model::clearError, title = stringResource(R.string.lfs_m3_attention), icon = R.drawable.ic_m3_warning, iconTint = c.error,
            content = { Text(error.asString(), modifier = Modifier.testTag("lfs-error")) }) {
            M3DialogAction(stringResource(R.string.lfs_m3_ok), model::clearError, Modifier.testTag("lfs-error-ok"))
        }
    }
    choice?.let { pending -> LfsTrackingConfirmation(pending.selected, pending.lfs,
        canApply = !state.busy && state.plan == pending.plan,
        onCancel = { choice = null },
        onConfirm = { choice = null; model.decide(pending.selected, pending.lfs, pending.plan) }) }
}

private fun LazyListScope.filesTab(
    state: LfsViewModel.State, download: LfsDownloadProgress?, hydrating: Boolean, selected: Set<String>, locked: Set<String>, lockCount: Int?,
    onToggle: (String, Boolean) -> Unit, onTrack: (Boolean) -> Unit, onExtensions: () -> Unit, onReload: () -> Unit, onDownload: () -> Unit,
) {
    item(key = "hero") { StoredHero(state.files.sumOf { it.pointer.size }, state.files.size, lockCount) }
    if (hydrating || download != null) item(key = "downloading") { DownloadCard(download) }
    state.notice?.takeIf { it !is LfsNotice.LockListRefreshed && it !is LfsNotice.Plain }?.let { notice ->
        item(key = "notice") { Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) { LfsOperationNotice(notice) } }
    }
    if (state.refreshRequired) {
        item(key = "refresh-required") { RefreshRequired(onReload) }
        return
    }
    val candidates = state.plan?.candidates.orEmpty()
    if (candidates.isNotEmpty()) {
        item(key = "tracking-title") { M3SectionLabel(stringResource(R.string.lfs_m3_needs_choice)) }
        itemsIndexed(candidates, key = { _, it -> "candidate:${it.path}" }) { index, candidate ->
            val c = M3.colors
            val checked = candidate.path in selected
            Row(Modifier.padding(horizontal = 16.dp).padding(top = if (index == 0) 8.dp else 0.dp)
                .m3GroupedItem(index, candidates.size, c.cLow).m3TopDivider(index > 0, c.outlineVar)
                .toggleable(value = checked, enabled = !state.busy, role = Role.Checkbox) { onToggle(candidate.path, it) }
                .testTag("lfs-select-${candidate.path}").heightIn(min = 60.dp).padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LfsCheckbox(checked, enabled = !state.busy)
                Column(Modifier.weight(1f)) {
                    Text(candidate.path, style = LfsMono.copy(fontWeight = FontWeight.Medium), color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (candidate.path in state.plan?.untracked.orEmpty()) R.string.lfs_m3_candidate_new else R.string.lfs_m3_candidate_changed,
                        LfsSizes.format(candidate.size)), style = M3Type.caption, color = c.onVar)
                }
            }
        }
        item(key = "tracking-actions") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                M3FilledButton(pluralStringResource(R.plurals.lfs_m3_track_files, selected.size, selected.size), { onTrack(true) },
                    Modifier.fillMaxWidth().testTag("lfs-track"), size = M3ButtonSize.Medium, icon = R.drawable.ic_m3_storage,
                    enabled = !state.busy && selected.isNotEmpty())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3OutlinedButton(stringResource(R.string.lfs_m3_match_extensions), onExtensions, Modifier.weight(1f).testTag("lfs-review-extensions"),
                        size = M3ButtonSize.Medium, enabled = !state.busy && selected.any { LfsTracking.extensionPatterns(it).isNotEmpty() })
                    M3OutlinedButton(stringResource(R.string.lfs_m3_keep_git), { onTrack(false) }, Modifier.weight(1f).testTag("lfs-regular-git"),
                        size = M3ButtonSize.Medium, enabled = !state.busy && selected.isNotEmpty())
                }
            }
            Text(stringResource(R.string.lfs_m3_tracking_caption), style = M3Type.caption, color = M3.colors.onVar,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        }
    }
    item(key = "objects-title") { M3SectionLabel(stringResource(R.string.lfs_m3_tracked_files)) }
    if (state.files.isEmpty()) item(key = "objects-empty") {
        M3Note(stringResource(R.string.lfs_m3_no_tracked), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), tone = M3NoteTone.Muted)
    }
    itemsIndexed(state.files, key = { _, it -> "object:${it.path}" }) { index, file ->
        val c = M3.colors
        Row(Modifier.padding(horizontal = 16.dp).padding(top = if (index == 0) 8.dp else 0.dp)
            .m3GroupedItem(index, state.files.size, c.cLow).m3TopDivider(index > 0, c.outlineVar)
            .heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 10.dp).testTag("lfs-object-${file.path}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LfsFileSummary(file, locked = file.path in locked)
        }
    }
    val missing = state.files.filter { !it.hydrated && !it.modified }
    if (state.files.isNotEmpty()) item(key = "download") {
        M3Button(stringResource(R.string.lfs_m3_download_missing), onDownload,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().testTag("lfs-download"), style = M3ButtonStyle.Tonal,
            icon = R.drawable.ic_m3_arrow_downward, trailingMeta = missing.takeIf { it.isNotEmpty() }?.let { m -> LfsSizes.format(m.sumOf { it.pointer.size }) },
            enabled = !state.busy && missing.isNotEmpty())
    }
}

/** Inverse hero: "Stored in LFS", the total size, "in N files · M locked", the pointer explainer. */
@Composable
private fun StoredHero(bytes: Long, files: Int, locked: Int?) {
    M3Hero(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("lfs-hero"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        M3HeroKicker(stringResource(R.string.lfs_m3_stored_kicker))
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(LfsSizes.format(bytes), style = M3Type.display.copy(fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-1).sp),
                modifier = Modifier.alignByBaseline())
            val count = pluralStringResource(R.plurals.lfs_m3_stored_files, files, files)
            Text(if (locked != null && locked > 0) stringResource(R.string.lfs_m3_stored_locked, count, locked) else count,
                style = M3Type.body, modifier = Modifier.alignByBaseline())
        }
        Text(stringResource(R.string.lfs_m3_stored_body), style = M3Type.bodyMedium, color = M3.colors.invMeta, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Primary-container progress card while "Download missing contents" runs. */
@Composable
private fun DownloadCard(progress: LfsDownloadProgress?) {
    val c = M3.colors
    val description = stringResource(R.string.lfs_m3_download_progress)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().background(c.pc, RoundedCornerShape(20.dp))
        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("lfs-downloading")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (progress != null) pluralStringResource(R.plurals.lfs_m3_downloading, progress.files, progress.files) else stringResource(R.string.lfs_m3_downloading_prepare),
                style = M3Type.section, color = c.onPc, modifier = Modifier.weight(1f))
            if (progress != null) Text(LfsSizes.progress(progress.done, progress.total), style = M3Type.code.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = c.onPc)
        }
        val fraction = progress?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total }
        if (fraction != null) M3ProgressBar(fraction, Modifier.padding(top = 10.dp), tone = M3ProgressTone.PrimaryContainer, description = description)
        else M3IndeterminateProgressBar(Modifier.padding(top = 10.dp), tone = M3ProgressTone.PrimaryContainer, description = description)
    }
}

/** Tertiary-container alert after a confirmed write whose follow-up read failed. */
@Composable
private fun RefreshRequired(onReload: () -> Unit) {
    val c = M3.colors
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().background(c.tc, RoundedCornerShape(16.dp)).padding(16.dp)
        .testTag("lfs-refresh-required")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            M3Icon(R.drawable.ic_m3_warning, tint = c.onTc, size = 20.dp)
            Text(stringResource(R.string.lfs_m3_refresh_required), style = M3Type.bodyMedium, color = c.onTc, modifier = Modifier.weight(1f))
        }
        M3FilledButton(stringResource(R.string.lfs_m3_reload), onReload, Modifier.padding(top = 12.dp).testTag("lfs-refresh-reload"), size = M3ButtonSize.Medium)
    }
}
