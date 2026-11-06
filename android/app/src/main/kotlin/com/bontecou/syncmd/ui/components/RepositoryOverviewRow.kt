package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.RepositoryOverview
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@Composable
fun recordedSyncText(time: Long?): String = time?.let {
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
        LocalConfiguration.current.locales[0]).format(java.util.Date(it))
} ?: stringResource(R.string.repository_sync_not_recorded)

@Composable
fun headCommitText(head: String?): String = head?.take(7) ?: stringResource(R.string.repository_no_commits)

sealed interface RepositoryOverviewState {
    data object Loading : RepositoryOverviewState
    data object Unavailable : RepositoryOverviewState
    data class Loaded(val overview: RepositoryOverview) : RepositoryOverviewState
}

/** Each composed card owns its read. Stopping, disposal or path changes cancel old publication. */
@Composable
fun rememberRepositoryOverview(path: String,
    read: suspend (String) -> RepositoryOverview = { RepositoryOverview.read(it) },
): State<RepositoryOverviewState> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentRead by rememberUpdatedState(read)
    return key(path, lifecycle) {
        produceState<RepositoryOverviewState>(RepositoryOverviewState.Loading, path, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    com.bontecou.syncmd.observation.RepositoryObservations.observe(path).collectLatest {
                        // Keep a loaded card steady during a foreground refresh.
                        // Initial/path/resume reads still start in Loading, and
                        // a failed read replaces the old data with Unavailable.
                        value = try { RepositoryOverviewState.Loaded(withContext(Dispatchers.IO) { currentRead(path) }) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { RepositoryOverviewState.Unavailable }
                    }
                } finally { value = RepositoryOverviewState.Loading }
            }
        }
    }
}

/** Mono git facts ("Branch: main · Commit SHA: e19c4a0 · Last Sync: …") that wrap instead of clipping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepositoryOverviewRow(branch: String, head: String?, time: Long?, tagPrefix: String) {
    val c = Tide.colors
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Triple(stringResource(R.string.ui_branch_52656e81), branch, "branch"),
            Triple(stringResource(R.string.ui_commit_sha_d0bc6028), headCommitText(head), "head"),
            Triple(stringResource(R.string.ui_last_sync_a0d540dd), recordedSyncText(time), "sync"),
        ).forEach { (label, value, kind) ->
            Text("$label: $value", Modifier.testTag("$tagPrefix-$kind"), color = c.inkMid, style = TideType.mono(12.sp))
        }
    }
}
