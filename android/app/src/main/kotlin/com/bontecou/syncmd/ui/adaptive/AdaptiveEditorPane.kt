package com.bontecou.syncmd.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.FileLineCount
import com.bontecou.syncmd.ui.components.CommitSheet
import com.bontecou.syncmd.localization.resourceId
import com.bontecou.syncmd.ui.screens.LineNumberedEditor
import com.bontecou.syncmd.ui.screens.MarkdownPreview
import com.bontecou.syncmd.ui.screens.isMarkdownPath
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Spinner
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.FileEditorViewModel
import com.bontecou.syncmd.ui.viewmodels.VaultSyncViewModel

/**
 * The editor in the detail pane (boards TabletEditor, FoldEditor): the vault and its changes stay
 * beside it. Write / Preview in the header, then "Saved · 3 lines changed" and "Commit this file",
 * which stages this file alone and opens the commit sheet. Autosave is the phone editor's.
 */
@Composable
fun EditorPane(
    repositoryPath: String,
    filePath: String,
    vaultName: String,
    count: FileLineCount?,
    onCommitted: () -> Unit,
    modifier: Modifier = Modifier,
    syncViewModel: VaultSyncViewModel? = null,
    viewModel: FileEditorViewModel = hiltViewModel(key = "adaptive-editor"),
    diffVM: DiffViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath, filePath) { viewModel.open(repositoryPath, filePath) }
    // Leaving the pane (another file, another vault) saves what was typed.
    androidx.compose.runtime.DisposableEffect(repositoryPath, filePath) {
        onDispose { if (viewModel.state.value.isDirty) viewModel.save() }
    }
    val state by viewModel.state.collectAsState()
    val c = M3.colors
    var committing by remember(filePath) { mutableStateOf(false) }
    val document = state.document
    val markdown = isMarkdownPath(filePath)
    var preview by rememberSaveable(filePath) { mutableStateOf(false) }
    val folder = filePath.substringBeforeLast('/', "").ifEmpty { vaultName }
    val subtitle = buildList {
        add(folder)
        add(if (state.head?.tracked == false) stringResource(R.string.adaptive_new_file) else stringResource(R.string.adaptive_edited))
        if (count != null && !count.binary && (count.added > 0 || count.removed > 0))
            add(listOfNotNull(count.added.takeIf { it > 0 }?.let { "+$it" }, count.removed.takeIf { it > 0 }?.let { "−$it" }).joinToString(" "))
    }.joinToString(" · ")
    val status = when {
        state.saveFailed && state.isDirty -> stringResource(R.string.editor_m3_not_saved)
        state.isDirty || state.autosaving || state.isSaving -> stringResource(R.string.editor_m3_saving)
        state.linesChanged > 0 -> stringResource(R.string.editor_m3_saved_changed,
            pluralStringResource(R.plurals.editor_m3_lines_changed, state.linesChanged, state.linesChanged))
        else -> stringResource(R.string.editor_m3_saved)
    }
    val dot = when {
        state.saveFailed && state.isDirty -> c.error
        state.isDirty || state.autosaving -> c.ahead
        else -> c.ok
    }
    EditorPaneFrame(
        title = nameOf(filePath),
        subtitle = subtitle,
        markdown = markdown,
        preview = preview,
        onPreview = { preview = it },
        status = status,
        dot = dot,
        canCommit = document != null && !state.isSaving && !state.isLoading,
        onCommit = {
            val stage = { diffVM.refreshRepositoryPath(repositoryPath); diffVM.stageOnly(filePath) { committing = true } }
            if (viewModel.state.value.isDirty) viewModel.save { stage() } else stage()
        },
        modifier = modifier,
    ) {
        when {
            state.isLoading -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { M3Spinner(size = 24.dp, color = c.onVar) }
            document == null -> Text(stringResource(R.string.ui_unable_to_open_file_2787a028), Modifier.padding(32.dp), style = M3Type.bodyMedium, color = c.onVar)
            document.content == null -> Text(document.unavailableReason?.let { stringResource(it.resourceId) }.orEmpty(), Modifier.padding(32.dp),
                style = M3Type.bodyMedium, color = c.onVar)
            markdown && preview -> MarkdownPreview(state.draft, Modifier.padding(horizontal = 16.dp).testTag("editor-preview"))
            else -> {
                val field = remember(document.path) { mutableStateOf(TextFieldValue(state.draft)) }
                LineNumberedEditor(document.path, state.draft, !state.isSaving, viewModel::updateDraft, state.changedLines, field = field)
            }
        }
    }
    state.error?.let { error ->
        M3Dialog(onDismissRequest = viewModel::clearError, title = stringResource(R.string.ui_file_operation_failed_f78a328b),
            icon = R.drawable.ic_m3_warning, iconTint = c.error,
            content = { Text(stringResource(error.resourceId)) }) { M3DialogAction(stringResource(R.string.ui_keep_editing_e76fd2ad), viewModel::clearError) }
    }
    if (committing) CommitSheet(repositoryPath, vaultName, onDismiss = { committing = false; viewModel.refreshLocalState(); onCommitted() },
        diffVM = diffVM, syncViewModel = syncViewModel)
}

/** Stateless frame of the editor pane: header with Write / Preview, the body, the saved status and Commit this file. */
@Composable
fun EditorPaneFrame(
    title: String,
    subtitle: String,
    markdown: Boolean,
    preview: Boolean,
    onPreview: (Boolean) -> Unit,
    status: String,
    dot: Color,
    canCommit: Boolean,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val c = M3.colors
    Column(modifier.fillMaxSize().detailPane().testTag("editor-pane")) {
        DetailHeader(title, subtitle) {
            if (markdown) CompactSegmented(listOf(stringResource(R.string.m3_editor_write), stringResource(R.string.m3_editor_preview)),
                if (preview) 1 else 0, { onPreview(it == 1) })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { body() }
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVar))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(dot, CircleShape))
                    Text(status, style = M3Type.caption, color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("file-editor-status"))
                }
                Spacer(Modifier.size(0.dp))
                M3Button(stringResource(R.string.adaptive_commit_this_file), onCommit, Modifier.testTag("editor-commit-file"),
                    style = M3ButtonStyle.Filled, size = M3ButtonSize.Medium, enabled = canCommit)
            }
        }
    }
}
