package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.Stash
import com.bontecou.syncmd.data.models.Tag
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.BSmallActionButton
import com.bontecou.syncmd.ui.theme.BSectionHeader
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.BranchViewModel
import com.bontecou.syncmd.ui.viewmodels.ConflictViewModel
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.HistoryViewModel
import com.bontecou.syncmd.ui.viewmodels.PullViewModel

/**
 * Git screen — matches iOS GitControlSheet.
 *
 * All git operations in one scrollable screen (opened from VaultScreen):
 *   • Repository Status  (branch, commit, changes count)
 *   • Branches           (create, switch, merge, delete)
 *   • Conflict Center    (if merge in progress)
 *   • Changes            (stage / unstage / commit message / push)
 *   • Stash              (save, apply, pop, drop)
 *   • Tags               (create, push, delete)
 *   • Pull
 */
@Composable
fun GitScreen(
    repositoryPath: String,
    onNavigateBack: () -> Unit,
    pullVM    : PullViewModel    = hiltViewModel(),
    diffVM    : DiffViewModel    = hiltViewModel(),
    branchVM  : BranchViewModel  = hiltViewModel(),
    conflictVM: ConflictViewModel= hiltViewModel(),
    historyVM : HistoryViewModel = hiltViewModel(),
) {
    // Initialise all VMs
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) {
            pullVM.setRepositoryPath(repositoryPath)
            diffVM.setRepositoryPath(repositoryPath)
            branchVM.setRepositoryPath(repositoryPath)
            conflictVM.setRepositoryPath(repositoryPath)
            historyVM.setRepositoryPath(repositoryPath)
        }
    }

    val status         by pullVM.status.collectAsState()
    val diff           by diffVM.diff.collectAsState()
    val commitMessage  by diffVM.commitMessage.collectAsState()
    val localBranches  by branchVM.localBranches.collectAsState()
    val currentBranch  by branchVM.currentBranch.collectAsState()
    val conflicts      by conflictVM.conflicts.collectAsState()
    val resolvedConflicts by conflictVM.resolvedConflicts.collectAsState()
    val mergeState     by conflictVM.mergeState.collectAsState()
    val stashes        by historyVM.stashes.collectAsState()
    val tags           by historyVM.tags.collectAsState()
    val isLoading      by diffVM.isLoading.collectAsState()
    val bc             = LocalBrutalColors.current

    var newBranchName  by remember { mutableStateOf("") }
    var newTagName     by remember { mutableStateOf("") }
    var stashMsg       by remember { mutableStateOf("") }
    var mergeMsg       by remember { mutableStateOf("") }

    val changeCount    = diff?.files?.size ?: 0
    val stagedCount    = diff?.files?.count { it.status.name == "ADDED" || it.status.name == "MODIFIED" } ?: 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bc.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top Bar ───────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bc.bg)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // "GIT" title
                    Text(
                        text = "GIT",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            letterSpacing = 4.sp,
                            color = bc.text,
                        )
                    )
                    // DONE button (matches iOS GitControlSheet)
                    Box(
                        modifier = Modifier
                            .border(1.dp, bc.border)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onNavigateBack,
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "DONE",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp,
                                color = bc.text,
                            )
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(bc.border))
            }

            // ── Scrollable body ───────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp, vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                // ── Repository Status Card ────────────────────────────────
                item {
                    BCard {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                BSectionHeader(title = "Repository Status")
                            }
                            BDivider()
                            if (status != null) {
                                StatusDataRow("Branch", status!!.currentBranch, mono = true)
                                BDivider()
                                StatusDataRow("Commit SHA", "—", mono = true)
                                BDivider()
                                StatusDataRow("Last Sync", "just now")
                                BDivider()
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "LOCAL CHANGES",
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 1.sp, color = bc.text)
                                )
                                if (changeCount > 0) {
                                    BBadge(text = "$changeCount files", style = BBadgeStyle.ACCENT)
                                } else {
                                    Text(
                                        text = "None",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = bc.textFaint)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Branches Card ─────────────────────────────────────────
                item {
                    BCard {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                BSectionHeader(title = "Branches")
                                currentBranch?.let { BBadge(text = it.name, style = BBadgeStyle.ACCENT) }
                            }
                            BDivider()

                            // Create branch inline input
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MonoInput(
                                    value       = newBranchName,
                                    onChange    = { newBranchName = it },
                                    placeholder = "new-branch-name",
                                    modifier    = Modifier.weight(1f),
                                )
                                val canCreate = newBranchName.trim().isNotEmpty()
                                ActionChipButton(
                                    label   = "CREATE",
                                    enabled = canCreate,
                                    solid   = true,
                                ) {
                                    branchVM.createBranch(newBranchName.trim(), "HEAD")
                                    newBranchName = ""
                                }
                            }

                            if (localBranches.isNotEmpty()) {
                                BDivider()
                                localBranches.forEachIndexed { idx, branch ->
                                    GitBranchRow(
                                        branch     = branch,
                                        isCurrent  = branch.isHead || branch.name == currentBranch?.name,
                                        onSwitch   = { branchVM.switchBranch(branch.name) },
                                        onMerge    = { branchVM.mergeBranch(branch.name) },
                                        onDelete   = { branchVM.deleteBranch(branch.name) },
                                    )
                                    if (idx < localBranches.size - 1) {
                                        Box(Modifier.padding(horizontal = 16.dp)) { BDivider() }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Conflict Center (only when merge in progress) ─────────
                if (mergeState?.isMergeInProgress == true) {
                    item {
                        BCard {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    BSectionHeader(title = "Conflict Center")
                                    BBadge(text = "MERGE", style = BBadgeStyle.ERROR)
                                }
                                BDivider()

                                if (conflicts.isEmpty()) {
                                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                        Text(
                                            text = "All conflicts resolved. Complete or abort the merge.",
                                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = bc.textMid)
                                        )
                                    }
                                } else {
                                    conflicts.forEachIndexed { idx, conflict ->
                                        ConflictRow(
                                            conflict      = conflict,
                                            isResolved    = resolvedConflicts.contains(conflict.filePath),
                                            onOurs        = { conflictVM.acceptOurs(conflict.filePath) },
                                            onTheirs      = { conflictVM.acceptTheirs(conflict.filePath) },
                                            onManual      = { conflictVM.acceptManual(conflict.filePath) },
                                        )
                                        if (idx < conflicts.size - 1) {
                                            Box(Modifier.padding(horizontal = 16.dp)) { BDivider() }
                                        }
                                    }
                                }

                                // Complete / abort merge
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        MonoInput(
                                            value       = mergeMsg,
                                            onChange    = { mergeMsg = it },
                                            placeholder = "Merge commit message",
                                            modifier    = Modifier.weight(1f),
                                        )
                                        ActionChipButton(
                                            label   = "COMPLETE",
                                            enabled = conflicts.isEmpty(),
                                            solid   = true,
                                            success = true,
                                        ) {
                                            conflictVM.completeMerge(mergeMsg)
                                            mergeMsg = ""
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, bc.error.copy(alpha = 0.4f))
                                            .clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() },
                                            ) { conflictVM.abortMerge() }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "✕  ABORT MERGE",
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                letterSpacing = 1.sp,
                                                color = bc.error,
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Changes Card ──────────────────────────────────────────
                item {
                    BCard {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                BSectionHeader(title = "Changes")
                                if (stagedCount > 0) BBadge(text = "$stagedCount staged", style = BBadgeStyle.SUCCESS)
                            }
                            BDivider()

                            val files = diff?.files ?: emptyList()
                            if (files.isEmpty()) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Text(
                                        text = "No local changes",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = bc.textFaint)
                                    )
                                }
                            } else {
                                files.forEachIndexed { idx, file ->
                                    GitChangeRow(
                                        file      = file,
                                        onStage   = { diffVM.stageFile(file.filePath) },
                                        onUnstage = { diffVM.unstageFile(file.filePath) },
                                    )
                                    if (idx < files.size - 1) {
                                        Box(Modifier.padding(horizontal = 16.dp)) { BDivider() }
                                    }
                                }
                            }

                            // Commit message input + push button
                            BDivider()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bc.surface)
                                    .padding(13.dp)
                            ) {
                                if (commitMessage.isEmpty()) {
                                    Text(
                                        text = "Commit message…",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = bc.textFaint)
                                    )
                                }
                                BasicTextField(
                                    value = commitMessage,
                                    onValueChange = { diffVM.setCommitMessage(it) },
                                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = bc.text),
                                    cursorBrush = SolidColor(bc.text),
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            BDivider()
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = if (stagedCount == 1) "1 file staged" else "$stagedCount files staged",
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textMid)
                                )
                            }
                            BDivider()
                            val canPush = stagedCount > 0 && commitMessage.isNotBlank()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .background(bc.text.copy(alpha = if (canPush) 1f else 0.25f))
                                    .clickable(
                                        enabled = canPush,
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                    ) { diffVM.commit() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (stagedCount == 1) "↑  PUSH 1 FILE" else "↑  PUSH $stagedCount FILES",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        letterSpacing = 1.sp,
                                        color = bc.bg,
                                    )
                                )
                            }
                        }
                    }
                }

                // ── Stash Card ────────────────────────────────────────────
                item {
                    BCard {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                BSectionHeader(title = "Stash")
                                if (stashes.isNotEmpty()) BBadge(text = "${stashes.size}", style = BBadgeStyle.DEFAULT)
                            }
                            BDivider()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MonoInput(
                                    value       = stashMsg,
                                    onChange    = { stashMsg = it },
                                    placeholder = "Stash message (optional)…",
                                    modifier    = Modifier.weight(1f),
                                )
                                ActionChipButton(
                                    label   = "SAVE",
                                    enabled = changeCount > 0,
                                    solid   = true,
                                ) {
                                    historyVM.stashSave()
                                    stashMsg = ""
                                }
                            }

                            if (stashes.isNotEmpty()) {
                                BDivider()
                                stashes.forEachIndexed { idx, stash ->
                                    GitStashRow(
                                        stash   = stash,
                                        onApply = { historyVM.stashApply(stash.id) },
                                        onPop   = { historyVM.stashPop(stash.id) },
                                        onDrop  = { historyVM.stashDrop(stash.id) },
                                    )
                                    if (idx < stashes.size - 1) {
                                        Box(Modifier.padding(horizontal = 16.dp)) { BDivider() }
                                    }
                                }
                            } else if (changeCount == 0) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    Text(
                                        text = "No local changes to stash",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = bc.textFaint)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Tags Card ─────────────────────────────────────────────
                item {
                    BCard {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                BSectionHeader(title = "Tags")
                                if (tags.isNotEmpty()) BBadge(text = "${tags.size}", style = BBadgeStyle.DEFAULT)
                            }
                            BDivider()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MonoInput(
                                    value       = newTagName,
                                    onChange    = { newTagName = it },
                                    placeholder = "tag-name (e.g. v1.0.0)",
                                    modifier    = Modifier.weight(1f),
                                )
                                ActionChipButton(
                                    label   = "CREATE",
                                    enabled = newTagName.trim().isNotEmpty(),
                                    solid   = true,
                                ) {
                                    historyVM.createTag(newTagName.trim())
                                    newTagName = ""
                                }
                            }

                            if (tags.isNotEmpty()) {
                                BDivider()
                                tags.forEachIndexed { idx, tag ->
                                    GitTagRow(
                                        tag      = tag,
                                        onPush   = { /* push not implemented in VM */ },
                                        onDelete = { historyVM.deleteTag(tag.name) },
                                    )
                                    if (idx < tags.size - 1) {
                                        Box(Modifier.padding(horizontal = 16.dp)) { BDivider() }
                                    }
                                }
                            } else {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    Text(
                                        text = "No tags in this repository",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = bc.textFaint)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Pull Card ─────────────────────────────────────────────
                item {
                    BCard(
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { pullVM.executePull() }
                    ) {
                        com.bontecou.syncmd.ui.theme.BActionRow(
                            icon     = "⬇",
                            title    = "Pull",
                            subtitle = "Fetch and apply remote changes",
                        )
                    }
                }

                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

// ─── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun StatusDataRow(label: String, value: String, mono: Boolean = false) {
    val bc = LocalBrutalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label.uppercase(),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 1.sp, color = bc.text)
        )
        Text(
            text = value,
            style = TextStyle(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = 13.sp,
                color = bc.textMid,
            )
        )
    }
}

