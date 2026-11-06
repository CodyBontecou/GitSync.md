package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.localization.resourceId
import com.bontecou.syncmd.services.files.FileEntryInsight
import com.bontecou.syncmd.services.files.RepositoryFile
import com.bontecou.syncmd.services.files.SyntaxHighlighter
import com.bontecou.syncmd.services.files.SyntaxKind
import com.bontecou.syncmd.services.files.SyntaxToken
import com.bontecou.syncmd.ui.components.FileLeadingTile
import com.bontecou.syncmd.ui.components.FilesBreadcrumbs
import com.bontecou.syncmd.ui.components.FilesSyncStatus
import com.bontecou.syncmd.ui.components.LfsFetchButton
import com.bontecou.syncmd.ui.components.VaultTab
import com.bontecou.syncmd.ui.components.fileAgeLabel
import com.bontecou.syncmd.ui.components.fileSizeLabel
import com.bontecou.syncmd.ui.components.openFileInOtherApp
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3DropdownMenu
import com.bontecou.syncmd.ui.theme.M3Fab
import com.bontecou.syncmd.ui.theme.M3FilterChip
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3MenuItem
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3Segmented
import com.bontecou.syncmd.ui.theme.M3SnackbarHost
import com.bontecou.syncmd.ui.theme.M3Spinner
import com.bontecou.syncmd.ui.theme.M3Tag
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3TopBarTextAction
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.M3VaultNavigationBar
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.TideFonts
import com.bontecou.syncmd.ui.theme.m3GroupedItem
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.viewmodels.FileEditorMessage
import com.bontecou.syncmd.ui.viewmodels.FileEditorViewModel
import com.bontecou.syncmd.ui.viewmodels.FilesViewModel
import com.bontecou.syncmd.ui.viewmodels.NoteTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── Files (board 03) ───────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    repositoryPath: String,
    directory: String,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
    showNavigation: Boolean = false,
    onSelectTab: (VaultTab) -> Unit = {},
    /** The vault's display name for the top bar and the root breadcrumb. */
    vaultName: String? = null,
    /** Opens the commit log of a file or folder. */
    onOpenHistory: (String) -> Unit = {},
    /** The vault's real sync (fetch, then fast-forward); null in hosts without Hilt, which only refresh the list. */
    syncViewModel: com.bontecou.syncmd.ui.viewmodels.VaultSyncViewModel? = rememberVaultSyncViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(repositoryPath) { if (repositoryPath.isNotBlank()) syncViewModel?.setRepositoryPath(repositoryPath) }
    val syncRun = syncViewModel?.run?.collectAsState()?.value?.takeIf { it.path == repositoryPath }
    // Results are shown here only for a sync started from this tab; the Changes tab shows its own.
    var syncRequested by rememberSaveable(repositoryPath) { mutableStateOf(false) }
    LaunchedEffect(syncRun?.result) { if (syncRun?.result != null) viewModel.refresh() }
    val c = M3.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = vaultName?.takeIf { it.isNotBlank() } ?: repositoryPath.substringAfterLast('/')
    var creating by remember { mutableStateOf(false) }
    var renameItem by remember { mutableStateOf<RepositoryFile?>(null) }
    var deleteItem by remember { mutableStateOf<RepositoryFile?>(null) }
    var actionsFor by remember { mutableStateOf<String?>(null) }
    var overflow by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val openFailed = stringResource(R.string.files_m3_open_in_failed)
    val fetchFailed = stringResource(R.string.files_m3_fetch_failed)
    val fetchedText = state.fetched?.let { stringResource(R.string.files_m3_fetched, it) }
    LaunchedEffect(repositoryPath, directory) { viewModel.open(repositoryPath, directory) }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) { viewModel.open(repositoryPath, directory) }
    LaunchedEffect(state.fetchFailed) { if (state.fetchFailed) { snackbar.showSnackbar(fetchFailed); viewModel.clearError() } }
    LaunchedEffect(fetchedText) { fetchedText?.let { snackbar.showSnackbar(it); viewModel.clearFetched() } }
    val subtitle = listOfNotNull(state.header.branch, state.header.remoteSlug).joinToString(" · ").ifBlank { null }

    Scaffold(
        modifier = Modifier.testTag("files-screen"),
        containerColor = c.surface,
        contentColor = c.on,
        snackbarHost = { M3SnackbarHost(snackbar) },
        topBar = {
            M3TopBar(
                title = name,
                subtitle = subtitle,
                onBack = onNavigateBack,
                modifier = Modifier.testTag("files-title"),
                backTestTag = "files-back",
                actions = {
                    M3IconButton(R.drawable.ic_m3_sync, stringResource(R.string.files_m3_check_for_changes), {
                        if (syncViewModel != null) { syncRequested = true; syncViewModel.fetchAndFastForward() } else viewModel.refresh()
                    }, Modifier.testTag("files-refresh"), enabled = !state.isLoading && !state.isMutating && syncRun?.running != true)
                    Box {
                        M3IconButton(R.drawable.ic_m3_more_vert, stringResource(R.string.ui_more_d47d7cb0), { overflow = true }, Modifier.testTag("files-more"))
                        M3DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            M3MenuItem(stringResource(if (state.showHidden) R.string.files_hide_hidden else R.string.files_show_hidden),
                                { overflow = false; viewModel.toggleHidden() }, Modifier.testTag("files-hidden-toggle"),
                                icon = R.drawable.ic_m3_visibility, enabled = !state.isMutating)
                            M3MenuItem(stringResource(R.string.ui_refresh_0e916101), { overflow = false; viewModel.refresh() },
                                icon = R.drawable.ic_m3_refresh, enabled = !state.isLoading && !state.isMutating)
                        }
                    }
                },
            )
        },
        bottomBar = { if (showNavigation) M3VaultNavigationBar(VaultTab.FILES, onSelectTab, changes = state.changes) },
        floatingActionButton = {
            M3Fab(R.drawable.ic_m3_edit, stringResource(R.string.files_m3_new_file_cd), { if (!state.isMutating) creating = true },
                Modifier.testTag("files-new"))
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilesBreadcrumbs(name, directory, state.entries.size.takeIf { !state.isLoading || state.entries.isNotEmpty() },
                onOpen = { if (it != directory) onOpenDirectory(it) })
            FilesProgress(state.isLoading || state.isMutating)
            FilesSyncStatus(syncRun?.takeIf { it.running || syncRequested }, onCancel = { syncViewModel?.cancel() },
                onDismiss = { syncRequested = false; syncViewModel?.dismiss() })
            state.error?.let { error ->
                M3Note(stringResource(error.resourceId), Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("files-error"), tone = M3NoteTone.Warn)
            }
            when {
                state.entries.isEmpty() && !state.isLoading && state.error == null -> FilesEmptyState()
                state.entries.isEmpty() && state.isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { M3Spinner(size = 24.dp, color = c.onVar) }
            }
            LazyColumn(
                Modifier.fillMaxSize().testTag("files-list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
            ) {
                itemsIndexed(state.entries, key = { _, entry -> entry.path }) { index, entry ->
                    FileRow(
                        entry = entry,
                        insight = state.insights[entry.path],
                        index = index,
                        count = state.entries.size,
                        enabled = !state.isMutating,
                        menuOpen = actionsFor == entry.path,
                        fetching = entry.path in state.fetching,
                        onOpen = { if (entry.isDirectory) onOpenDirectory(entry.path) else onOpenFile(entry.path) },
                        onLongPress = { actionsFor = entry.path },
                        onDismissMenu = { actionsFor = null },
                        onFetch = { viewModel.fetch(entry.path) },
                        onOpenIn = {
                            actionsFor = null
                            scope.launch {
                                val ok = runCatching { openFileInOtherApp(context, repositoryPath, entry.path) }.getOrDefault(false)
                                if (!ok) snackbar.showSnackbar(openFailed)
                            }
                        },
                        onRename = { actionsFor = null; renameItem = entry },
                        onHistory = { actionsFor = null; onOpenHistory(entry.path) },
                        onDelete = { actionsFor = null; deleteItem = entry },
                    )
                }
                if (state.entries.isNotEmpty()) item(key = "footnote") {
                    Text(stringResource(R.string.files_m3_footnote), style = M3Type.caption, color = c.onVar,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp).testTag("files-footnote"))
                }
            }
        }
    }
    if (creating) {
        NewNoteDialog(
            location = (listOf(name) + directory.split('/').filter { it.isNotEmpty() }).joinToString("/") + "/",
            folderLabel = (directory.substringAfterLast('/').ifEmpty { name }) + "/",
            templates = state.templates,
            exists = viewModel::exists,
            onDismiss = { creating = false },
            onCreate = { file, folder, content ->
                creating = false
                viewModel.create(file, folder, content) { path -> if (folder) onOpenDirectory(path) else onOpenFile(path) }
            },
        )
    }
    renameItem?.let { entry ->
        FilenameDialog(stringResource(R.string.ui_rename_3064d79a), entry.name, { renameItem = null }, subject = entry.path,
            confirmLabel = stringResource(R.string.ui_rename_3064d79a)) { newName ->
            renameItem = null
            viewModel.rename(entry.path, newName)
        }
    }
    deleteItem?.let { entry ->
        FileConfirmation(
            title = stringResource(if (entry.isDirectory) R.string.files_m3_delete_folder_title else R.string.file_delete_title),
            message = stringResource(if (entry.isDirectory) R.string.files_m3_delete_folder_body else R.string.file_delete_body),
            action = stringResource(R.string.ui_delete_e2d0a549),
            subject = entry.path,
            onDismiss = { deleteItem = null },
        ) { deleteItem = null; viewModel.delete(entry) }
    }
}

