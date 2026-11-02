package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.localization.resourceId
import com.bontecou.syncmd.services.files.RepositoryFile
import com.bontecou.syncmd.services.files.SyntaxHighlighter
import com.bontecou.syncmd.services.files.SyntaxKind
import com.bontecou.syncmd.services.files.SyntaxToken
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCircleButton
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideDisplay
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideToast
import com.bontecou.syncmd.ui.theme.TideToastStyle
import com.bontecou.syncmd.ui.theme.TideTopBar
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors
import com.bontecou.syncmd.ui.viewmodels.FileEditorMessage
import com.bontecou.syncmd.ui.viewmodels.FileEditorViewModel
import com.bontecou.syncmd.ui.viewmodels.FilesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ─── File browser (iOS FileBrowserView) ─────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(
    repositoryPath: String,
    directory: String,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val locale = LocalConfiguration.current.locales[0]
    val c = Tide.colors
    var newItem by remember { mutableStateOf<Boolean?>(null) }
    var renameItem by remember { mutableStateOf<RepositoryFile?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(repositoryPath, directory) { viewModel.open(repositoryPath, directory) }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) { viewModel.open(repositoryPath, directory) }
    Scaffold(
        modifier = Modifier.testTag("files-screen"),
        containerColor = c.paper,
        contentColor = c.ink,
        topBar = {
            TideTopBar(
                onBack = onNavigateBack,
                backLabel = stringResource(R.string.publication_back),
                backModifier = Modifier.testTag("files-back"),
                center = {
                    Text(
                        directory.substringAfterLast('/').ifEmpty { stringResource(R.string.publication_files) },
                        style = TideType.sans(17.sp, FontWeight.SemiBold),
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("files-title").semantics { heading() },
                    )
                },
                actions = {
                    Box {
                        TideCircleButton(
                            stringResource(R.string.ui_new_18fdd549), { menuOpen = true },
                            Modifier.testTag("files-new"), size = 48.dp, enabled = !state.isMutating,
                        ) { Text("+", style = TideType.sans(22.sp, FontWeight.Medium).copy(lineHeight = 22.sp), color = c.ink) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.ui_new_file_44dedd0f), style = TideType.body) }, onClick = { menuOpen = false; newItem = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ui_new_folder_cf28f49e), style = TideType.body) }, onClick = { menuOpen = false; newItem = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilePathLine(directory.ifEmpty { repositoryPath.substringAfterLast('/') }, "files-current-path")
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Tide.gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilesPill(stringResource(if (state.showHidden) R.string.files_hide_hidden else R.string.files_show_hidden),
                    { viewModel.toggleHidden() }, !state.isMutating, "files-hidden-toggle", selected = state.showHidden)
                FilesPill(stringResource(R.string.ui_refresh_0e916101),
                    { viewModel.refresh() }, !state.isLoading && !state.isMutating, "files-refresh")
            }
            Spacer(Modifier.height(10.dp))
            FilesProgress(state.isLoading || state.isMutating)
            state.error?.let { error -> FilesErrorCard(stringResource(error.resourceId)) }
            when {
                state.entries.isEmpty() && !state.isLoading && state.error == null -> FilesEmptyState()
                state.entries.isEmpty() && state.isLoading -> FilesLoadingState()
            }
            LazyColumn(
                Modifier.fillMaxSize().testTag("files-list"),
                contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 6.dp, bottom = 32.dp),
            ) {
                itemsIndexed(state.entries, key = { _, entry -> entry.path }) { index, entry ->
                    FileRow(
                        name = entry.name,
                        detail = listOfNotNull(
                            if (entry.isDirectory) stringResource(R.string.files_folder) else formatFileSize(entry.size, locale),
                            entry.gitStatus?.let { "Git: $it" },
                        ).joinToString(" · "),
                        isDirectory = entry.isDirectory,
                        enabled = !state.isMutating,
                        first = index == 0,
                        last = index == state.entries.lastIndex,
                        rowTag = "file-row-${entry.path}",
                        renameTag = "file-rename-${entry.path}",
                        onOpen = { if (entry.isDirectory) onOpenDirectory(entry.path) else onOpenFile(entry.path) },
                        onRename = { renameItem = entry },
                    )
                }
            }
        }
    }
    newItem?.let { folder ->
        FilenameDialog(
            title = stringResource(if (folder) R.string.ui_new_folder_cf28f49e else R.string.ui_new_file_44dedd0f),
            initialValue = if (folder) "" else "untitled.md",
            onDismiss = { newItem = null },
            onConfirm = { name -> newItem = null; viewModel.create(name, folder, onOpenFile) },
        )
    }
    renameItem?.let { entry ->
        FilenameDialog(stringResource(R.string.ui_rename_3064d79a), entry.name, { renameItem = null }, subject = entry.name) { name ->
            renameItem = null
            viewModel.rename(entry.path, name)
        }
    }
}