@Composable
private fun GitBranchRow(
    branch: Branch,
    isCurrent: Boolean,
    onSwitch: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    val bc = LocalBrutalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = branch.name,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = bc.text)
            )
            if (!branch.trackingBranch.isNullOrBlank()) {
                Text(
                    text = branch.trackingBranch!!,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.textFaint)
                )
            }
        }
        if (isCurrent) {
            BBadge(text = "CURRENT", style = BBadgeStyle.SUCCESS)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BSmallActionButton(title = "Switch", onClick = onSwitch)
                BSmallActionButton(title = "Merge",  onClick = onMerge)
                BSmallActionButton(title = "✕", isDestructive = true, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun ConflictRow(
    conflict: Conflict,
    isResolved: Boolean,
    onOurs: () -> Unit,
    onTheirs: () -> Unit,
    onManual: () -> Unit,
) {
    val bc = LocalBrutalColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = conflict.filePath,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = bc.text),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isResolved) BBadge(text = "Resolved", style = BBadgeStyle.SUCCESS)
        }
        if (!isResolved) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BSmallActionButton(title = "OURS",   onClick = onOurs)
                BSmallActionButton(title = "THEIRS", onClick = onTheirs)
                BSmallActionButton(title = "MANUAL", onClick = onManual)
            }
        }
    }
}

