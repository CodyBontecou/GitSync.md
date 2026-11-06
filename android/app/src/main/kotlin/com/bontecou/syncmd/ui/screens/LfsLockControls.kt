package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.lfs.*
import com.bontecou.syncmd.ui.components.LfsCheckbox
import com.bontecou.syncmd.ui.components.LfsIconTile
import com.bontecou.syncmd.ui.components.LfsMono
import com.bontecou.syncmd.ui.components.LfsPill
import com.bontecou.syncmd.ui.theme.*
import com.bontecou.syncmd.ui.viewmodels.LfsNotice
import com.bontecou.syncmd.ui.viewmodels.LfsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class LockConfirmation(val review: LfsLockReview, val path: String, val lock: LfsLock? = null, val force: Boolean = false)

internal fun LazyListScope.lfsLockControls(state: LfsViewModel.State, model: LfsViewModel, confirm: (LockConfirmation) -> Unit) =
    lfsLockControls(state, onCheck = { verify -> model.checkLocks(verify) }, confirm = confirm)

private val lockTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
/** Server lock times are ISO-8601; show them as "2026-11-01 14:02" in local time, or as given. */
internal fun lfsLockTime(raw: String): String = runCatching { lockTime.format(Instant.parse(raw).atZone(ZoneId.systemDefault())) }.getOrDefault(raw)

/**
 * Board "Large files" › Locks: check ownership (verified) or list read-only, the reviewed scope
 * card, the server's locks with Release…/Force release… and the lockable files you can lock.
 * Rendering and callbacks stay independent of repository/server execution.
 */
internal fun LazyListScope.lfsLockControls(state: LfsViewModel.State, onCheck: (Boolean) -> Unit, confirm: (LockConfirmation) -> Unit) {
    item(key = "locks-intro") {
        Text(stringResource(R.string.lfs_m3_locks_intro), style = M3Type.bodyMedium, color = M3.colors.onVar,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).testTag("lfs-locks-intro"))
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3FilledButton(stringResource(R.string.lfs_m3_check_ownership), { onCheck(true) }, Modifier.weight(1f).testTag("lfs-locks-check"),
                size = M3ButtonSize.Medium, icon = R.drawable.ic_m3_shield, enabled = !state.busy)
            M3OutlinedButton(stringResource(R.string.lfs_m3_list_read_only), { onCheck(false) }, Modifier.weight(1f).testTag("lfs-locks-list"),
                size = M3ButtonSize.Medium, enabled = !state.busy)
        }
    }
    state.notice?.takeIf { it is LfsNotice.Plain }?.let { notice ->
        item(key = "locks-notice") { Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) { LfsOperationNotice(notice) } }
    }
    val review = state.locks ?: return
    item(key = "locks-scope") { LockScope(review) }
    if (!review.supported) {
        item(key = "locks-unsupported") {
            M3Note(stringResource(R.string.lfs_m3_unsupported), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("lfs-locks-unsupported"),
                tone = M3NoteTone.Muted)
        }
        return
    }
    val locks = if (review.verified) review.ours + review.theirs else review.listed
    item(key = "locks-title") { M3SectionLabel(stringResource(R.string.lfs_m3_locked_on_server), Modifier.testTag("lfs-locks-title")) }
    if (locks.isEmpty()) item(key = "locks-empty") {
        M3Note(stringResource(R.string.lfs_m3_locks_empty), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("lfs-locks-empty"), tone = M3NoteTone.Muted)
    }
    itemsIndexed(locks, key = { _, lock -> "server-lock:${lock.id}" }) { index, lock ->
        val owned = lock in review.ours
        LockItem(lock, owned, review.verified, state.busy,
            Modifier.padding(horizontal = 16.dp).padding(top = if (index == 0) 8.dp else 0.dp)
                .m3GroupedItem(index, locks.size, M3.colors.cLow).m3TopDivider(index > 0, M3.colors.outlineVar)
                .testTag("lfs-server-lock-${lock.id}")) {
            confirm(LockConfirmation(review, lock.path, lock, !owned))
        }
    }
    if (review.verified) {
        val available = review.candidates.filter { path -> locks.none { it.path == path } }
        item(key = "locks-candidates-title") { M3SectionLabel(stringResource(R.string.lfs_m3_lock_a_file), Modifier.testTag("lfs-locks-create-heading")) }
        if (available.isEmpty()) item(key = "locks-candidates-empty") {
            M3Note(stringResource(R.string.lfs_m3_no_lockable), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("lfs-locks-candidates-empty"),
                tone = M3NoteTone.Muted)
        }
        itemsIndexed(available, key = { _, path -> "lock-candidate:$path" }) { index, path ->
            val c = M3.colors
            M3ListRow(stringResource(R.string.lfs_lock_create_review, path),
                Modifier.padding(horizontal = 16.dp).padding(top = if (index == 0) 8.dp else 0.dp).m3GroupedItem(index, available.size, c.cLow)
                    .testTag("lfs-lock-create-$path"),
                leading = { M3Icon(R.drawable.ic_m3_lock_open, tint = c.onVar) }, trailing = { M3Chevron() },
                onClick = { confirm(LockConfirmation(review, path)) }, divider = index > 0, enabled = !state.busy,
                titleStyle = LfsMono, titleMaxLines = 1, contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
        }
        item(key = "locks-candidates-caption") {
            Text(stringResource(R.string.lfs_m3_lockable_caption), style = M3Type.caption, color = M3.colors.onVar,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
        }
    }
}

/** The reviewed scope: folder, branch and remote, LFS server, and the ownership pill. */
@Composable
private fun LockScope(review: LfsLockReview) {
    val c = M3.colors
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().background(c.cLow, M3.cardShape).padding(horizontal = 20.dp, vertical = 16.dp)) {
        val mono = M3Type.meta.copy(lineHeight = 18.sp)
        SelectionContainer {
            Column {
                Text(review.context.path, style = mono, color = c.on, modifier = Modifier.testTag("lfs-locks-path"))
                Text(stringResource(R.string.lfs_m3_branch_remote, review.context.branch.removePrefix("refs/heads/"), review.context.remote), style = mono, color = c.onVar,
                    modifier = Modifier.testTag("lfs-locks-branch"))
                Text(stringResource(R.string.lfs_m3_server, lfsDisplayEndpoint(review.endpoint)), style = mono, color = c.onVar, modifier = Modifier.testTag("lfs-locks-server"))
            }
        }
        when {
            !review.supported -> LfsPill(stringResource(R.string.lfs_lock_unverified), M3Tone.Muted, Modifier.padding(top = 10.dp).testTag("lfs-locks-scope"), R.drawable.ic_m3_info)
            review.verified -> LfsPill(stringResource(R.string.lfs_m3_scope_verified), M3Tone.Ok, Modifier.padding(top = 10.dp).testTag("lfs-locks-scope"), R.drawable.ic_m3_shield)
            else -> LfsPill(stringResource(R.string.lfs_m3_scope_read_only), M3Tone.Behind, Modifier.padding(top = 10.dp).testTag("lfs-locks-scope"), R.drawable.ic_m3_info)
        }
    }
}

