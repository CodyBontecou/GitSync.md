package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.services.git.OutgoingSummary
import com.bontecou.syncmd.services.git.RemoteDivergence
import com.bontecou.syncmd.services.git.TransferUpdate
import com.bontecou.syncmd.sync.SyncStep
import com.bontecou.syncmd.sync.SyncStepId
import com.bontecou.syncmd.sync.SyncStepState
import com.bontecou.syncmd.sync.VaultSyncKind
import com.bontecou.syncmd.sync.VaultSyncRun
import com.bontecou.syncmd.sync.operationLabel
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3CodeBlock
import com.bontecou.syncmd.ui.theme.M3CountText
import com.bontecou.syncmd.ui.theme.M3Divider
import com.bontecou.syncmd.ui.theme.M3Hero
import com.bontecou.syncmd.ui.theme.M3HeroHeadline
import com.bontecou.syncmd.ui.theme.M3HeroKicker
import com.bontecou.syncmd.ui.theme.M3HeroStyle
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3ProgressBar
import com.bontecou.syncmd.ui.theme.M3ProgressTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3StepRow
import com.bontecou.syncmd.ui.theme.M3StepState
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3Gutter
import kotlinx.coroutines.delay
import java.util.Locale

// ─── Formatting ─────────────────────────────────────────────────────────────

/** Compact age in the design's format: "just now", "2m ago", "6h ago", "3d ago". */
@Composable
fun vaultAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - millis).coerceAtLeast(0) / 60_000L).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.vault_m3_just_now)
        minutes < 60 -> stringResource(R.string.vault_m3_minutes_ago, minutes)
        minutes < 60 * 24 -> stringResource(R.string.vault_m3_hours_ago, minutes / 60)
        else -> stringResource(R.string.vault_m3_days_ago, minutes / (60 * 24))
    }
}

/** Sizes as the design writes them: "820 B", "412 KB", "2 MB", "1.2 MB". */
fun vaultSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    val text = if (value >= 10 || value % 1.0 < 0.05) "%.0f".format(Locale.ROOT, value) else "%.1f".format(Locale.ROOT, value)
    return "$text ${units[unit]}"
}

/** Fraction of the current transfer task, or null when JGit has not said how much work there is. */
private fun TransferUpdate.fraction(): Float? = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

private val TransferUpdate.isWriting get() = task.startsWith("Writing", ignoreCase = true)

// ─── Syncing (board 22) ─────────────────────────────────────────────────────

