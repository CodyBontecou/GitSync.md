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
    var attempt by remember(completed) { mutableStateOf(0) }
    var busy by remember(completed) { mutableStateOf(true) }
    var failed by remember(completed) { mutableStateOf(false) }
    var delivered by remember(completed) { mutableStateOf(false) }
    LaunchedEffect(completed, attempt, name, alias, historyEntry) {
        if (delivered) return@LaunchedEffect
        busy = true; failed = false
        try {
            settings.registerCompletedClone(completed, name, alias, historyEntry)
            val needsLfsReview = try {
                withContext(Dispatchers.IO) {
                    com.bontecou.syncmd.services.git.lfs.LfsRepository().inspect(completed.path).any { !it.hydrated }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { true }
            val reviewLfs = if (needsLfsReview) {
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
    CloneCompletionContent(busy = busy, lfsAttention = lfsAttention && !failed && !delivered, failed = failed,
        canReviewLfs = reviewCallback != null,
        onReviewLfs = { lfsDecision.complete(true) }, onContinue = { lfsDecision.complete(false) },
        onRetry = { if (!busy) { busy = true; failed = false; attempt++ } })
}

/** Stateless finish-up states: registering, Git LFS needs review, registration failed. */
@Composable
private fun CloneCompletionContent(busy: Boolean, lfsAttention: Boolean, failed: Boolean, canReviewLfs: Boolean,
    onReviewLfs: () -> Unit, onContinue: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (busy) AddVaultBusyLine(stringResource(R.string.ui_finishing_up_13f4f85a), Modifier.padding(horizontal = 4.dp))
        if (lfsAttention) {
            TideCard {
                AddVaultNote(stringResource(R.string.clone_lfs_attention), TideBannerTone.AHEAD, textModifier = Modifier.testTag("clone-lfs-attention"))
                Spacer(Modifier.height(16.dp))
                if (canReviewLfs) {
                    BPrimaryButton(stringResource(R.string.clone_lfs_review), modifier = Modifier.testTag("clone-lfs-review"), onClick = onReviewLfs)
                    Spacer(Modifier.height(10.dp))
                }
                BSecondaryButton(stringResource(R.string.guide_continue), modifier = Modifier.testTag("clone-lfs-continue"), onClick = onContinue)
            }
        }
        if (failed) {
            TideCard {
                AddVaultNote(stringResource(R.string.clone_registration_failed), TideBannerTone.DANGER, textModifier = Modifier.testTag("clone-registration-error"))
                Spacer(Modifier.height(16.dp))
                BPrimaryButton(stringResource(R.string.ui_add_cloned_repository_f84b64e8), modifier = Modifier.testTag("clone-registration-retry"), onClick = onRetry)
            }
        }
    }
}

@Composable
private fun CompletionPreview(busy: Boolean, lfs: Boolean, failed: Boolean) {
    Box(Modifier.background(Tide.colors.paper).padding(vertical = 16.dp)) {
        CloneCompletionContent(busy, lfs, failed, canReviewLfs = true, {}, {}, {})
    }
}

@Preview(name = "Completion · finishing") @Composable
private fun CompletionBusy() = SyncMdTheme(darkTheme = false) { CompletionPreview(true, false, false) }

@Preview(name = "Completion · LFS review") @Composable
private fun CompletionLfs() = SyncMdTheme(darkTheme = false) { CompletionPreview(false, true, false) }

@Preview(name = "Completion · failed · dark") @Composable
private fun CompletionFailedDark() = SyncMdTheme(darkTheme = true) { CompletionPreview(false, false, true) }
