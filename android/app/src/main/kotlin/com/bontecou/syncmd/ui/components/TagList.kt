package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3DialogSurface
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3TopDivider

/** One tag (Tags board): mono name with an "annotated"/"light" tag, its message, "id · tagger · date", Push and Delete. */
@Composable
fun TagRow(name: String, annotated: Boolean, message: String?, meta: String, divider: Boolean, enabled: Boolean, onPush: () -> Unit, onDelete: () -> Unit) {
    val c = M3.colors
    Row(Modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).testTag("git-tag-$name").heightIn(min = 72.dp)
        .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, Modifier.weight(1f, fill = false), style = M3Type.branch, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                GitKindTag(stringResource(if (annotated) R.string.git_m3_annotated else R.string.git_m3_light), annotated)
            }
            if (!message.isNullOrBlank()) Text(message, style = M3Type.bodyMedium, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = M3Type.meta, color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val pushLabel = stringResource(R.string.git_m3_push_tag_cd, name)
        M3Button(stringResource(R.string.git_m3_push), onPush, Modifier.semantics { contentDescription = pushLabel }.testTag("git-tag-push-$name"),
            style = M3ButtonStyle.Outlined, size = M3ButtonSize.Small, icon = R.drawable.ic_m3_arrow_upward, enabled = enabled)
        GitDeleteButton(stringResource(R.string.git_m3_delete_tag_cd, name), onDelete, Modifier.testTag("git-tag-delete-$name"), enabled = enabled)
    }
}

/** "Delete v0.3-beta?": the tag goes; its commit and any pushed copy stay. */
@Composable
fun DeleteTagDialog(name: String, commit: String, busy: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    M3Dialog(onDismissRequest = { if (!busy) onDismiss() }, title = stringResource(R.string.git_m3_delete_tag_title, name),
        modifier = Modifier.testTag("git-delete-tag-dialog"), icon = R.drawable.ic_m3_delete, iconTint = M3.colors.error,
        content = { DeleteTagBody(commit) }) {
        M3DialogAction(stringResource(R.string.git_m3_cancel), onDismiss, enabled = !busy)
        M3DialogAction(stringResource(R.string.git_m3_delete_tag), onDelete, Modifier.testTag("git-delete-tag-confirm"), destructive = true, filled = true, loading = busy)
    }
}

@Composable
private fun DeleteTagBody(commit: String) =
    Text(gitMonoInline(stringResource(R.string.git_m3_delete_tag_body), commit), style = M3Type.bodyMedium, color = M3.colors.onVar)

@Preview(name = "Tags · light")
@Composable
private fun TagRowsPreview() = SyncMdTheme(darkTheme = false) {
    M3ListCard(Modifier.padding(16.dp)) {
        TagRow("v0.4", true, "Pipeline merge", "51fe803 · Cody Bontecou · Nov 1", false, true, {}, {})
        TagRow("v0.3-beta", false, null, "0b8d771 · Oct 31", true, true, {}, {})
    }
}

@Preview(name = "Delete tag · dark")
@Composable
private fun DeleteTagPreview() = SyncMdTheme(darkTheme = true) {
    M3DialogSurface(stringResource(R.string.git_m3_delete_tag_title, "v0.3-beta"), icon = R.drawable.ic_m3_delete, iconTint = M3.colors.error,
        content = { DeleteTagBody("0b8d771") }) {
        M3DialogAction("Cancel", {}); M3DialogAction("Delete tag", {}, destructive = true, filled = true)
    }
}