@Composable
private fun GitChangeRow(file: FileDiff, onStage: () -> Unit, onUnstage: () -> Unit) {
    val bc      = LocalBrutalColors.current
    val isStaged = file.status.name == "ADDED" || file.status.name == "MODIFIED"
    val additions = file.hunks.sumOf { h -> h.lines.count { it.type.name == "ADDITION" } }
    val deletions = file.hunks.sumOf { h -> h.lines.count { it.type.name == "DELETION" } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = file.filePath,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = bc.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (additions > 0) Text(text = "+$additions", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.success))
                if (deletions > 0) Text(text = "-$deletions", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.error))
            }
        }
        BSmallActionButton(
            title   = if (isStaged) "UNSTAGE" else "STAGE",
            onClick = { if (isStaged) onUnstage() else onStage() },
        )
    }
}

@Composable
private fun GitStashRow(stash: Stash, onApply: () -> Unit, onPop: () -> Unit, onDrop: () -> Unit) {
    val bc = LocalBrutalColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stash.name,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = bc.text),
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BSmallActionButton(title = "APPLY", onClick = onApply)
            BSmallActionButton(title = "POP",   onClick = onPop)
            Spacer(Modifier.weight(1f))
            BSmallActionButton(title = "✕", isDestructive = true, onClick = onDrop)
        }
    }
}