@Composable
private fun FilesProgress(visible: Boolean) {
    val c = M3.colors
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (visible) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.primary, trackColor = c.cHighest)
    }
}

@Composable
private fun FilesEmptyState() {
    val c = M3.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).background(c.pc, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            M3Icon(R.drawable.ic_m3_folder, tint = c.onPc)
        }
        Text(stringResource(R.string.ui_empty_folder_ca1fd454), style = M3Type.headlineSmall, color = c.on)
        Text(stringResource(R.string.ui_create_a_file_or_folder_using_new_655c5596), style = M3Type.bodyMedium, color = c.onVar, textAlign = TextAlign.Center)
    }
}

/** One row of the grouped file card (board 03): tile, name over meta, M / LFS tags or Fetch; long-press for actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: RepositoryFile,
    insight: FileEntryInsight?,
    index: Int,
    count: Int,
    enabled: Boolean,
    menuOpen: Boolean,
    fetching: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onFetch: () -> Unit,
    onOpenIn: () -> Unit,
    onRename: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = M3.colors
    val pointer = insight?.lfsPointer
    val status = entry.gitStatus
    val meta = when {
        entry.isDirectory -> insight?.childCount?.let { pluralStringResource(R.plurals.files_m3_items, it, it) } ?: stringResource(R.string.files_folder)
        pointer != null -> stringResource(R.string.files_m3_lfs_pointer, fileSizeLabel(pointer.size))
        insight == null -> fileSizeLabel(entry.size)
        status in setOf("M", "S", "S+M") -> stringResource(R.string.files_m3_meta_edited, fileAgeLabel(insight.lastModified), fileSizeLabel(entry.size))
        else -> stringResource(R.string.files_m3_meta, fileAgeLabel(insight.lastModified), fileSizeLabel(entry.size))
    }
    val actionsLabel = stringResource(R.string.files_m3_actions_for, entry.name)
    Box(Modifier.fillMaxWidth().m3GroupedItem(index, count, c.cLow)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (menuOpen) Modifier.background(c.cHighest) else Modifier)
                .m3TopDivider(index > 0, c.outlineVar)
                .combinedClickable(enabled = enabled, role = Role.Button, onLongClickLabel = actionsLabel, onLongClick = onLongPress, onClick = onOpen)
                .testTag("file-row-${entry.path}")
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FileLeadingTile(entry.name, entry.isDirectory)
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = M3Type.body, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, style = M3Type.bodySmall.let { if (entry.isDirectory) it else it.copy(textDirection = TextDirection.ContentOrLtr) },
                    color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (status) {
                "M", "S", "S+M" -> M3Tag(stringResource(R.string.files_m3_tag_modified), tone = M3Tone.Ahead)
                "?", "A" -> M3Tag(stringResource(R.string.files_m3_tag_added), tone = M3Tone.Ok)
                "!" -> M3Tag(stringResource(R.string.files_m3_tag_conflict), tone = M3Tone.Warn)
            }
            if (pointer != null) LfsFetchButton(entry.name, fetching, enabled, onFetch, Modifier.testTag("file-fetch-${entry.path}"))
            else if (insight?.lfsTracked == true) M3Tag(stringResource(R.string.files_m3_tag_lfs))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(end = 12.dp)) {
            M3DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu, modifier = Modifier.semantics { contentDescription = actionsLabel }) {
                if (!entry.isDirectory) M3MenuItem(stringResource(R.string.files_m3_open_in), onOpenIn, Modifier.testTag("file-action-open-in"), icon = R.drawable.ic_m3_open_in_new)
                M3MenuItem(stringResource(R.string.files_m3_rename), onRename, Modifier.testTag("file-action-rename"), icon = R.drawable.ic_m3_edit)
                M3MenuItem(stringResource(R.string.files_m3_history), onHistory, Modifier.testTag("file-action-history"), icon = R.drawable.ic_m3_history)
                if (!entry.isDirectory || insight?.childCount == 0) M3MenuItem(stringResource(R.string.files_m3_delete), onDelete,
                    Modifier.testTag("file-action-delete"), icon = R.drawable.ic_m3_delete, destructive = true)
            }
        }
    }
}

// ─── New note (board 20) ────────────────────────────────────────────────────

private enum class NoteStart { BLANK, TEMPLATE, TODAY }

/** Built-in "idea" template, used when the vault has no templates/ folder. */
private const val BUILT_IN_IDEA = "# {{title}}\n\n## The idea\n\n\n## Why it matters\n\n\n## Next steps\n- [ ] \n"

