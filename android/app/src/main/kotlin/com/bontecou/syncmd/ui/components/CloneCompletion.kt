package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withStateAtLeast
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.repositories.CompletedClone
import com.bontecou.syncmd.ui.theme.*
import androidx.compose.ui.res.pluralStringResource
import com.bontecou.syncmd.ui.viewmodels.RepositoryHistoryEntry
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The visible clone route owns registration and delivery; leaving it cancels any late navigation. */
@Composable
fun CloneCompletion(completed: CompletedClone, name: String, settings: SettingsViewModel,
    alias: String = "", historyEntry: RepositoryHistoryEntry? = null, onReviewLfs: ((String) -> Unit)? = null, onRegistered: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callback by rememberUpdatedState(onRegistered)
    val reviewCallback by rememberUpdatedState(onReviewLfs)
    val lfsDecision = remember(completed) { CompletableDeferred<Boolean>() }
    var lfsAttention by remember(completed) { mutableStateOf(false) }
    var lfsFiles by remember(completed) { mutableStateOf<List<CloneLfsRow>>(emptyList()) }
    var attempt by remember(completed) { mutableStateOf(0) }
    var busy by remember(completed) { mutableStateOf(true) }
    var failed by remember(completed) { mutableStateOf(false) }
    var delivered by remember(completed) { mutableStateOf(false) }
    LaunchedEffect(completed, attempt, name, alias, historyEntry) {
        if (delivered) return@LaunchedEffect
        busy = true; failed = false
        try {
            settings.registerCompletedClone(completed, name, alias, historyEntry)
            val pending = try {
                withContext(Dispatchers.IO) {
                    com.bontecou.syncmd.services.git.lfs.LfsRepository().inspect(completed.path).filter { !it.hydrated }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { null }
            val needsLfsReview = pending == null || pending.isNotEmpty()
            val reviewLfs = if (needsLfsReview) {
                lfsFiles = cloneLfsRows(pending.orEmpty().map { it.path to it.pointer.size })
                lfsAttention = true; busy = false
                lfsDecision.await()
            } else false
            while (!delivered) {
                lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {}
                yield() // Navigation must leave the lifecycle observer's dispatch turn.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    delivered = true
                    if (reviewLfs && reviewCallback != null) reviewCallback?.invoke(completed.path) else callback()
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { delivered = false; failed = true }
        finally { busy = false }
    }
    CloneCompletionContent(name = name, busy = busy, lfsAttention = lfsAttention && !failed && !delivered, lfsFiles = lfsFiles, failed = failed,
        canReviewLfs = reviewCallback != null,
        onReviewLfs = { lfsDecision.complete(true) }, onContinue = { lfsDecision.complete(false) },
        onRetry = { if (!busy) { busy = true; failed = false; attempt++ } })
}

/** One line of the LFS review list: a file and its size, or a folder and how many files wait in it. */
data class CloneLfsRow(val path: String, val size: Long?, val count: Int = 1)

/** Folders holding three or more pending files collapse to "covers/ · 6 files" (board Cloning "lfs"). */
internal fun cloneLfsRows(files: List<Pair<String, Long>>, limit: Int = 5): List<CloneLfsRow> {
    val byFolder = files.groupBy { it.first.substringBeforeLast('/', "") }
    val rows = byFolder.flatMap { (folder, items) ->
        if (folder.isNotEmpty() && items.size >= 3) listOf(CloneLfsRow("$folder/", null, items.size))
        else items.map { CloneLfsRow(it.first, it.second) }
    }.sortedBy { it.path.lowercase() }
    return rows.take(limit)
}

/** Stateless finish-up states (board Cloning): finishing, Git LFS needs review, registration failed. */
@Composable
private fun CloneCompletionContent(name: String, busy: Boolean, lfsAttention: Boolean, lfsFiles: List<CloneLfsRow>, failed: Boolean, canReviewLfs: Boolean,
    onReviewLfs: () -> Unit, onContinue: () -> Unit, onRetry: () -> Unit) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (busy) {
            AddVaultBusy(stringResource(R.string.add_clone_finishing), Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp))
            Text(stringResource(R.string.add_clone_finishing_body, name), style = M3Type.bodySmall, color = c.onVar,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
        }
        if (lfsAttention) {
            M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).testTag("clone-lfs-card")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AddVaultInlineIssue(stringResource(R.string.add_clone_lfs), Modifier.testTag("clone-lfs-attention"), icon = R.drawable.ic_m3_cloud_download, tint = c.tertiary)
                    if (lfsFiles.isNotEmpty()) M3ListCard(color = c.surface, radius = 16.dp) {
                        lfsFiles.forEachIndexed { index, row ->
                            Row(Modifier.fillMaxWidth().m3TopDivider(index > 0, c.outlineVar).padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(row.path, style = M3Type.meta, color = c.on, modifier = Modifier.weight(1f), maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(row.size?.let(::addVaultSize) ?: pluralStringResource(R.plurals.add_clone_lfs_files, row.count, row.count), style = M3Type.meta, color = c.onVar)
                            }
                        }
                    }
                    if (canReviewLfs) M3FilledButton(stringResource(R.string.add_clone_lfs_review), onReviewLfs, Modifier.fillMaxWidth().testTag("clone-lfs-review"))
                    M3OutlinedButton(stringResource(R.string.add_clone_continue), onContinue, Modifier.fillMaxWidth().testTag("clone-lfs-continue"))
                }
            }
        }
        if (failed) {
            M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AddVaultInlineIssue(stringResource(R.string.add_clone_register_failed), Modifier.testTag("clone-registration-error"))
                    M3FilledButton(stringResource(R.string.add_clone_register_retry), onRetry, Modifier.fillMaxWidth().testTag("clone-registration-retry"))
                }
            }
        }
    }
}

@Composable
private fun CompletionPreview(busy: Boolean, lfs: Boolean, failed: Boolean) {
    Box(Modifier.background(M3.colors.surface).padding(vertical = 16.dp)) {
        CloneCompletionContent("reading-vault", busy, lfs, cloneLfsRows(listOf("attachments/reading-map.pdf" to 18_000_000L, "audio/ch3-notes.m4a" to 42_000_000L) +
            (1..6).map { "covers/$it.jpg" to 1L }), failed, canReviewLfs = true, {}, {}, {})
    }
}

@Preview(name = "Completion · finishing") @Composable
private fun CompletionBusy() = SyncMdTheme(darkTheme = false) { CompletionPreview(true, false, false) }

@Preview(name = "Completion · LFS review") @Composable
private fun CompletionLfs() = SyncMdTheme(darkTheme = false) { CompletionPreview(false, true, false) }

@Preview(name = "Completion · failed · dark") @Composable
private fun CompletionFailedDark() = SyncMdTheme(darkTheme = true) { CompletionPreview(false, false, true) }