/** "Pushing…" hero with Cancel, determinate progress, bytes and ETA, then Steps and the note. */
@Composable
fun VaultSyncingSection(run: VaultSyncRun, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    // Re-read the clock so the ETA counts down between callbacks.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run.running) { while (run.running) { delay(1000); now = System.currentTimeMillis() } }
    val transfer = run.transfer
    val fraction = transfer?.fraction()
    val current = run.current?.id
    Column(modifier.fillMaxWidth()) {
        M3Hero(Modifier.m3Gutter().padding(top = 8.dp).testTag("vault-syncing").semantics { liveRegion = LiveRegionMode.Polite },
            style = M3HeroStyle.PrimaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(operationLabel(run)), style = M3Type.headline.copy(letterSpacing = M3Type.title.letterSpacing),
                    color = c.onPc, modifier = Modifier.weight(1f))
                M3Button(stringResource(R.string.vault_m3_cancel), onCancel, Modifier.testTag("vault-sync-cancel"),
                    style = M3ButtonStyle.OutlinedContent, size = M3ButtonSize.Medium)
            }
            Text(syncSubtitle(run), style = M3Type.bodyMedium, color = c.onPc, modifier = Modifier.padding(top = 2.dp))
            val description = stringResource(if (run.kind == VaultSyncKind.PULL || current in setOf(SyncStepId.DOWNLOAD, SyncStepId.MERGE, SyncStepId.UPDATE))
                R.string.vault_m3_download_progress else R.string.vault_m3_upload_progress)
            if (fraction != null) M3ProgressBar(fraction, Modifier.padding(top = 16.dp), tone = M3ProgressTone.PrimaryContainer, description = description)
            else M3IndeterminateProgressBar(Modifier.padding(top = 16.dp), tone = M3ProgressTone.PrimaryContainer, description = description)
            val bytes = bytesLine(run.outgoing, transfer, current)
            val eta = if (fraction != null && fraction in 0.02f..0.995f && run.taskStartedAt > 0) {
                val elapsed = (now - run.taskStartedAt).coerceAtLeast(0)
                ((elapsed * (1 - fraction) / fraction) / 1000).toLong()
            } else null
            if (bytes != null || eta != null) Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(bytes.orEmpty(), style = M3Type.meta, color = c.onPc, modifier = Modifier.weight(1f))
                if (eta != null) Text(stringResource(R.string.vault_m3_time_left,
                    if (eta < 60) stringResource(R.string.vault_m3_seconds, eta.toInt().coerceAtLeast(1)) else stringResource(R.string.vault_m3_minutes, (eta / 60).toInt())),
                    style = M3Type.meta, color = c.onPc)
            }
        }
        M3SectionLabel(stringResource(R.string.vault_m3_steps))
        M3ListCard(Modifier.m3Gutter().padding(top = 8.dp).testTag("vault-sync-steps")) {
            run.steps.forEachIndexed { index, step ->
                M3StepRow(stepLabel(run, step), step.state.m3(), meta = stepMeta(step, transfer), divider = index > 0)
            }
        }
        M3Note(stringResource(R.string.vault_m3_leave_note), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Plain)
    }
}

private fun SyncStepState.m3() = when (this) {
    SyncStepState.DONE -> M3StepState.Done
    SyncStepState.NOW -> M3StepState.Now
    SyncStepState.TODO -> M3StepState.Todo
}

@Composable
private fun hostOrRemote(run: VaultSyncRun) = run.hostLabel ?: stringResource(R.string.vault_m3_remote_generic)

@Composable
private fun syncSubtitle(run: VaultSyncRun): String {
    val transfer = run.transfer
    val outgoing = run.outgoing
    return when (run.current?.id) {
        SyncStepId.COMMIT -> pluralStringResource(R.plurals.vault_m3_step_commit, run.files, run.files)
        SyncStepId.CHECK -> stringResource(R.string.vault_m3_checking_remote, hostOrRemote(run))
        SyncStepId.BRANCH -> stringResource(R.string.vault_m3_step_branch, run.branchName.orEmpty())
        SyncStepId.LFS -> (outgoing?.lfsFiles ?: 0).let { pluralStringResource(R.plurals.vault_m3_uploading_lfs, it, it) }
        SyncStepId.UPLOAD -> when {
            outgoing != null && outgoing.commits > 0 -> {
                // JGit writes a pack's commits before its trees and blobs, so the first objects written are the commits.
                val sent = if (transfer != null && transfer.isWriting) minOf(transfer.done, outgoing.commits) else 0
                stringResource(R.string.vault_m3_commits_sent, sent, outgoing.commits)
            }
            transfer != null && transfer.total > 0 -> stringResource(R.string.vault_m3_objects_sent, transfer.done, transfer.total)
            else -> stringResource(R.string.vault_m3_step_upload, run.target)
        }
        SyncStepId.DOWNLOAD, SyncStepId.MERGE, SyncStepId.UPDATE ->
            if (transfer != null && transfer.total > 0) stringResource(R.string.vault_m3_objects_received, transfer.done, transfer.total)
            else stepLabel(run, run.current!!)
        null -> stringResource(R.string.vault_m3_preparing)
    }
}