/** Fills Obsidian-style {{title}}, {{date}} and {{time}} placeholders. */
internal fun fillTemplate(template: String, title: String, now: java.util.Date = java.util.Date()): String = template
    .replace("{{title}}", title)
    .replace("{{date}}", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(now))
    .replace("{{time}}", java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(now))

/** The base name the user typed, without a trailing ".md". */
internal fun noteBaseName(input: String): String = input.trim().let { if (it.endsWith(".md", ignoreCase = true)) it.dropLast(3) else it }

@Composable
private fun NewNoteDialog(
    location: String,
    folderLabel: String,
    templates: List<NoteTemplate>,
    exists: (String) -> Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, folder: Boolean, content: String) -> Unit,
) {
    val c = M3.colors
    val locale = LocalConfiguration.current.locales[0]
    var folder by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(NoteStart.BLANK) }
    var templateIndex by rememberSaveable { mutableStateOf(0) }
    val builtInName = stringResource(R.string.newfile_m3_builtin_idea)
    val choices = templates.ifEmpty { listOf(NoteTemplate(builtInName, BUILT_IN_IDEA, builtIn = true)) }
    val base = if (folder) input.trim() else noteBaseName(input)
    val fileName = if (folder) base else "$base.md"
    val invalid = base.isNotEmpty() && (base.any { it == '/' || it == '\\' } || base == "." || base == "..")
    val taken = base.isNotEmpty() && !invalid && exists(fileName)
    val error = when {
        invalid -> stringResource(R.string.newfile_m3_invalid)
        taken -> stringResource(R.string.newfile_m3_exists, fileName, folderLabel)
        else -> null
    }
    val title = stringResource(if (folder) R.string.newfile_m3_title_folder else R.string.newfile_m3_title_note)
    M3Dialog(
        onDismissRequest = onDismiss,
        title = title,
        modifier = Modifier.testTag("filename-dialog"),
        content = {
            val where = stringResource(R.string.newfile_m3_in, location)
            Text(buildAnnotatedString {
                val at = where.indexOf(location)
                if (at < 0) append(where) else {
                    append(where.substring(0, at))
                    withStyle(SpanStyle(fontFamily = TideFonts.Mono)) { append(location) }
                    append(where.substring(at + location.length))
                }
            }, style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.offset(y = (-12).dp).testTag("filename-original"))
            M3Segmented(
                listOf(stringResource(R.string.newfile_m3_note), stringResource(R.string.newfile_m3_folder)),
                selectedIndex = if (folder) 1 else 0,
                onSelect = { folder = it == 1 },
                role = Role.RadioButton,
                modifier = Modifier.testTag("filename-kind"),
            )
            M3TextField(
                value = input,
                onValueChange = { input = it },
                label = stringResource(if (folder) R.string.newfile_m3_folder_name else R.string.newfile_m3_file_name),
                suffix = if (folder) null else ".md",
                isError = error != null,
                supportingText = error,
                modifier = Modifier.padding(top = 24.dp).testTag("filename-input"),
            )
            if (!folder) {
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3FilterChip(stringResource(R.string.newfile_m3_blank), start == NoteStart.BLANK, { start = NoteStart.BLANK }, compact = true,
                        role = Role.RadioButton, modifier = Modifier.testTag("newfile-blank"))
                    choices.forEachIndexed { i, template ->
                        M3FilterChip(stringResource(R.string.newfile_m3_template, template.name), start == NoteStart.TEMPLATE && templateIndex == i,
                            { start = NoteStart.TEMPLATE; templateIndex = i }, compact = true, role = Role.RadioButton,
                            modifier = Modifier.testTag("newfile-template-$i"))
                    }
                    M3FilterChip(stringResource(R.string.newfile_m3_today), start == NoteStart.TODAY, {
                        start = NoteStart.TODAY
                        input = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
                    }, compact = true, role = Role.RadioButton, modifier = Modifier.testTag("newfile-today"))
                }
            }
        },
    ) {
        M3DialogAction(stringResource(R.string.publication_cancel), onDismiss, Modifier.testTag("filename-cancel"))
        M3DialogAction(stringResource(R.string.newfile_m3_create_open), {
            val content = when {
                folder -> ""
                start == NoteStart.TEMPLATE -> fillTemplate(choices[templateIndex.coerceIn(0, choices.lastIndex)].content, base)
                start == NoteStart.TODAY -> "# " + java.text.SimpleDateFormat("EEEE, MMM d", locale).format(java.util.Date()) + "\n\n"
                else -> ""
            }
            onCreate(fileName, folder, content)
        }, Modifier.testTag("filename-confirm"), enabled = base.isNotEmpty() && error == null)
    }
}

