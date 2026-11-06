package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.lfs.*
import com.bontecou.syncmd.ui.components.VaultOpsBanner
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsButtonStyle
import com.bontecou.syncmd.ui.components.VaultOpsDangerIcon
import com.bontecou.syncmd.ui.components.VaultOpsDetail
import com.bontecou.syncmd.ui.components.VaultOpsHeading
import com.bontecou.syncmd.ui.components.VaultOpsNote
import com.bontecou.syncmd.ui.components.VaultOpsOutlineButton
import com.bontecou.syncmd.ui.components.VaultOpsRow
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.components.VaultOpsToggleRow
import com.bontecou.syncmd.ui.components.vaultOpsSlice
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.LfsViewModel

internal data class LockConfirmation(val review: LfsLockReview, val path: String, val lock: LfsLock? = null, val force: Boolean = false)

internal fun LazyListScope.lfsLockControls(state: LfsViewModel.State, model: LfsViewModel, confirm: (LockConfirmation) -> Unit) =
    lfsLockControls(state, onCheck = { verify -> model.checkLocks(verify) }, confirm = confirm)

/** Rendering and callbacks stay independent of repository/server execution. */
internal fun LazyListScope.lfsLockControls(state: LfsViewModel.State, onCheck: (Boolean) -> Unit, confirm: (LockConfirmation) -> Unit) {
    item(key = "locks-title") { VaultOpsHeading(stringResource(R.string.lfs_locks_title), Modifier.padding(top = 20.dp).testTag("lfs-locks-title")) }
    item(key = "locks-check") {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VaultOpsNote(stringResource(R.string.lfs_locks_intro), Modifier.padding(horizontal = 4.dp).testTag("lfs-locks-intro"))
            VaultOpsButton(stringResource(R.string.lfs_locks_check), { onCheck(true) }, Modifier.testTag("lfs-locks-check"), enabled = !state.busy)
            VaultOpsOutlineButton(stringResource(R.string.lfs_locks_list), { onCheck(false) }, Modifier.testTag("lfs-locks-list"), enabled = !state.busy)
        }
    }
    val review = state.locks ?: return
    item(key = "locks-scope") {
        val c = Tide.colors
        TideCard(Modifier.padding(top = 14.dp), padding = 16.dp) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(review.context.path, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.ink, modifier = Modifier.testTag("lfs-locks-path"))
                    Text(review.context.branch, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkMid, modifier = Modifier.testTag("lfs-locks-branch"))
                    Text(stringResource(R.string.lfs_locks_remote, review.context.remote), style = TideType.mono(12.sp), color = c.inkMid, modifier = Modifier.testTag("lfs-locks-remote"))
                    Text(stringResource(R.string.lfs_locks_server, review.endpoint), style = TideType.mono(12.sp), color = c.inkMid, modifier = Modifier.testTag("lfs-locks-server"))
                }
            }
            Spacer(Modifier.height(10.dp))
            val (fg, bg) = when {
                !review.supported -> c.inkMid to c.well
                review.verified -> c.synced to c.synced.copy(alpha = 0.12f)
                else -> c.behind to c.behind.copy(alpha = 0.12f)
            }
            TidePill(stringResource(when {
                !review.supported -> R.string.lfs_lock_unverified
                review.verified -> R.string.lfs_locks_verified
                else -> R.string.lfs_locks_read_only
            }), fg, bg, Modifier.testTag("lfs-locks-scope"))
        }
    }
    if (!review.supported) {
        item(key = "locks-unsupported") {
            VaultOpsBanner(stringResource(R.string.lfs_locks_unsupported), Modifier.testTag("lfs-locks-unsupported"), Modifier.padding(top = 12.dp))
        }
        return
    }
    val locks = if (review.verified) review.ours + review.theirs else review.listed
    if (locks.isEmpty()) item(key = "locks-empty") {
        TideCard(Modifier.padding(top = 12.dp)) { VaultOpsNote(stringResource(R.string.lfs_locks_empty), Modifier.testTag("lfs-locks-empty")) }
    }
    if (locks.isNotEmpty()) item(key = "locks-gap") { Spacer(Modifier.height(12.dp)) }
    itemsIndexed(locks, key = { _, lock -> "server-lock:${lock.id}" }) { index, lock ->
        val owned = lock in review.ours
        LockItem(lock, owned, review.verified, state.busy, Modifier.vaultOpsSlice(index, locks.size).testTag("lfs-server-lock-${lock.id}")) {
            confirm(LockConfirmation(review, lock.path, lock, !owned))
        }
    }
    if (review.verified) {
        val available = review.candidates.filter { path -> locks.none { it.path == path } }
        item(key = "locks-candidates-title") {
            Text(stringResource(R.string.lfs_lock_create_heading), style = TideType.sans(15.sp, FontWeight.SemiBold), color = Tide.colors.ink,
                modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp).testTag("lfs-locks-create-heading"))
        }
        if (available.isEmpty()) item(key = "locks-candidates-empty") {
            TideCard { VaultOpsNote(stringResource(R.string.lfs_lock_candidates_empty), Modifier.testTag("lfs-locks-candidates-empty")) }
        }
        itemsIndexed(available, key = { _, path -> "lock-candidate:$path" }) { index, path ->
            Box(Modifier.vaultOpsSlice(index, available.size)) {
                VaultOpsRow(stringResource(R.string.lfs_lock_create_review, path), { confirm(LockConfirmation(review, path)) },
                    Modifier.testTag("lfs-lock-create-$path"), enabled = !state.busy)
            }
        }
    }
}