/** Outline pill that wraps rather than clipping long translations. */
@Composable
private fun FilesPill(title: String, onClick: () -> Unit, enabled: Boolean, tag: String, selected: Boolean = false) {
    val c = Tide.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(c.ink, CircleShape) else Modifier.border(1.dp, c.lineSoft, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TideType.sans(14.sp, FontWeight.Medium), color = (if (selected) c.paper else c.ink).copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
private fun FilesProgress(visible: Boolean) {
    val c = Tide.colors
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (visible) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.ink, trackColor = c.line)
    }
}

@Composable
private fun FilesErrorCard(message: String) {
    val c = Tide.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .padding(horizontal = Tide.gutter, vertical = 10.dp)
            .fillMaxWidth()
            .background(c.danger.copy(alpha = 0.10f), shape)
            .border(1.dp, c.danger.copy(alpha = 0.25f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("!", style = TideType.mono(15.sp, FontWeight.SemiBold), color = c.danger)
        Text(message, style = TideType.sans(14.sp, FontWeight.Medium), color = c.danger, modifier = Modifier.weight(1f).testTag("files-error"))
    }
}

@Composable
private fun FilesEmptyState() {
    val c = Tide.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(56.dp).background(c.well, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            FileGlyph(isDirectory = true, tint = c.inkMid, size = 24.dp)
        }
        TideDisplay(stringResource(R.string.ui_empty_folder_ca1fd454), size = 26.sp)
        Text(stringResource(R.string.ui_create_a_file_or_folder_using_new_655c5596), style = TideType.meta, color = c.inkFaint, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FilesLoadingState() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { TidePulse(color = Tide.colors.inkFaint) }
}

/** One row of the grouped file card; only the first and last rows round their corners. */
@Composable
private fun FileRow(
    name: String,
    detail: String,
    isDirectory: Boolean,
    enabled: Boolean,
    first: Boolean,
    last: Boolean,
    rowTag: String,
    renameTag: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
) {
    val c = Tide.colors
    val r = Tide.radiusCard
    val shape = RoundedCornerShape(topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onOpen)
                .testTag(rowTag)
                .heightIn(min = 60.dp)
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(34.dp).background(c.well, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                FileGlyph(isDirectory, if (isDirectory) c.ink else c.inkMid)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = TideType.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = TideType.mono(11.sp).let { if (isDirectory) it else it.copy(textDirection = TextDirection.Ltr) }, color = c.inkFaint)
            }
            Box(
                Modifier
                    .widthIn(max = 180.dp)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onRename)
                    .testTag(renameTag)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.ui_rename_3064d79a), style = TideType.sans(14.sp, FontWeight.Medium), color = c.inkMid.copy(alpha = if (enabled) 1f else 0.4f))
            }
        }
        if (!last) Box(Modifier.padding(start = 62.dp).fillMaxWidth().height(1.dp).background(c.line))
    }
}