/** "412 KB / 610 KB" while a pack is written (sizes read before the push), else objects. */
private fun bytesLine(outgoing: OutgoingSummary?, transfer: TransferUpdate?, current: SyncStepId?): String? {
    if (transfer == null || transfer.total <= 0) return null
    val fraction = transfer.done.toDouble() / transfer.total
    return when {
        current == SyncStepId.UPLOAD && transfer.isWriting && outgoing != null && outgoing.bytes > 0 ->
            "${vaultSize((outgoing.bytes * fraction).toLong())} / ${vaultSize(outgoing.bytes)}"
        else -> null
    }
}

@Composable
private fun stepLabel(run: VaultSyncRun, step: SyncStep): String = when (step.id) {
    SyncStepId.COMMIT -> pluralStringResource(R.plurals.vault_m3_step_commit, run.files, run.files)
    SyncStepId.CHECK -> stringResource(R.string.vault_m3_step_check, hostOrRemote(run))
    SyncStepId.BRANCH -> stringResource(R.string.vault_m3_step_branch, run.branchName ?: "…")
    SyncStepId.DOWNLOAD -> stringResource(R.string.vault_m3_step_download, run.target)
    SyncStepId.MERGE -> stringResource(R.string.vault_m3_step_merge, run.target)
    SyncStepId.UPDATE -> stringResource(R.string.vault_m3_step_update)
    SyncStepId.LFS -> (run.outgoing?.lfsFiles ?: 0).let { pluralStringResource(R.plurals.vault_m3_step_lfs, it, it, vaultSize(run.outgoing?.lfsBytes ?: 0)) }
    SyncStepId.UPLOAD -> stringResource(R.string.vault_m3_step_upload, run.target)
}

@Composable
private fun stepMeta(step: SyncStep, transfer: TransferUpdate?): String? = when {
    step.state == SyncStepState.NOW && step.id in setOf(SyncStepId.UPLOAD, SyncStepId.DOWNLOAD, SyncStepId.UPDATE, SyncStepId.MERGE) ->
        transfer?.fraction()?.let { "${(it * 100).toInt()}%" }
    step.state == SyncStepState.DONE && step.none -> stringResource(R.string.vault_m3_step_none)
    else -> step.meta
}

// ─── Push rejected (board 25) ───────────────────────────────────────────────

/** Inline rejected state: error hero with ahead/behind chips, the overlap card, three ways out, the git line. */
@Composable
fun VaultPushRejectedSection(
    divergence: RemoteDivergence?,
    gitLine: String,
    onPullThenPush: () -> Unit,
    onReviewFirst: () -> Unit,
    onPushToBranch: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = M3.colors
    Column(modifier.fillMaxWidth().testTag("vault-push-rejected")) {
        M3Hero(Modifier.m3Gutter().padding(top = 8.dp), style = M3HeroStyle.ErrorContainer) {
            M3HeroKicker(stringResource(R.string.vault_m3_rejected_kicker))
            M3HeroHeadline(stringResource(R.string.tide_vault_moved_on), Modifier.padding(top = 6.dp), style = M3Type.headlineSmall)
            if (divergence != null) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RejectChip(stringResource(R.string.vault_m3_chip_yours, divergence.ahead), c.ahead)
                val remote = divergence.newestRemote
                RejectChip(
                    if (remote != null) stringResource(R.string.vault_m3_chip_theirs, divergence.behind, remote.author, vaultAgo(remote.timestamp))
                    else stringResource(R.string.vault_m3_chip_theirs_plain, divergence.behind),
                    c.behind, Modifier.weight(1f, fill = false),
                )
            }
        }
        if (divergence != null) OverlapCard(divergence, Modifier.m3Gutter().padding(top = 12.dp))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            M3Button(stringResource(R.string.vault_m3_pull_then_push), onPullThenPush, Modifier.fillMaxWidth().testTag("vault-pull-then-push"),
                style = M3ButtonStyle.Filled, size = M3ButtonSize.Large, enabled = enabled)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                M3Button(stringResource(R.string.vault_m3_review_first), onReviewFirst, Modifier.weight(1f).testTag("vault-review-first"),
                    style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large, enabled = enabled)
                M3Button(stringResource(R.string.vault_m3_push_branch), onPushToBranch, Modifier.weight(1f).testTag("vault-push-branch"),
                    style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large, enabled = enabled)
            }
        }
        M3CodeBlock(gitLine, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).testTag("vault-rejected-line"))
    }
}