// ─── Editor (board 06) ──────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileEditorScreen(
    repositoryPath: String,
    filePath: String,
    onNavigateBack: () -> Unit,
    viewModel: FileEditorViewModel = hiltViewModel(),
    /** The vault's display name for the "Vault / folder" subtitle and "Vault ↑N". */
    vaultName: String? = null,
) {
    val state by viewModel.state.collectAsState()
    val c = M3.colors
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val document = state.document
    val currentPath = document?.path ?: filePath
    val filename = currentPath.substringAfterLast('/')
    val vault = vaultName?.takeIf { it.isNotBlank() } ?: repositoryPath.substringAfterLast('/')
    val folder = currentPath.substringBeforeLast('/', "")
    // Preserve a writing viewport in short windows; the subtitle returns when the keyboard closes.
    // Read the keyboard's actual inset rather than WindowInsets.isImeVisible: after a rotation the
    // visibility flag can stay latched on once the IME has hidden.
    val compactEditing = WindowInsets.ime.getBottom(LocalDensity.current) > 0 && LocalConfiguration.current.screenHeightDp < 480
    val leave = {
        // Hardware Back can arrive between an input event and recomposition; read the current state.
        val current = viewModel.state.value
        when {
            current.isSaving -> Unit
            current.isDirty && current.saveFailed -> confirmLeave = true
            current.isDirty -> viewModel.save(onNavigateBack)
            else -> onNavigateBack()
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
        containerColor = c.surface,
        contentColor = c.on,
        snackbarHost = { M3SnackbarHost(snackbar) },
        topBar = {
            M3TopBar(
                title = filename,
                subtitle = if (compactEditing) null else if (folder.isEmpty()) vault else stringResource(R.string.editor_m3_subtitle, vault, folder),
                onBack = { leave() },
                modifier = Modifier.testTag("file-editor-title"),
                titleTestTag = "file-editor-name",
                backTestTag = "file-editor-back",
                actions = {
                    M3TopBarTextAction(stringResource(R.string.editor_m3_commit), {
                        if (viewModel.state.value.isDirty) viewModel.save { committing = true } else committing = true
                    }, Modifier.testTag("file-editor-commit"), enabled = document != null && !state.isSaving && !state.isLoading)
                    Box {
                        M3IconButton(R.drawable.ic_m3_more_vert, stringResource(R.string.ui_more_d47d7cb0), { menuOpen = true },
                            Modifier.testTag("file-editor-more"), enabled = !state.isLoading && !state.isSaving)
                        M3DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (state.isDirty) M3MenuItem(stringResource(R.string.editor_m3_save_now), { menuOpen = false; viewModel.save() },
                                Modifier.testTag("file-save"), icon = R.drawable.ic_m3_check)
                            M3MenuItem(stringResource(R.string.ui_reload_from_disk_d4288839), { menuOpen = false; if (state.isDirty) confirmReload = true else viewModel.reload() },
                                icon = R.drawable.ic_m3_refresh)
                            M3MenuItem(stringResource(R.string.ui_rename_file_e3526ab6), { menuOpen = false; rename = true }, icon = R.drawable.ic_m3_edit, enabled = document != null)
                            M3MenuItem(stringResource(R.string.ui_delete_file_28e18dc9), { menuOpen = false; confirmDelete = true }, icon = R.drawable.ic_m3_delete,
                                destructive = true, enabled = document != null)
                        }
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
                else -> {
                    val markdown = isMarkdownPath(document.path)
                    var preview by rememberSaveable(document.path) { mutableStateOf(false) }
                    val field = remember(document.path) { mutableStateOf(TextFieldValue(state.draft)) }
                    if (markdown && !compactEditing) {
                        M3Segmented(
                            listOf(stringResource(R.string.m3_editor_write), stringResource(R.string.m3_editor_preview)),
                            selectedIndex = if (preview) 1 else 0,
                            onSelect = { preview = it == 1 },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("editor-mode"),
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (markdown && preview) MarkdownPreview(state.draft, Modifier.testTag("editor-preview"))
                        else LineNumberedEditor(document.path, state.draft, !state.isSaving, viewModel::updateDraft, state.changedLines,
                            compact = compactEditing, field = field)
                    }
                    EditorFooter(
                        statusText = when {
                            state.saveFailed && state.isDirty -> stringResource(R.string.editor_m3_not_saved)
                            state.isDirty || state.autosaving || state.isSaving -> stringResource(R.string.editor_m3_saving)
                            state.linesChanged > 0 -> stringResource(R.string.editor_m3_saved_changed,
                                pluralStringResource(R.plurals.editor_m3_lines_changed, state.linesChanged, state.linesChanged))
                            else -> stringResource(R.string.editor_m3_saved)
                        },
                        dot = when {
                            state.saveFailed && state.isDirty -> c.error
                            state.isDirty || state.autosaving -> c.ahead
                            else -> c.ok
                        },
                        vault = state.toPush?.let { stringResource(R.string.editor_m3_vault_ahead, vault, it) },
                        toolbar = markdown && !preview && !state.isSaving,
                        onInsert = { transform ->
                            val updated = transform(field.value)
                            field.value = updated
                            viewModel.updateDraft(updated.text)
                        },
                    )
                }
            }
        }
    }
    state.error?.let { error ->
        M3Dialog(
            onDismissRequest = viewModel::clearError,
            title = stringResource(R.string.ui_file_operation_failed_f78a328b),
            icon = R.drawable.ic_m3_warning,
            iconTint = c.error,
            content = { Text(stringResource(error.resourceId), modifier = Modifier.testTag("file-error-reason")) },
        ) { M3DialogAction(stringResource(R.string.ui_keep_editing_e76fd2ad), viewModel::clearError) }
    }
    if (confirmLeave) {
        M3Dialog(
            onDismissRequest = { confirmLeave = false },
            title = stringResource(R.string.file_save_title),
            modifier = Modifier.testTag("file-unsaved-title"),
            stackedActions = true,
            content = {
                Column(Modifier.testTag("file-unsaved-body")) {
                    Text(stringResource(R.string.ui_your_edits_have_not_been_saved_625899a4))
                    SelectionContainer { Text(filename, style = M3Type.code, color = c.on, modifier = Modifier.padding(top = 8.dp).testTag("file-unsaved-name")) }
                }
            },
        ) {
            M3DialogAction(stringResource(R.string.publication_save_close), { confirmLeave = false; viewModel.save(onNavigateBack) },
                Modifier.fillMaxWidth().testTag("file-leave-save"), filled = true)
            M3DialogAction(stringResource(R.string.ui_keep_editing_e76fd2ad), { confirmLeave = false }, Modifier.fillMaxWidth().testTag("file-leave-keep"))
            M3DialogAction(stringResource(R.string.ui_discard_eb1a70e3), { confirmLeave = false; onNavigateBack() },
                Modifier.fillMaxWidth().testTag("file-leave-discard"), destructive = true)
        }
    }
    if (confirmReload) {
        FileConfirmation(stringResource(R.string.file_reload_title), stringResource(R.string.file_reload_body), stringResource(R.string.background_reload),
            filename, { confirmReload = false }, destructive = false) {
            confirmReload = false
            viewModel.reload()
        }
    }
    if (confirmDelete) {
        FileConfirmation(stringResource(R.string.file_delete_title), stringResource(R.string.file_delete_body), stringResource(R.string.ui_delete_e2d0a549),
            filename, { confirmDelete = false }) {
            confirmDelete = false
            viewModel.delete()
        }
    }
    if (rename) FilenameDialog(stringResource(R.string.ui_rename_file_e3526ab6), filename, { rename = false }, subject = currentPath,
        confirmLabel = stringResource(R.string.ui_rename_3064d79a)) { name -> rename = false; viewModel.rename(name) }
    if (committing) {
        com.bontecou.syncmd.ui.components.CommitSheet(repositoryPath, vault, onDismiss = { committing = false; viewModel.refreshLocalState() })
    }
}

/** Status strip ("Saved · 3 lines changed" … "VoxVault ↑3") over the markdown keys, on the c container. */
@Composable
private fun EditorFooter(statusText: String, dot: Color, vault: String?, toolbar: Boolean, onInsert: ((TextFieldValue) -> TextFieldValue) -> Unit) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().background(c.c).navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(dot, RoundedCornerShape(4.dp)))
                Text(statusText, style = M3Type.caption, color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("file-editor-status"))
            }
            if (vault != null) Text(vault, style = M3Type.meta, color = c.onVar, maxLines = 1, modifier = Modifier.padding(start = 8.dp).testTag("file-editor-vault"))
        }
        if (toolbar) MarkdownToolbar(onInsert)
    }
}