/** One server lock: mono path, owner, ownership pill and the reviewed action. */
@Composable
private fun LockItem(lock: LfsLock, owned: Boolean, verified: Boolean, busy: Boolean, modifier: Modifier, onReview: () -> Unit) {
    val c = Tide.colors
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer {
            Text(lock.path, style = TideType.mono(13.sp, FontWeight.Medium).copy(textDirection = TextDirection.Ltr), color = c.ink,
                modifier = Modifier.testTag("lfs-lock-path-${lock.id}"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val (fg, bg) = when {
                !verified -> c.inkMid to c.well
                owned -> c.synced to c.synced.copy(alpha = 0.12f)
                else -> c.behind to c.behind.copy(alpha = 0.12f)
            }
            TidePill(stringResource(if (!verified) R.string.lfs_lock_unverified else if (owned) R.string.lfs_lock_ours else R.string.lfs_lock_theirs),
                fg, bg, Modifier.weight(1f, fill = false).testTag("lfs-lock-ownership-${lock.id}"))
        }
        Text(stringResource(R.string.ui_owner_1_s_599ca9ce, lock.owner ?: stringResource(R.string.lfs_lock_owner_missing)), style = TideType.sans(14.sp), color = c.inkMid)
        lock.lockedAt?.let { Text(it, style = TideType.meta, color = c.inkFaint) }
        if (verified) VaultOpsOutlineButton(stringResource(if (owned) R.string.lfs_lock_release_review else R.string.lfs_lock_force_review), onReview,
            Modifier.testTag(if (owned) "lfs-lock-release-${lock.id}" else "lfs-lock-force-${lock.id}"), enabled = !busy, danger = !owned)
    }
}

@Composable
internal fun LfsLockConfirmation(choice: LockConfirmation, valid: Boolean, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    var acknowledged by remember(choice) { mutableStateOf(false) }
    val c = Tide.colors
    VaultOpsSheet(
        onDismissRequest = onCancel,
        title = stringResource(when {
            choice.force -> R.string.lfs_lock_force_title
            choice.lock != null -> R.string.lfs_lock_release_title
            else -> R.string.lfs_lock_create_title
        }),
        titleModifier = Modifier.testTag("lfs-lock-title"),
        icon = if (choice.force) ({ VaultOpsDangerIcon() }) else ({ TideModalIcon(glyph = if (choice.lock != null) "↑" else "⌁", tint = c.ink) }),
        confirmButton = {
            VaultOpsButton(stringResource(when {
                choice.force -> R.string.lfs_lock_force_action
                choice.lock != null -> R.string.lfs_lock_release_action
                else -> R.string.lfs_lock_create_action
            }), onConfirm, Modifier.testTag("lfs-lock-confirm"), enabled = valid && !busy && (!choice.force || acknowledged),
                style = if (choice.force) VaultOpsButtonStyle.DANGER else VaultOpsButtonStyle.PRIMARY)
        },
        dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), onCancel, Modifier.fillMaxWidth().testTag("lfs-lock-cancel")) },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).testTag("lfs-lock-review"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VaultOpsDetail(choice.path, textModifier = Modifier.testTag("lfs-lock-path"))
            Text(choice.review.context.branch, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkMid,
                modifier = Modifier.testTag("lfs-lock-branch"))
            SelectionContainer {
                Text(choice.review.endpoint, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkMid,
                    modifier = Modifier.testTag("lfs-lock-endpoint"))
            }
            choice.lock?.let { Text(stringResource(R.string.ui_owner_1_s_599ca9ce, it.owner ?: stringResource(R.string.lfs_lock_owner_missing)), modifier = Modifier.testTag("lfs-lock-owner")) }
            if (choice.force) {
                VaultOpsBanner(stringResource(R.string.ui_this_removes_another_connection_s_lock_coo_880defbf), Modifier.testTag("lfs-lock-warning"), danger = true)
                VaultOpsToggleRow(acknowledged, { acknowledged = it }, stringResource(R.string.ui_i_understand_that_another_owner_may_still_b4a12b2d),
                    Modifier.testTag("lfs-lock-force-ack"), labelModifier = Modifier.testTag("lfs-lock-ack-label"))
            }
            if (!valid) VaultOpsBanner(stringResource(R.string.ui_the_review_changed_cancel_and_refresh_serv_c7ee804c), Modifier.testTag("lfs-lock-stale"), danger = true)
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewContext = LfsLockContext("/storage/emulated/0/Documents/VoxVault", "main", "e19c4a0", "origin", "r1")
private val previewLocks = listOf(LfsLock("1", "assets/diagram.psd", "cody", "2026-11-01T14:02:00Z"), LfsLock("2", "audio/interview.wav", "sam", null))
private val previewReview = LfsLockReview(previewContext, "https://lfs.example.com/team/vox", previewLocks.take(1), previewLocks.drop(1),
    emptyList(), verified = true, supported = true, candidates = listOf("video/cut.mov"))

@Composable
private fun LockListPreviewContent(review: LfsLockReview?) {
    LazyColumn(Modifier.background(Tide.colors.paper).padding(horizontal = 16.dp)) {
        lfsLockControls(LfsViewModel.State(locks = review), onCheck = {}, confirm = {})
    }
}

@Preview(name = "LFS locks · verified · light", heightDp = 900)
@Composable
private fun LockListVerifiedLight() = SyncMdTheme(darkTheme = false) { LockListPreviewContent(previewReview) }

@Preview(name = "LFS locks · verified · dark", heightDp = 900)
@Composable
private fun LockListVerifiedDark() = SyncMdTheme(darkTheme = true) { LockListPreviewContent(previewReview) }

@Preview(name = "LFS locks · unsupported · light", heightDp = 600)
@Composable
private fun LockListUnsupported() = SyncMdTheme(darkTheme = false) {
    LockListPreviewContent(previewReview.copy(supported = false, verified = false, ours = emptyList(), theirs = emptyList()))
}

@Preview(name = "LFS locks · empty read-only · dark", heightDp = 600)
@Composable
private fun LockListEmpty() = SyncMdTheme(darkTheme = true) {
    LockListPreviewContent(previewReview.copy(verified = false, ours = emptyList(), theirs = emptyList(), listed = emptyList()))
}

@Preview(name = "Force unlock · light")
@Composable
private fun ForceUnlockPreview() = SyncMdTheme(darkTheme = false) {
    LfsLockConfirmation(LockConfirmation(previewReview, previewLocks[1].path, previewLocks[1], force = true), valid = true, busy = false, onCancel = {}, onConfirm = {})
}

@Preview(name = "Create lock · stale · dark")
@Composable
private fun CreateLockPreview() = SyncMdTheme(darkTheme = true) {
    LfsLockConfirmation(LockConfirmation(previewReview, "video/cut.mov"), valid = false, busy = false, onCancel = {}, onConfirm = {})
}