/** One server lock: lock glyph and mono path, ownership pill, owner and time, then the reviewed action. */
@Composable
private fun LockItem(lock: LfsLock, owned: Boolean, verified: Boolean, busy: Boolean, modifier: Modifier, onReview: () -> Unit) {
    val c = M3.colors
    Column(modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3Icon(R.drawable.ic_m3_lock, tint = c.onVar, size = 18.dp)
            Text(lock.path, style = LfsMono.copy(fontWeight = FontWeight.Medium), color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("lfs-lock-path-${lock.id}"))
        }
        LfsPill(stringResource(if (!verified) R.string.lfs_lock_unverified else if (owned) R.string.lfs_m3_owned_here else R.string.lfs_lock_theirs),
            if (!verified) M3Tone.Muted else if (owned) M3Tone.Ok else M3Tone.Behind, Modifier.padding(top = 8.dp).testTag("lfs-lock-ownership-${lock.id}"))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lfs_m3_owner, lock.owner ?: stringResource(R.string.lfs_lock_owner_missing)), style = M3Type.bodyMedium, color = c.on)
                lock.lockedAt?.let { Text(lfsLockTime(it), style = M3Type.meta, color = c.onVar) }
            }
            if (verified) M3OutlinedButton(stringResource(if (owned) R.string.lfs_m3_release_review else R.string.lfs_lock_force_review), onReview,
                Modifier.testTag(if (owned) "lfs-lock-release-${lock.id}" else "lfs-lock-force-${lock.id}"), size = M3ButtonSize.Medium,
                enabled = !busy, destructive = !owned)
        }
    }
}

/**
 * Lock sheet (create, release, force release): icon tile, title, the exact path/branch/server, and
 * for force release a warning plus an acknowledgement that must be checked first.
 */