/** Small line-drawn folder / document mark (decorative). */
@Composable
private fun FileGlyph(isDirectory: Boolean, tint: Color, size: androidx.compose.ui.unit.Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.09f)
        if (isDirectory) {
            val path = Path().apply {
                moveTo(w * 0.06f, h * 0.24f)
                lineTo(w * 0.38f, h * 0.24f)
                lineTo(w * 0.48f, h * 0.36f)
                lineTo(w * 0.94f, h * 0.36f)
                lineTo(w * 0.94f, h * 0.84f)
                lineTo(w * 0.06f, h * 0.84f)
                close()
            }
            drawPath(path, tint, style = stroke)
        } else {
            drawRoundRect(tint, Offset(w * 0.2f, h * 0.08f), Size(w * 0.6f, h * 0.84f), CornerRadius(w * 0.1f), style = stroke)
            drawLine(tint, Offset(w * 0.34f, h * 0.38f), Offset(w * 0.66f, h * 0.38f), w * 0.08f)
            drawLine(tint, Offset(w * 0.34f, h * 0.56f), Offset(w * 0.66f, h * 0.56f), w * 0.08f)
            drawLine(tint, Offset(w * 0.34f, h * 0.74f), Offset(w * 0.54f, h * 0.74f), w * 0.08f)
        }
    }
}