@Composable
private fun RejectChip(text: String, ink: Color, modifier: Modifier = Modifier) {
    Text(text, modifier.background(M3.colors.surface, RoundedCornerShape(8.dp)).heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 7.dp),
        style = M3Type.count, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun OverlapCard(divergence: RemoteDivergence, modifier: Modifier = Modifier) {
    val c = M3.colors
    val overlap = divergence.overlap
    val rows = buildList {
        divergence.yours.filterNot { it in overlap }.forEach { add(it to 0) }
        divergence.theirs.filterNot { it in overlap }.forEach { add(it to 1) }
        overlap.forEach { add(it to 2) }
    }.take(24)
    M3ListCard(modifier.testTag("vault-overlap")) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            M3Icon(if (overlap.isEmpty()) R.drawable.ic_m3_check else R.drawable.ic_m3_warning, tint = if (overlap.isEmpty()) c.ok else c.error)
            Column {
                Text(if (overlap.isEmpty()) stringResource(R.string.vault_m3_no_overlap_title) else pluralStringResource(R.plurals.vault_m3_overlap_title, overlap.size, overlap.size),
                    style = M3Type.bodyStrong, color = c.on)
                Text(stringResource(if (overlap.isEmpty()) R.string.vault_m3_no_overlap_body else R.string.vault_m3_overlap_body),
                    style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
            rows.forEach { (path, side) ->
                M3Divider()
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(path, style = M3Type.code, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val (label, ink) = when (side) {
                        0 -> stringResource(R.string.vault_m3_who_yours) to c.ahead
                        1 -> stringResource(R.string.vault_m3_who_theirs) to c.behind
                        else -> stringResource(R.string.vault_m3_who_both) to c.error
                    }
                    M3CountText(label, Modifier.padding(start = 12.dp), color = ink, style = M3Type.metaStrong)
                }
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewRun = VaultSyncRun(
    path = "/vaults/VoxVault", kind = VaultSyncKind.COMMIT_PUSH, target = "origin/main", hostLabel = "GitHub", files = 3,
    steps = listOf(SyncStep(SyncStepId.COMMIT, SyncStepState.DONE, "e19c4a0"), SyncStep(SyncStepId.CHECK, SyncStepState.DONE, none = true),
        SyncStep(SyncStepId.UPLOAD, SyncStepState.NOW), SyncStep(SyncStepId.LFS)),
    outgoing = OutgoingSummary(3, 40, 610_000, 1, 2_000_000),
    transfer = TransferUpdate(com.bontecou.syncmd.services.git.TransferPhase.GIT, "Writing objects", 27, 40),
    taskStartedAt = System.currentTimeMillis() - 6000,
)

private val previewDivergence = RemoteDivergence("main", "origin/main", 2, 1,
    Commit("a41c9e2", author = "Mac", message = "Capture", timestamp = System.currentTimeMillis() - 240_000),
    listOf("daily/2026-11-01.md", "projects/q4.md"), listOf("inbox.md"))

@Preview(name = "Syncing", heightDp = 760) @Composable
private fun PreviewSyncing() = SyncMdTheme { Column(Modifier.background(M3.colors.surface)) { VaultSyncingSection(previewRun, {}) } }

@Preview(name = "Push rejected · dark", heightDp = 760) @Composable
private fun PreviewRejected() = SyncMdTheme(darkTheme = true) {
    Column(Modifier.background(M3.colors.surface)) { VaultPushRejectedSection(previewDivergence, "! [rejected] main -> main (non-fast-forward)", {}, {}, {}) }
}