@Composable
private fun EditorLoading() {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        M3Spinner(size = 24.dp, color = c.onVar)
        Text(stringResource(R.string.ui_opening_file_de0df916), style = M3Type.bodyMedium, color = c.onVar)
    }
}

@Composable
private fun EditorUnavailable(onRetry: () -> Unit) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(56.dp).background(c.ec, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            M3Icon(R.drawable.ic_m3_error, tint = c.onEc)
        }
        Text(stringResource(R.string.ui_unable_to_open_file_2787a028), style = M3Type.headlineSmall, color = c.on, textAlign = TextAlign.Center)
        com.bontecou.syncmd.ui.theme.M3OutlinedButton(stringResource(R.string.ui_retry_942087cc), onRetry)
    }
}

/** Binary, non-UTF-8 or oversized files: a quiet card explaining why editing is off. */
@Composable
private fun EditorReadOnly(reason: String) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(56.dp).background(c.cHigh, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            M3Icon(R.drawable.ic_m3_file, tint = c.onVar)
        }
        Text(reason, style = M3Type.bodyMedium.copy(fontSize = 15.sp), color = c.onVar, textAlign = TextAlign.Center, modifier = Modifier.testTag("file-readonly-reason"))
    }
}

/** Start offsets of each logical line. */
private fun lineStarts(text: String): IntArray {
    val starts = ArrayList<Int>(text.length / 24 + 1)
    starts += 0
    text.forEachIndexed { i, ch -> if (ch == '\n') starts += i + 1 }
    return starts.toIntArray()
}

/**
 * The editor body (board 06): a 36dp line-number gutter, mono 14/26 text, lines changed since the
 * last commit tinted with an addInk edge, and the cursor's line marked in primary.
 */
