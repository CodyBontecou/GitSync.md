package com.bontecou.syncmd.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.repositories.GitTrackingRemovalPlan
import com.bontecou.syncmd.ui.components.GitDangerBadge
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SheetSurface
import com.bontecou.syncmd.ui.theme.M3Switch
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3TopDivider

/** What removing tracking keeps and disconnects, counted from the working copy (StopTracking board). */
data class TrackingFacts(val files: Int, val uncommitted: Int, val branches: Int, val stashes: Int, val tags: Int, val commits: Int, val remote: String?)

/**
 * Step one of "Stop tracking" (Material 3 board StopTracking, step review): what stays on disk, what
 * is disconnected, the exact folder and metadata size, and the verified-backup switch.
 */
@Composable
internal fun TrackingRemovalReview(review: GitTrackingRemovalPlan, keepBackup: Boolean, busy: Boolean,
    onKeepBackup: (Boolean) -> Unit, onContinue: () -> Unit, onCancel: () -> Unit, facts: TrackingFacts? = null) {
    val size = Formatter.formatFileSize(LocalContext.current, review.metadataBytes)
    M3ModalSheet(onDismissRequest = { if (!busy) onCancel() }, modifier = Modifier.testTag("tracking-review-sheet"),
        footer = { ReviewFooter(busy, onContinue, onCancel) }) {
        ReviewBody(review.path, review.branch, size, keepBackup, busy, onKeepBackup, facts)
    }
}

@Composable
private fun ReviewFooter(busy: Boolean, onContinue: () -> Unit, onCancel: () -> Unit) {
    M3Button(stringResource(R.string.guide_continue), onContinue, Modifier.fillMaxWidth().testTag("tracking-review-continue"), size = M3ButtonSize.Hero, enabled = !busy)
    M3TextButton(stringResource(R.string.publication_cancel), onCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tracking-review-cancel"), enabled = !busy)
}

@Composable
private fun ReviewBody(path: String, branch: String?, size: String, keepBackup: Boolean, busy: Boolean, onKeepBackup: (Boolean) -> Unit, facts: TrackingFacts?) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().testTag("tracking-review-scroll")) {
        GitDangerBadge(R.drawable.ic_m3_link_off, Modifier.align(Alignment.CenterHorizontally))
        Text(stringResource(R.string.ui_remove_git_tracking_1f8255d9), Modifier.fillMaxWidth().padding(top = 12.dp).testTag("tracking-review-title"),
            style = M3Type.dialogTitle, color = c.on, textAlign = TextAlign.Center)
        Text(stringResource(R.string.git_m3_tracking_review_body), Modifier.padding(top = 8.dp).testTag("tracking-review-scope"), style = M3Type.bodyMedium, color = c.onVar)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactCard(stringResource(R.string.git_m3_stays_on_disk), c.okc, c.onOkc, Modifier.weight(1f), listOf(
                facts?.let { pluralStringResource(R.plurals.git_m3_notes_attachments, it.files, it.files) } ?: stringResource(R.string.git_m3_notes_attachments_any),
                facts?.takeIf { it.uncommitted > 0 }?.let { pluralStringResource(R.plurals.git_m3_uncommitted_plain, it.uncommitted, it.uncommitted) }
                    ?: stringResource(R.string.git_m3_uncommitted_plain_any),
                stringResource(R.string.git_m3_folder_location)))
            FactCard(stringResource(R.string.git_m3_disconnected), c.ec, c.onEc, Modifier.weight(1f), listOf(
                facts?.let {
                    listOf(pluralStringResource(R.plurals.git_m3_n_branches, it.branches, it.branches), pluralStringResource(R.plurals.git_m3_n_stashes, it.stashes, it.stashes),
                        pluralStringResource(R.plurals.git_m3_n_tags, it.tags, it.tags)).joinToString(", ")
                } ?: stringResource(R.string.git_m3_branches_stashes_tags),
                facts?.let { pluralStringResource(R.plurals.git_m3_n_commits_history, it.commits, it.commits) } ?: stringResource(R.string.git_m3_commit_history),
                facts?.remote?.let { stringResource(R.string.git_m3_the_named_remote, it) } ?: stringResource(R.string.git_m3_its_remotes)))
        }
        val shape = RoundedCornerShape(16.dp)
        Column(Modifier.padding(top = 12.dp).fillMaxWidth().background(c.surface, shape)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer {
                    Text(path, Modifier.testTag("tracking-review-path"), style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.on)
                }
                Text(stringResource(R.string.callback_result_branch, branch ?: stringResource(R.string.repository_discovery_branch_unknown)),
                    Modifier.testTag("tracking-review-branch"), style = M3Type.meta, color = c.onVar)
                Text(stringResource(R.string.tracking_metadata_size, size), Modifier.testTag("tracking-review-size"), style = M3Type.meta, color = c.onVar)
            }
            Row(Modifier.fillMaxWidth().m3TopDivider(true, c.outlineVar)
                .toggleable(keepBackup, enabled = !busy, role = Role.Switch, onValueChange = onKeepBackup).testTag("tracking-review-backup")
                .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp).alpha(if (busy) 0.38f else 1f),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.git_m3_keep_backup), Modifier.testTag("tracking-review-backup-label"), style = M3Type.body, color = c.on)
                    Text(stringResource(R.string.git_m3_keep_backup_sub), style = M3Type.bodySmall, color = c.onVar)
                }
                M3Switch(keepBackup, null, enabled = !busy)
            }
        }
        if (!keepBackup) M3Note(stringResource(R.string.git_m3_no_backup_warning), Modifier.padding(top = 12.dp).testTag("tracking-review-warning"), tone = M3NoteTone.Warn)
    }
}

@Composable
private fun FactCard(title: String, container: Color, content: Color, modifier: Modifier, lines: List<String>) {
    Column(modifier.background(container, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(title, style = M3Type.badge, color = content)
        Text(lines.joinToString("\n"), Modifier.padding(top = 6.dp), style = M3Type.bodySmall.copy(lineHeight = M3Type.bodySmall.lineHeight * 1.05f), color = content)
    }
}

@Preview(name = "Stop tracking · backup · light")
@Composable
private fun TrackingReviewBackupPreview() = SyncMdTheme(darkTheme = false) {
    M3SheetSurface(footer = { ReviewFooter(false, {}, {}) }) {
        ReviewBody("/storage/emulated/0/Documents/Obsidian/VoxVault", "main", "4.2 MB", true, false, {}, TrackingFacts(214, 3, 3, 1, 12, 186, "origin"))
    }
}

@Preview(name = "Stop tracking · no backup · dark")
@Composable
private fun TrackingReviewPermanentPreview() = SyncMdTheme(darkTheme = true) {
    M3SheetSurface(footer = { ReviewFooter(false, {}, {}) }) {
        ReviewBody("/storage/emulated/0/Documents/Obsidian/VoxVault", null, "4.2 MB", false, false, {}, null)
    }
}
