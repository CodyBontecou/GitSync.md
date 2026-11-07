package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.files.FileCommit
import com.bontecou.syncmd.services.files.FileInsights
import com.bontecou.syncmd.ui.components.fileAgeLabel
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3Spinner
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3GroupedItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Navigation route for a file's (or folder's) commit log; the path argument is URL-encoded. */
const val FILE_HISTORY_ROUTE = "file-history"

@HiltViewModel
class FileHistoryViewModel @Inject constructor() : ViewModel() {
    data class State(val commits: List<FileCommit> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)

    private val insights = FileInsights()
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun load(repositoryPath: String, path: String) {
        if (repositoryPath.isBlank() || path.isBlank()) return
        _state.value = _state.value.copy(loading = true, failed = false)
        viewModelScope.launch {
            _state.value = try {
                State(insights.history(repositoryPath, path), loading = false)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { State(loading = false, failed = true) }
        }
    }
}

/** Long-press → History on Files: the commits that touched this file, newest first. */
@Composable
fun FileHistoryScreen(
    repositoryPath: String,
    filePath: String,
    onNavigateBack: () -> Unit,
    viewModel: FileHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(repositoryPath, filePath) { viewModel.load(repositoryPath, filePath) }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) { viewModel.load(repositoryPath, filePath) }
    FileHistoryContent(filePath, state, onNavigateBack)
}

@Composable
private fun FileHistoryContent(filePath: String, state: FileHistoryViewModel.State, onBack: () -> Unit) {
    val c = M3.colors
    val folder = filePath.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else "$it/" }
    Column(Modifier.fillMaxSize().background(c.surface).testTag("file-history-screen")) {
        M3TopBar(filePath.substringAfterLast('/'), subtitle = stringResource(R.string.filehistory_m3_subtitle, folder), onBack = onBack)
        when {
            state.loading && state.commits.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                M3Spinner(size = 24.dp, color = c.onVar)
            }
            state.failed -> M3Note(stringResource(R.string.filehistory_m3_load_failed), Modifier.padding(16.dp), tone = M3NoteTone.Warn)
            state.commits.isEmpty() -> M3Note(stringResource(R.string.filehistory_m3_empty), Modifier.padding(16.dp), tone = M3NoteTone.Muted)
            else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().testTag("file-history-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { M3SectionLabel(stringResource(R.string.filehistory_m3_commits), state.commits.size, Modifier.padding(bottom = 8.dp)) }
                itemsIndexed(state.commits, key = { _, commit -> commit.hash }) { index, commit ->
                    M3ListRow(
                        title = commit.message,
                        meta = stringResource(R.string.filehistory_m3_meta, commit.shortHash, commit.author, fileAgeLabel(commit.time)),
                        leading = {
                            Box(Modifier.size(12.dp).background(if (index == 0) c.primary else c.onVar, CircleShape))
                        },
                        divider = index > 0,
                        modifier = Modifier.padding(horizontal = 16.dp).m3GroupedItem(index, state.commits.size, c.cLow),
                    )
                }
            }
        }
    }
}

@Preview(name = "File history", heightDp = 520) @Composable
private fun PreviewFileHistory() = SyncMdTheme {
    val now = System.currentTimeMillis()
    FileHistoryContent("ideas/voice-memo-pipeline.md", FileHistoryViewModel.State(listOf(
        FileCommit("a41c9e2aa", "a41c9e2", "Capture morning voice notes", "Obsidian Git on Mac", now - 6 * 3_600_000L),
        FileCommit("c22e914aa", "c22e914", "Restructure ideas/ folder", "Cody Bontecou", now - 3 * 86_400_000L),
    ), loading = false), {})
}