@Composable
internal fun LineNumberedEditor(
    path: String,
    content: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    changedLines: Set<Int>,
    compact: Boolean = false,
    field: MutableState<TextFieldValue> = remember(path) { mutableStateOf(TextFieldValue(content)) },
) {
    val c = M3.colors
    // The draft lives in the view model; keep the field (text + selection) in step with it so the
    // markdown keys can insert at the cursor.
    if (field.value.text != content) {
        val end = content.length
        field.value = field.value.copy(text = content,
            selection = TextRange(field.value.selection.start.coerceAtMost(end), field.value.selection.end.coerceAtMost(end)))
    }
    var highlightedContent by remember { mutableStateOf("") }
    var tokens by remember { mutableStateOf<List<SyntaxToken>>(emptyList()) }
    LaunchedEffect(path, content) {
        delay(350)
        tokens = withContext(Dispatchers.Default) { SyntaxHighlighter.highlight(path, content) }
        highlightedContent = content
    }
    val markdown = isMarkdownPath(path)
    val transformation = remember(content, highlightedContent, tokens, c) {
        VisualTransformation { value ->
            val builder = AnnotatedString.Builder(value)
            if (value.text == highlightedContent) {
                tokens.forEach { token ->
                    val style = when (token.kind) {
                        SyntaxKind.COMMENT -> SpanStyle(color = c.onVar)
                        SyntaxKind.STRING -> SpanStyle(color = c.ok)
                        SyntaxKind.KEYWORD -> SpanStyle(color = c.behind, fontWeight = FontWeight.SemiBold)
                        SyntaxKind.NUMBER -> SpanStyle(color = c.ahead)
                        SyntaxKind.HEADING -> if (value.text.getOrNull(token.start) == '#') SpanStyle(color = c.primary, fontWeight = FontWeight.SemiBold)
                            else SpanStyle(fontWeight = FontWeight.SemiBold)
                        SyntaxKind.TAG -> SpanStyle(color = c.behind)
                    }
                    builder.addStyle(style, token.start, token.end)
                }
            }
            if (markdown) Regex("\\[\\[[^\\]\\n]+]]").findAll(value.text).forEach { builder.addStyle(SpanStyle(color = c.behind), it.range.first, it.range.last + 1) }
            TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        }
    }
    val starts = remember(content) { lineStarts(content) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val measurer = rememberTextMeasurer()
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val gutter = 36.dp
    val topPad = if (compact) 4.dp else 16.dp
    val numberStyle = M3Type.metaSmall.copy(fontSize = 12.sp, color = c.onVar, textAlign = TextAlign.End)
    val cursorLine = remember(field.value.selection.start, starts) {
        val offset = field.value.selection.start
        var lo = 0; var hi = starts.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (starts[mid] <= offset) lo = mid else hi = mid - 1 }
        lo
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it.height }.verticalScroll(scroll)) {
        BasicTextField(
            value = field.value,
            onValueChange = { value -> val changed = value.text != field.value.text; field.value = value; if (changed) onChange(value.text) },
            readOnly = !enabled,
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            textStyle = M3Type.code.copy(fontSize = 14.sp, lineHeight = 26.sp, color = c.on, textDirection = TextDirection.ContentOrLtr),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrect = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = with(density) { viewport.toDp() })
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    val top = topPad.toPx()
                    val gutterPx = gutter.toPx()
                    val visibleTop = scroll.value - top
                    val visibleBottom = scroll.value + viewport.toFloat()
                    val textLength = result.layoutInput.text.length
                    for (i in starts.indices) {
                        val start = starts[i].coerceAtMost(textLength)
                        val lineTop = result.getLineTop(result.getLineForOffset(start))
                        if (lineTop > visibleBottom) break
                        val endOffset = ((if (i + 1 < starts.size) starts[i + 1] - 1 else textLength)).coerceIn(start, textLength)
                        val lineBottom = result.getLineBottom(result.getLineForOffset(endOffset))
                        if (lineBottom < visibleTop) continue
                        val y = top + lineTop
                        val height = lineBottom - lineTop
                        if ((i + 1) in changedLines) {
                            drawRect(c.addBg, Offset(0f, y), Size(size.width, height))
                            drawRect(c.addInk, Offset(0f, y), Size(3.dp.toPx(), height))
                        }
                        if (i == cursorLine && enabled) drawRect(c.primary, Offset(0f, y), Size(2.dp.toPx(), height))
                        val number = measurer.measure((i + 1).toString(), numberStyle)
                        // Every line shares the line height, so the first line's baseline offset holds for all.
                        val baseline = lineTop + (result.firstBaseline - result.getLineTop(0))
                        drawText(number, topLeft = Offset(gutterPx - 14.dp.toPx() - number.size.width.toFloat(), top + baseline - number.firstBaseline))
                    }
                }
                .testTag("file-editor-input"),
            decorationBox = { inner ->
                Box(Modifier.padding(start = gutter, end = 16.dp, top = topPad, bottom = 24.dp)) { inner() }
            },
        )
    }
}

internal fun isMarkdownPath(path: String) = path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown", "mkd")

/** Wraps the selection (or inserts at the cursor) with [prefix]/[suffix]; [linePrefix] goes at the start of the line. */
internal fun insertMarkdown(value: TextFieldValue, prefix: String, suffix: String, linePrefix: Boolean): TextFieldValue {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max
    return if (linePrefix) {
        val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        val updated = text.substring(0, lineStart) + prefix + text.substring(lineStart)
        value.copy(text = updated, selection = TextRange(start + prefix.length, end + prefix.length))
    } else {
        val updated = text.substring(0, start) + prefix + text.substring(start, end) + suffix + text.substring(end)
        value.copy(text = updated, selection = TextRange(start + prefix.length, end + prefix.length))
    }
}

/** ⇥: indents every line the selection touches by two spaces (a list item becomes a sub-item). */
internal fun indentLines(value: TextFieldValue, indent: String = "  "): TextFieldValue {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max
    val firstLine = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
    // A selection that ends right after a newline doesn't take the following line.
    val lineStarts = mutableListOf(firstLine)
    var newline = text.indexOf('\n', firstLine)
    while (newline >= 0 && newline + 1 < end) { lineStarts += newline + 1; newline = text.indexOf('\n', newline + 1) }
    val updated = StringBuilder(text)
    for (lineStart in lineStarts.asReversed()) updated.insert(lineStart, indent)
    return value.copy(text = updated.toString(), selection = TextRange(start + indent.length, end + indent.length * lineStarts.size))
}