// ─── File editor (iOS FileEditorView + CodeEditorView) ──────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileEditorScreen(
    repositoryPath: String,
    filePath: String,
    onNavigateBack: () -> Unit,
    viewModel: FileEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val c = Tide.colors
    val locale = LocalConfiguration.current.locales[0]
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val document = state.document
    val filename = (document?.path ?: filePath).substringAfterLast('/')
    // Preserve a writing viewport in short windows; the complete path returns when the keyboard closes.
    // Read the keyboard's actual inset rather than WindowInsets.isImeVisible: after a rotation the
    // visibility flag can stay latched on once the IME has hidden, which would keep the path collapsed.
    val compactEditing = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0 &&
        LocalConfiguration.current.screenHeightDp < 480
    val leave = {
        // Hardware Back can arrive between an input event and recomposition.
        // Keep the handler installed and consult the authoritative current draft.
        val current = viewModel.state.value
        if (!current.isSaving) {
            if (current.isDirty) confirmLeave = true else onNavigateBack()
        }
    }
    BackHandler { leave() }
    LaunchedEffect(repositoryPath, filePath) { viewModel.open(repositoryPath, filePath) }
    LaunchedEffect(state.deleted) { if (state.deleted) onNavigateBack() }
    val message = state.message?.let { stringResource(when (it) {
        FileEditorMessage.SAVED -> R.string.file_saved
        FileEditorMessage.RENAMED -> R.string.file_renamed
    }) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it) } }
    Scaffold(
        modifier = Modifier.testTag("file-editor-screen").imePadding(),
        containerColor = c.card,
        contentColor = c.ink,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                TideToast(data.visuals.message, Modifier.padding(horizontal = Tide.gutter, vertical = 12.dp), style = TideToastStyle.SUCCESS)
            }
        },
        topBar = {
            EditorHeader(
                filename = filename,
                path = document?.path ?: filePath,
                compact = compactEditing,
                status = if (compactEditing) null else
                    (if (state.isDirty) stringResource(R.string.file_unsaved_changes) else document?.size?.let { formatFileSize(it, locale) }),
                dirty = state.isDirty,
                showSave = document?.content != null,
                saveEnabled = state.isDirty && !state.isSaving,
                backEnabled = !state.isSaving,
                moreEnabled = !state.isLoading && !state.isSaving,
                onBack = { leave() },
                onSave = { viewModel.save() },
                onMore = { menuOpen = true },
                menu = {
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_reload_from_disk_d4288839), style = TideType.body) }, onClick = { menuOpen = false; if (state.isDirty) confirmReload = true else viewModel.reload() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_rename_file_e3526ab6), style = TideType.body) }, enabled = document != null, onClick = { menuOpen = false; rename = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_delete_file_28e18dc9), style = TideType.body, color = if (document != null) c.danger else Color.Unspecified) }, enabled = document != null, onClick = { menuOpen = false; confirmDelete = true })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilesProgress(state.isLoading || state.isSaving)
            when {
                state.isLoading -> EditorLoading()
                document == null -> EditorUnavailable(onRetry = { viewModel.reload() })
                document.content == null -> EditorReadOnly(document.unavailableReason?.let { stringResource(it.resourceId) }.orEmpty())
                else -> HighlightedFileEditor(document.path, state.draft, !state.isSaving, viewModel::updateDraft, compact = compactEditing)
            }
        }
    }
    state.error?.let { error ->
        TideDialog(
            onDismissRequest = viewModel::clearError,
            icon = { TideModalIcon() },
            title = stringResource(R.string.ui_file_operation_failed_f78a328b),
            text = { Text(stringResource(error.resourceId), modifier = Modifier.testTag("file-error-reason")) },
            confirmButton = { BPrimaryButton(stringResource(R.string.ui_keep_editing_e76fd2ad), onClick = viewModel::clearError) },
        )
    }
    if (confirmLeave) {
        val leaveTitle = stringResource(R.string.file_save_title)
        TideDialog(
            onDismissRequest = { confirmLeave = false },
            modifier = Modifier.semantics { paneTitle = leaveTitle },
            icon = { TideModalIcon("•", Tide.colors.ahead) },
            content = {
                TideDisplay(stringResource(R.string.file_save_title), size = 26.sp, modifier = Modifier.testTag("file-unsaved-title"))
                Column(Modifier.padding(top = 8.dp).testTag("file-unsaved-body")) {
                    Text(stringResource(R.string.ui_your_edits_have_not_been_saved_625899a4), style = TideType.copy, color = c.inkMid)
                    SelectionContainer {
                        Text(filename, style = TideType.mono(13.sp), color = c.ink,
                            modifier = Modifier.padding(top = 8.dp).testTag("file-unsaved-name"))
                    }
                }
            },
            confirmButton = {
                BPrimaryButton(stringResource(R.string.publication_save_close), Modifier.testTag("file-leave-save"),
                    onClick = { confirmLeave = false; viewModel.save(onNavigateBack) })
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BGhostButton(stringResource(R.string.ui_keep_editing_e76fd2ad), Modifier.fillMaxWidth().testTag("file-leave-keep"), onClick = { confirmLeave = false })
                    BGhostButton(stringResource(R.string.ui_discard_eb1a70e3), Modifier.fillMaxWidth().testTag("file-leave-discard"), color = c.danger,
                        onClick = { confirmLeave = false; onNavigateBack() })
                }
            },
        )
    }
    if (confirmReload) {
        FileConfirmation(stringResource(R.string.file_reload_title), stringResource(R.string.file_reload_body), stringResource(R.string.background_reload), filename, { confirmReload = false }, destructive = false) {
            confirmReload = false
            viewModel.reload()
        }
    }
    if (confirmDelete) {
        FileConfirmation(stringResource(R.string.file_delete_title), stringResource(R.string.file_delete_body), stringResource(R.string.ui_delete_e2d0a549), filename, { confirmDelete = false }) {
            confirmDelete = false
            viewModel.delete()
        }
    }
    if (rename) FilenameDialog(stringResource(R.string.ui_rename_file_e3526ab6), filename, { rename = false }, subject = filename) { name -> rename = false; viewModel.rename(name) }
}