@Composable
internal fun LfsLockConfirmation(choice: LockConfirmation, valid: Boolean, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    var acknowledged by remember(choice) { mutableStateOf(false) }
    val c = M3.colors
    M3ModalSheet(onDismissRequest = onCancel, footer = {
        M3FilledButton(stringResource(when {
            choice.force -> R.string.lfs_lock_force_action
            choice.lock != null -> R.string.lfs_lock_release_action
            else -> R.string.lfs_lock_create_action
        }), onConfirm, Modifier.fillMaxWidth().testTag("lfs-lock-confirm"), size = M3ButtonSize.Hero,
            enabled = valid && !busy && (!choice.force || acknowledged), destructive = choice.force)
        M3TextButton(stringResource(R.string.lfs_m3_cancel), onCancel, Modifier.fillMaxWidth().testTag("lfs-lock-cancel"))
    }) {
        Column(Modifier.fillMaxWidth().testTag("lfs-lock-review")) {
            if (choice.force) LfsIconTile(R.drawable.ic_m3_warning, c.ec, c.onEc)
            else LfsIconTile(if (choice.lock != null) R.drawable.ic_m3_lock_open else R.drawable.ic_m3_lock, c.sc, c.onSc)
            Text(stringResource(when {
                choice.force -> R.string.lfs_m3_force_title
                choice.lock != null -> R.string.lfs_lock_release_title
                else -> R.string.lfs_lock_create_title
            }), style = M3Type.sheetTitle, color = c.on, modifier = Modifier.padding(top = 16.dp).testTag("lfs-lock-title"))
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                SelectionContainer {
                    Column {
                        Text(choice.path, style = LfsMono.copy(fontWeight = FontWeight.SemiBold), color = c.on, modifier = Modifier.testTag("lfs-lock-path"))
                        Text(choice.review.context.branch.removePrefix("refs/heads/"), style = M3Type.meta, color = c.onVar,
                            modifier = Modifier.padding(top = 4.dp).testTag("lfs-lock-branch"))
                        Text(lfsDisplayEndpoint(choice.review.endpoint), style = M3Type.meta, color = c.onVar,
                            modifier = Modifier.padding(top = 2.dp).testTag("lfs-lock-endpoint"))
                    }
                }
                choice.lock?.let {
                    Text(stringResource(R.string.lfs_m3_owner, it.owner ?: stringResource(R.string.lfs_lock_owner_missing)), style = M3Type.bodyMedium, color = c.on,
                        modifier = Modifier.padding(top = 8.dp).testTag("lfs-lock-owner"))
                }
            }
            if (choice.force) {
                M3Note(stringResource(R.string.lfs_m3_force_warning), Modifier.padding(top = 12.dp).testTag("lfs-lock-warning"), tone = M3NoteTone.Warn)
                Row(Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(acknowledged, role = Role.Checkbox) { acknowledged = it }.testTag("lfs-lock-force-ack"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LfsCheckbox(acknowledged, danger = true)
                    Text(stringResource(R.string.lfs_m3_force_ack), style = M3Type.bodyMedium, color = c.on, modifier = Modifier.testTag("lfs-lock-ack-label"))
                }
            } else {
                Text(stringResource(if (choice.lock != null) R.string.lfs_m3_release_body else R.string.lfs_m3_create_body), style = M3Type.bodyMedium, color = c.onVar,
                    modifier = Modifier.padding(top = 12.dp))
            }
            if (!valid) Text(stringResource(R.string.lfs_m3_review_changed), style = M3Type.bodySmall, color = c.error,
                modifier = Modifier.padding(top = 12.dp).testTag("lfs-lock-stale"))
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewContext = LfsLockContext("Documents/Obsidian/VoxVault", "main", "e19c4a0", "origin", "r1")
private val previewLocks = listOf(LfsLock("1", "attachments/whiteboard.png", "CodyBontecou", "2026-11-01T14:02:00Z"),
    LfsLock("2", "attachments/diagram.psd", "CodyBontecou", "2026-10-30T09:41:00Z"))
private val previewReview = LfsLockReview(previewContext, "https://github.com/CodyBontecou/VoxVault.git/info/lfs", previewLocks.take(1), previewLocks.drop(1),
    emptyList(), verified = true, supported = true, candidates = listOf("attachments/sketch.psd"))

@Composable
private fun LockListPreviewContent(review: LfsLockReview?) {
    LazyColumn(Modifier.background(M3.colors.surface)) {
        lfsLockControls(LfsViewModel.State(locks = review), onCheck = {}, confirm = {})
    }
}

@Preview(name = "LFS locks · verified · light", heightDp = 900)
@Composable
private fun LockListVerifiedLight() = SyncMdTheme(darkTheme = false) { LockListPreviewContent(previewReview) }

@Preview(name = "LFS locks · read-only · dark", heightDp = 900)
@Composable
private fun LockListReadOnlyDark() = SyncMdTheme(darkTheme = true) {
    LockListPreviewContent(previewReview.copy(verified = false, ours = emptyList(), theirs = emptyList(), listed = previewLocks))
}

@Preview(name = "LFS locks · unsupported · light", heightDp = 600)
@Composable
private fun LockListUnsupported() = SyncMdTheme(darkTheme = false) {
    LockListPreviewContent(previewReview.copy(supported = false, verified = false, ours = emptyList(), theirs = emptyList()))
}

/** HTTPS endpoints read as the board prints them (no scheme); anything else keeps its scheme visible. */
internal fun lfsDisplayEndpoint(endpoint: String): String = if (endpoint.startsWith("https://")) endpoint.removePrefix("https://") else endpoint