/** Markdown keys above the keyboard (board 06), in the design's order: # [ ] [[ ]] B ` ⇥. */
@Composable
private fun MarkdownToolbar(onInsert: ((TextFieldValue) -> TextFieldValue) -> Unit) {
    val c = M3.colors
    val keys: List<Triple<String, Int, (TextFieldValue) -> TextFieldValue>> = listOf(
        Triple("#", R.string.editor_m3_md_heading) { v -> insertMarkdown(v, "# ", "", true) },
        Triple("[ ]", R.string.editor_m3_md_task) { v -> insertMarkdown(v, "- [ ] ", "", true) },
        Triple("[[ ]]", R.string.editor_m3_md_link) { v -> insertMarkdown(v, "[[", "]]", false) },
        Triple("B", R.string.editor_m3_md_bold) { v -> insertMarkdown(v, "**", "**", false) },
        Triple("`", R.string.editor_m3_md_code) { v -> insertMarkdown(v, "`", "`", false) },
        Triple("⇥", R.string.editor_m3_md_indent) { v -> indentLines(v) },
    )
    val label = stringResource(R.string.editor_m3_markdown)
    Row(
        Modifier.fillMaxWidth().m3TopDivider(true, c.outlineVar).heightIn(min = 56.dp).padding(horizontal = 8.dp)
            .horizontalScroll(rememberScrollState()).semantics { contentDescription = label }.testTag("editor-markdown-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { (glyph, labelRes, action) ->
            val description = stringResource(labelRes)
            Box(
                Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button) { onInsert(action) }
                    .semantics { contentDescription = description }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(glyph, style = M3Type.code.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = c.on) }
        }
    }
}

/**
 * Read-only markdown preview: headings, checklists, bullets, quotes, code and inline emphasis.
 * [changedLines] (1-based) get the diff's addBg tint with an addInk edge (Diff · Preview).
 */
@Composable
internal fun MarkdownPreview(markdown: String, modifier: Modifier = Modifier, changedLines: Set<Int> = emptySet()) {
    val c = M3.colors
    var inCode = false
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        markdown.lines().forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            val marked = (index + 1) in changedLines
            val mark = if (marked) Modifier.fillMaxWidth().background(c.addBg, RoundedCornerShape(4.dp))
                .drawBehind { drawRect(c.addInk, Offset.Zero, Size(3.dp.toPx(), size.height)) }.padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)
                .testTag("preview-changed-${index + 1}") else Modifier
            when {
                line.trimStart().startsWith("```") -> { inCode = !inCode; if (marked) Box(mark.height(4.dp)) }
                inCode -> Text(line, style = M3Type.code, color = c.on,
                    modifier = Modifier.fillMaxWidth().background(c.cHighest).then(mark).padding(horizontal = 10.dp, vertical = 2.dp))
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val size = when (level) { 1 -> 26.sp; 2 -> 22.sp; 3 -> 18.sp; else -> 16.sp }
                    Text(inlineMarkdown(line.drop(level).trim()), style = M3Type.body.copy(fontSize = size, lineHeight = size * 1.3f, fontWeight = FontWeight.SemiBold), color = c.on,
                        modifier = Modifier.padding(top = 8.dp).then(mark).semantics { heading() })
                }
                Regex("^\\s*[-*] \\[( |x|X)] ").containsMatchIn(line) -> {
                    val done = Regex("\\[(x|X)]").containsMatchIn(line.substringBefore("]") + "]")
                    Row(mark, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                            .then(if (done) Modifier.background(c.primary) else Modifier.border(2.dp, c.onVar, RoundedCornerShape(4.dp))),
                            contentAlignment = Alignment.Center) {
                            if (done) M3Icon(R.drawable.ic_m3_check, tint = c.onPrimary, size = 14.dp)
                        }
                        Text(inlineMarkdown(line.substringAfter("] ")), style = M3Type.body, color = if (done) c.onVar else c.on)
                    }
                }
                Regex("^\\s*[-*+] ").containsMatchIn(line) -> Row(mark.padding(start = (line.takeWhile { it == ' ' }.length / 2 * 16).dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("•", style = M3Type.body, color = c.onVar)
                    Text(inlineMarkdown(line.trimStart().drop(2)), style = M3Type.body, color = c.on)
                }
                line.startsWith(">") -> Row(mark.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.outlineVar))
                    Text(inlineMarkdown(line.drop(1).trim()), style = M3Type.body, color = c.onVar, modifier = Modifier.padding(start = 12.dp))
                }
                else -> Text(inlineMarkdown(line), style = M3Type.body, color = c.on, modifier = mark)
            }
        }
    }
}

/** **bold**, _italic_ / *italic*, `code` and [[wiki links]] as styled spans. */
@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val c = M3.colors
    val pattern = Regex("\\*\\*(.+?)\\*\\*|`(.+?)`|\\[\\[(.+?)]]|(?<![\\w*])[_*](.+?)[_*](?![\\w*])")
    val builder = AnnotatedString.Builder()
    var index = 0
    pattern.findAll(text).forEach { match ->
        builder.append(text.substring(index, match.range.first))
        val (value, style) = when {
            match.groups[1] != null -> match.groupValues[1] to SpanStyle(fontWeight = FontWeight.SemiBold)
            match.groups[2] != null -> match.groupValues[2] to SpanStyle(fontFamily = TideFonts.Mono, background = c.cHighest)
            match.groups[3] != null -> match.groupValues[3] to SpanStyle(color = c.behind)
            else -> match.groupValues[4] to SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        }
        builder.pushStyle(style); builder.append(value); builder.pop()
        index = match.range.last + 1
    }
    builder.append(text.substring(index))
    return builder.toAnnotatedString()
}

// ─── Dialogs ────────────────────────────────────────────────────────────────