/** File name + mono location on the left, Save pill and more on the right (iOS editor header). */
@Composable
private fun EditorHeader(
    filename: String,
    path: String,
    compact: Boolean,
    status: String?,
    dirty: Boolean,
    showSave: Boolean,
    saveEnabled: Boolean,
    backEnabled: Boolean,
    moreEnabled: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onMore: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().background(c.card).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Tide.gutter, end = 10.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TideCircleButton(stringResource(R.string.publication_back), onBack, Modifier.testTag("file-editor-back"), size = 48.dp, filled = false, enabled = backEnabled) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(filename, style = TideType.sans(15.sp, FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("file-editor-title").semantics { heading() })
                if (!compact) FilePathLine(path, "file-editor-path", inline = true)
            }
            if (showSave) {
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .then(if (dirty) Modifier.background(if (saveEnabled) c.ink else c.ink.copy(alpha = 0.4f), CircleShape) else Modifier)
                        .clickable(enabled = saveEnabled, role = Role.Button, onClick = onSave)
                        .testTag("file-save")
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.ui_save_1509f561), style = TideType.sans(14.sp, FontWeight.SemiBold), color = if (dirty) c.paper else c.inkFaint)
                }
            }
            Box {
                TideCircleButton(stringResource(R.string.ui_more_d47d7cb0), onMore, Modifier.testTag("file-editor-more"), size = 48.dp, filled = false, enabled = moreEnabled) {
                    Text("•••", style = TideType.mono(13.sp, FontWeight.SemiBold), color = c.ink)
                }
                menu()
            }
        }
        if (status != null) {
            Row(
                Modifier.padding(start = Tide.gutter + 58.dp, end = Tide.gutter, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).background(if (dirty) c.ahead else c.inkFaint.copy(alpha = 0.5f), CircleShape))
                Text(status, style = TideType.mono(11.sp), color = if (dirty) c.ahead else c.inkFaint, modifier = Modifier.testTag("file-editor-status"))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

@Composable
private fun EditorLoading() {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TidePulse(color = c.inkFaint)
        Text(stringResource(R.string.ui_opening_file_de0df916), style = TideType.meta, color = c.inkFaint)
    }
}

@Composable
private fun EditorUnavailable(onRetry: () -> Unit) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(56.dp).background(c.well, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text("!", style = TideType.sans(22.sp, FontWeight.SemiBold), color = c.danger)
        }
        TideDisplay(stringResource(R.string.ui_unable_to_open_file_2787a028), size = 24.sp)
        Box(
            Modifier.heightIn(min = 48.dp).clip(CircleShape).border(1.dp, c.lineSoft, CircleShape)
                .clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.ui_retry_942087cc), style = TideType.sans(15.sp, FontWeight.SemiBold), color = c.ink) }
    }
}

/** Binary, non-UTF-8 or oversized files: a quiet card explaining why editing is off. */
@Composable
private fun EditorReadOnly(reason: String) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(56.dp).background(c.well, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            FileGlyph(isDirectory = false, tint = c.inkMid, size = 24.dp)
        }
        Text(reason, style = TideType.sans(15.sp), color = c.inkMid, textAlign = TextAlign.Center, modifier = Modifier.testTag("file-readonly-reason"))
    }
}

/** Retains the complete, selectable identity without displacing the file list or editor. */
@Composable
private fun FilePathLine(path: String, tag: String, inline: Boolean = false) {
    val scroll = remember(path) { androidx.compose.foundation.ScrollState(0) }
    SelectionContainer {
        Text(path, maxLines = 1,
            style = TideType.mono(if (inline) 11.sp else 12.sp).copy(textDirection = TextDirection.ContentOrLtr),
            color = Tide.colors.inkFaint,
            modifier = Modifier.fillMaxWidth().horizontalScroll(scroll)
                .then(if (inline) Modifier else Modifier.padding(horizontal = Tide.gutter + 4.dp, vertical = 8.dp)).testTag(tag))
    }
}

