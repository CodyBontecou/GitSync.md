package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Tag
import com.bontecou.syncmd.ui.components.DeleteTagDialog
import com.bontecou.syncmd.ui.components.GitPage
import com.bontecou.syncmd.ui.components.GitPlainNote
import com.bontecou.syncmd.ui.components.TagRow
import com.bontecou.syncmd.ui.components.gitShortDate
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.util.singleLineNameInput
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import com.bontecou.syncmd.ui.viewmodels.HistoryViewModel
import kotlinx.coroutines.launch

/**
 * Tags (Material 3 board GitTags): New tag (name, optional message = annotated), every tag with its
 * kind, message, commit, tagger and date, Push to the remote and Delete behind a confirmation.
 */
@Composable
fun GitTagsScreen(
    path: String,
    vaultName: String,
    onBack: () -> Unit,
    historyVM: HistoryViewModel = hiltViewModel(),
    workspaceVM: GitWorkspaceViewModel = hiltViewModel(),
) {
    LaunchedEffect(path) { historyVM.setRepositoryPath(path); workspaceVM.load(path, tags = true) }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(path) { historyVM.loadHistory(); workspaceVM.load(path, tags = true) }
    val state by historyVM.state.collectAsState()
    LaunchedEffect(state.operationRevision) { if (state.operationRevision > 0) workspaceVM.load(path, tags = true) }
    val workspace by workspaceVM.state.collectAsState()
    val summary = workspace.summary?.takeIf { workspace.path == path }
    val context = state.selection?.takeIf { it.path == path }
    val canAct = context != null && state.actionsReady && !state.isLoading
    val androidContext = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(path) { mutableStateOf("") }
    var message by rememberSaveable(path) { mutableStateOf("") }
    val validName = com.bontecou.syncmd.services.git.RemoteAuthentication.isValidTag(name.trim())
    val nameError = if (name.isNotBlank() && !validName) stringResource(R.string.git_m3_tag_name_invalid) else null
    var deleting by remember { mutableStateOf<Tag?>(null) }
    var expanded by rememberSaveable(path) { mutableStateOf(false) }
    val branch = summary?.branch ?: "—"

    GitPage(
        title = stringResource(R.string.git_m3_tags),
        subtitle = stringResource(R.string.git_m3_subtitle, vaultName, branch),
        onBack = onBack,
        modifier = Modifier.testTag("git-tags-screen"),
        scrollTag = "git-tags",
        snackbar = snackbar,
    ) {
        val c = M3.colors
        M3SectionLabel(stringResource(R.string.git_m3_new_tag), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp))
        M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                M3TextField(name, { name = singleLineNameInput(it) }, stringResource(R.string.git_m3_tag_name), Modifier.testTag("git-tag-name"),
                    mono = true, enabled = canAct, isError = nameError != null, supportingText = nameError)
                M3TextField(message, { message = it }, stringResource(R.string.git_m3_message_optional), Modifier.padding(top = 12.dp).testTag("git-tag-message"), enabled = canAct)
                Text(stringResource(R.string.git_m3_tag_kind_help), Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), style = M3Type.caption, color = c.onVar)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(summary?.head?.let { stringResource(R.string.git_m3_tag_target, it.shortId, branch) }.orEmpty(), Modifier.weight(1f),
                        style = M3Type.meta, color = c.onVar)
                    M3Button(stringResource(R.string.git_m3_create_tag), {
                        val expected = context ?: return@M3Button
                        val submittedName = name; val submittedMessage = message
                        val annotation = submittedMessage.trim().takeIf { it.isNotEmpty() }
                        historyVM.createTag(expected, submittedName.trim(), annotated = annotation != null, message = annotation) {
                            if (name == submittedName) name = ""
                            if (message == submittedMessage) message = ""
                        }
                    }, Modifier.testTag("git-tag-create"), icon = R.drawable.ic_m3_tag, enabled = canAct && validName)
                }
            }
        }

        val details = workspace.tags.associateBy { it.name }
        val tags = state.tags.takeIf { context != null }.orEmpty().sortedByDescending { details[it.name]?.time ?: 0L }
        M3SectionLabel(stringResource(R.string.git_m3_in_this_vault), count = tags.size)
        if (tags.isEmpty()) Text(stringResource(R.string.ui_no_tags_in_this_repository_3b060f73), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            style = M3Type.bodyMedium, color = c.onVar)
        else M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            val shown = if (expanded) tags else tags.take(5)
            shown.forEachIndexed { index, tag ->
                val detail = details[tag.name]
                val meta = listOfNotNull(detail?.shortId ?: tag.commitHash.take(7), detail?.tagger?.takeIf { tag.isAnnotated },
                    detail?.time?.takeIf { it > 0 }?.let { gitShortDate(androidContext, it) }).joinToString(" · ")
                TagRow(tag.name, tag.isAnnotated, tag.message, meta, divider = index > 0, enabled = canAct,
                    onPush = { context?.let { historyVM.pushTag(it, tag) { scope.launch { snackbar.showSnackbar(androidContext.getString(R.string.git_m3_tag_pushed, tag.name)) } } } },
                    onDelete = { deleting = tag })
            }
            if (tags.size > 5 && !expanded) M3TextButton(stringResource(R.string.git_m3_show_more, tags.size - 5), { expanded = true },
                Modifier.fillMaxWidth().m3TopDivider(true, c.outlineVar).heightIn(min = 52.dp))
        }
        GitPlainNote(stringResource(R.string.git_m3_tags_note))
    }

    deleting?.let { tag ->
        DeleteTagDialog(tag.name, tag.commitHash.take(7), busy = state.isLoading && !state.actionsReady,
            onDelete = { context?.let { historyVM.deleteTag(it, tag) { deleting = null } } ?: run { deleting = null } },
            onDismiss = { deleting = null })
    }
    state.errorMessage?.takeIf { context != null }?.asString()?.let { error ->
        M3Dialog(onDismissRequest = { historyVM.clearError(context) }, title = stringResource(R.string.tide_git_git_operation),
            content = { Text(error, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("git-operation-message"), style = M3Type.bodyMedium) }) {
            M3DialogAction(stringResource(R.string.ui_ok_565339bc), { historyVM.clearError(context) })
        }
    }
}