@Composable
private fun GitTagRow(tag: Tag, onPush: () -> Unit, onDelete: () -> Unit) {
    val bc = LocalBrutalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = tag.name, style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = bc.text))
                BBadge(text = if (tag.isAnnotated) "annotated" else "light", style = if (tag.isAnnotated) BBadgeStyle.ACCENT else BBadgeStyle.DEFAULT)
            }
            if (!tag.message.isNullOrBlank()) {
                Text(text = tag.message!!, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.textMid), maxLines = 1)
            }
            Text(text = tag.commitHash.take(7), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.accent))
        }
        BSmallActionButton(title = "PUSH", onClick = onPush)
        BSmallActionButton(title = "✕", isDestructive = true, onClick = onDelete)
    }
}

// ─── Reusable inline input ────────────────────────────────────────────────────
@Composable
private fun MonoInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val bc = LocalBrutalColors.current
    Box(
        modifier = modifier
            .background(bc.surface)
            .border(1.dp, bc.borderSoft)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textFaint))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.text),
            singleLine = true,
            cursorBrush = SolidColor(bc.text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Solid action button (like iOS "CREATE" / "SAVE" inline) ─────────────────
@Composable
private fun ActionChipButton(
    label: String,
    enabled: Boolean = true,
    solid: Boolean = false,
    success: Boolean = false,
    onClick: () -> Unit,
) {
    val bc = LocalBrutalColors.current
    val bg = when {
        !enabled -> bc.text.copy(alpha = 0.25f)
        success  -> bc.success
        solid    -> bc.text
        else     -> bc.bg
    }
    val fg = if (solid || success) bc.bg else bc.text
    Box(
        modifier = Modifier
            .background(bg)
            .border(if (!solid && !success) 1.dp else 0.dp, bc.border)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, color = fg)
        )
    }
}

// ─── Local helpers ────────────────────────────────────────────────────────────
@Composable
private fun BCard(modifier: Modifier, content: @Composable () -> Unit) {
    com.bontecou.syncmd.ui.theme.BCard(modifier = modifier, content = content)
}

@Composable
private fun BDivider() {
    com.bontecou.syncmd.ui.theme.BDivider()
}