@Composable
private fun HighlightedFileEditor(path: String, content: String, enabled: Boolean, onChange: (String) -> Unit, compact: Boolean = false) {
    val c = Tide.colors
    var highlightedContent by remember { mutableStateOf("") }
    var tokens by remember { mutableStateOf<List<SyntaxToken>>(emptyList()) }
    LaunchedEffect(path, content) {
        delay(350)
        tokens = withContext(Dispatchers.Default) { SyntaxHighlighter.highlight(path, content) }
        highlightedContent = content
    }
    val transformation = remember(content, highlightedContent, tokens, c) {
        VisualTransformation { value ->
            val builder = AnnotatedString.Builder(value)
            if (value.text == highlightedContent) {
                tokens.forEach { token ->
                    val style = when (token.kind) {
                        SyntaxKind.COMMENT -> SpanStyle(color = c.inkFaint)
                        SyntaxKind.STRING -> SpanStyle(color = c.synced)
                        SyntaxKind.KEYWORD -> SpanStyle(color = c.behind, fontWeight = FontWeight.SemiBold)
                        SyntaxKind.NUMBER -> SpanStyle(color = c.ahead)
                        SyntaxKind.HEADING -> SpanStyle(color = c.ink, fontWeight = FontWeight.Bold)
                        SyntaxKind.TAG -> SpanStyle(color = c.behind)
                    }
                    builder.addStyle(style, token.start, token.end)
                }
            }
            TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        }
    }
    BasicTextField(
        value = content,
        onValueChange = onChange,
        readOnly = !enabled,
        visualTransformation = transformation,
        textStyle = TideType.mono(14.sp).copy(lineHeight = 22.sp, color = c.ink, textDirection = TextDirection.ContentOrLtr),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrect = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.ink),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = if (compact) 4.dp else 18.dp).testTag("file-editor-input"),
    )
}

// ─── Sheets ─────────────────────────────────────────────────────────────────

@Composable
private fun FilenameDialog(title: String, initialValue: String, onDismiss: () -> Unit, subject: String? = null, onConfirm: (String) -> Unit) {
    var name by remember(title, initialValue) { mutableStateOf(initialValue) }
    val c = Tide.colors
    TideDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { paneTitle = title },
        content = {
            TideDisplay(title, size = 26.sp, modifier = Modifier.testTag("filename-title"))
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.ui_name_dcd1d522)) },
                textStyle = TideType.mono(15.sp),
                shape = Tide.fieldShape,
                colors = tideFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("filename-input"),
            )
            subject?.let { original ->
                SelectionContainer {
                    Text(original, style = TideType.mono(12.sp).copy(textDirection = TextDirection.ContentOrLtr), color = c.inkFaint,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp).testTag("filename-original"))
                }
            }
        },
        confirmButton = { BPrimaryButton(stringResource(R.string.ui_confirm_eebdd24a), isDisabled = name.isBlank(), onClick = { onConfirm(name) }) },
        dismissButton = { BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth(), onClick = onDismiss) },
    )
}

@Composable
private fun FileConfirmation(title: String, message: String, action: String, subject: String, onDismiss: () -> Unit, destructive: Boolean = true, onConfirm: () -> Unit) {
    val c = Tide.colors
    TideDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { paneTitle = title },
        icon = { if (destructive) TideModalIcon() else TideModalIcon("↻", c.ink) },
        content = {
            TideDisplay(title, size = 26.sp, modifier = Modifier.testTag("file-confirm-title"))
            Text(message, style = TideType.copy, color = c.inkMid, modifier = Modifier.padding(top = 8.dp))
            SelectionContainer {
                Text(subject, style = TideType.mono(13.sp), color = c.ink, modifier = Modifier.padding(top = 10.dp).testTag("file-confirm-subject"))
            }
        },
        confirmButton = {
            if (destructive) BDestructiveButton(action, Modifier.testTag("file-confirm-accept"), onClick = onConfirm)
            else BPrimaryButton(action, Modifier.testTag("file-confirm-accept"), onClick = onConfirm)
        },
        dismissButton = { BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth().testTag("file-confirm-cancel"), onClick = onDismiss) },
    )
}