@Composable
internal fun FilenameDialog(title: String, initialValue: String, onDismiss: () -> Unit, subject: String? = null,
    confirmLabel: String = stringResource(R.string.ui_confirm_eebdd24a), onConfirm: (String) -> Unit) {
    var name by remember(title, initialValue) { mutableStateOf(initialValue) }
    val c = M3.colors
    M3Dialog(
        onDismissRequest = onDismiss,
        title = title,
        modifier = Modifier.testTag("filename-title"),
        content = {
            M3TextField(name, { name = it }, stringResource(R.string.files_m3_rename_field), mono = true, modifier = Modifier.testTag("filename-input"))
            subject?.let { original ->
                SelectionContainer {
                    Text(original, style = M3Type.meta.copy(textDirection = TextDirection.ContentOrLtr), color = c.onVar,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp).testTag("filename-original"))
                }
            }
        },
    ) {
        M3DialogAction(stringResource(R.string.publication_cancel), onDismiss, Modifier.testTag("filename-cancel"))
        M3DialogAction(confirmLabel, { onConfirm(name.trim()) }, Modifier.testTag("filename-confirm"), enabled = name.isNotBlank() && name.trim() != initialValue)
    }
}

@Composable
private fun FileConfirmation(title: String, message: String, action: String, subject: String, onDismiss: () -> Unit, destructive: Boolean = true, onConfirm: () -> Unit) {
    val c = M3.colors
    M3Dialog(
        onDismissRequest = onDismiss,
        title = title,
        modifier = Modifier.testTag("file-confirm-title"),
        text = message,
        content = {
            SelectionContainer { Text(subject, style = M3Type.code, color = c.on, modifier = Modifier.testTag("file-confirm-subject")) }
        },
    ) {
        M3DialogAction(stringResource(R.string.publication_cancel), onDismiss, Modifier.testTag("file-confirm-cancel"))
        M3DialogAction(action, onConfirm, Modifier.testTag("file-confirm-accept"), destructive = destructive)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun FilesPreviewFrame(dark: Boolean, content: @Composable ColumnScope.() -> Unit) {
    SyncMdTheme(darkTheme = dark) {
        Column(Modifier.fillMaxSize().background(M3.colors.surface).padding(vertical = 12.dp), content = content)
    }
}

@Composable
private fun PreviewFileList(dark: Boolean) = FilesPreviewFrame(dark) {
    FilesBreadcrumbs("VoxVault", "ideas", 6, {})
    Column(Modifier.padding(horizontal = 16.dp)) {
        val now = System.currentTimeMillis()
        val rows = listOf(
            RepositoryFile("ideas/drafts", "drafts", true, 0) to FileEntryInsight("ideas/drafts", now, childCount = 4),
            RepositoryFile("ideas/voice-memo-pipeline.md", "voice-memo-pipeline.md", false, 3174, "M") to FileEntryInsight("", now - 9 * 60_000),
            RepositoryFile("ideas/reading-list.md", "reading-list.md", false, 1434) to FileEntryInsight("", now - 3 * 86_400_000L),
            RepositoryFile("ideas/whiteboard.png", "whiteboard.png", false, 1_258_291) to FileEntryInsight("", now - 14 * 86_400_000L, lfsTracked = true),
            RepositoryFile("ideas/field-recording.m4a", "field-recording.m4a", false, 130) to FileEntryInsight("", now, lfsTracked = true,
                lfsPointer = com.bontecou.syncmd.services.git.lfs.LfsPointer("a".repeat(64), 38L * 1024 * 1024)),
        )
        rows.forEachIndexed { i, (entry, insight) ->
            FileRow(entry, insight, i, rows.size, true, i == 1, false, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}

@Preview(name = "Files · list", heightDp = 560) @Composable private fun PreviewFilesList() = PreviewFileList(false)
@Preview(name = "Files · list dark", heightDp = 560) @Composable private fun PreviewFilesListDark() = PreviewFileList(true)
@Preview(name = "Files · empty", heightDp = 420) @Composable private fun PreviewFilesEmpty() = FilesPreviewFrame(false) { FilesEmptyState() }

@Composable
private fun PreviewEditor(dark: Boolean) {
    SyncMdTheme(darkTheme = dark) {
        Column(Modifier.fillMaxSize().background(M3.colors.surface)) {
            M3TopBar("2026-11-01.md", subtitle = "VoxVault / daily", onBack = {}, statusBarPadding = false) { M3TopBarTextAction("Commit", {}) }
            Box(Modifier.weight(1f)) {
                LineNumberedEditor("daily/2026-11-01.md", "# Sunday, Nov 1\n\n## Morning\n- Walked the beach, 40 min\n- Recorded 3 voice memos\n  → see [[voice-memo-pipeline]]\n\n## Today\n- [x] Ship Tide mockups\n- [ ] Port tokens to Compose\n",
                    true, {}, setOf(4, 5, 6))
            }
            EditorFooter("Saved · 3 lines changed", M3.colors.ok, "VoxVault ↑3", true) {}
        }
    }
}

@Preview(name = "Editor", heightDp = 640) @Composable private fun PreviewEditorLight() = PreviewEditor(false)
@Preview(name = "Editor dark", heightDp = 640) @Composable private fun PreviewEditorDark() = PreviewEditor(true)
@Preview(name = "Editor · read-only", heightDp = 420) @Composable private fun PreviewEditorReadOnly() = SyncMdTheme { Column(Modifier.background(M3.colors.surface)) { EditorReadOnly("This binary file cannot be edited as text.") } }
@Preview(name = "Editor · unavailable", heightDp = 420) @Composable private fun PreviewEditorUnavailable() = SyncMdTheme(darkTheme = true) { Column(Modifier.background(M3.colors.surface)) { EditorUnavailable {} } }
@Preview(name = "Editor · loading", heightDp = 300) @Composable private fun PreviewEditorLoading() = SyncMdTheme { Column(Modifier.background(M3.colors.surface)) { EditorLoading() } }