private fun formatFileSize(bytes: Long, locale: java.util.Locale): String = when {
    bytes < 1024 -> "${java.text.NumberFormat.getIntegerInstance(locale).format(bytes)} B"
    bytes < 1024 * 1024 -> String.format(locale, "%.1f KB", bytes / 1024.0)
    else -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun FilesPreviewFrame(dark: Boolean, content: @Composable ColumnScope.() -> Unit) {
    SyncMdTheme(darkTheme = dark) {
        Column(Modifier.fillMaxSize().background(Tide.colors.paper).padding(vertical = 12.dp), content = content)
    }
}

@Composable
private fun PreviewFileList(dark: Boolean) = FilesPreviewFrame(dark) {
    FilePathLine("notes", "preview-path")
    Column(Modifier.padding(horizontal = Tide.gutter)) {
        val rows = listOf(Triple("Daily", "Folder", true), Triple("Ideas", "Folder", true), Triple("notes.md", "1,204 B · Git: M", false), Triple("tide.md", "88 B · Git: ?", false))
        rows.forEachIndexed { i, (name, detail, dir) ->
            FileRow(name, detail, dir, true, i == 0, i == rows.lastIndex, "r$i", "n$i", {}, {})
        }
    }
}

@Preview(name = "Files · list", heightDp = 520) @Composable private fun PreviewFilesList() = PreviewFileList(false)
@Preview(name = "Files · list dark", heightDp = 520) @Composable private fun PreviewFilesListDark() = PreviewFileList(true)
@Preview(name = "Files · empty", heightDp = 420) @Composable private fun PreviewFilesEmpty() = FilesPreviewFrame(false) { FilesEmptyState() }
@Preview(name = "Files · loading", heightDp = 240) @Composable private fun PreviewFilesLoading() = FilesPreviewFrame(true) { FilesProgress(true); FilesLoadingState() }
@Preview(name = "Files · error", heightDp = 200) @Composable private fun PreviewFilesError() = FilesPreviewFrame(false) { FilesErrorCard("This folder is no longer available.") }

@Composable
private fun PreviewEditor(dark: Boolean, dirty: Boolean, saving: Boolean = false) {
    SyncMdTheme(darkTheme = dark) {
        Column(Modifier.fillMaxSize().background(Tide.colors.card)) {
            EditorHeader("notes.md", "Daily/notes.md", false, if (dirty) "Unsaved changes" else "1,204 B", dirty, true, dirty && !saving, !saving, !saving, {}, {}, {}, {})
            FilesProgress(saving)
            HighlightedFileEditor("notes.md", "# Notes\n\nA file editor with **syntax** highlighting.\n\n- Keep the cursor stable\n- Save to the working tree\n", !saving, {})
        }
    }
}

@Preview(name = "Editor · clean", heightDp = 520) @Composable private fun PreviewEditorClean() = PreviewEditor(false, false)
@Preview(name = "Editor · dirty", heightDp = 520) @Composable private fun PreviewEditorDirty() = PreviewEditor(false, true)
@Preview(name = "Editor · dirty dark", heightDp = 520) @Composable private fun PreviewEditorDirtyDark() = PreviewEditor(true, true)
@Preview(name = "Editor · saving", heightDp = 520) @Composable private fun PreviewEditorSaving() = PreviewEditor(true, true, saving = true)
@Preview(name = "Editor · read-only", heightDp = 420) @Composable private fun PreviewEditorReadOnly() = SyncMdTheme { Column(Modifier.background(Tide.colors.card)) { EditorReadOnly("This binary file cannot be edited as text.") } }
@Preview(name = "Editor · unavailable", heightDp = 420) @Composable private fun PreviewEditorUnavailable() = SyncMdTheme(darkTheme = true) { Column(Modifier.background(Tide.colors.card)) { EditorUnavailable {} } }
@Preview(name = "Editor · loading", heightDp = 300) @Composable private fun PreviewEditorLoading() = SyncMdTheme { Column(Modifier.background(Tide.colors.card)) { EditorLoading() } }
