package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.BuildConfig
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.SettingsCard
import com.bontecou.syncmd.ui.components.SettingsHint
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme

// ─── What's new (AboutHelp: notes · whatsNew) ───────────────────────────────

private val releaseNoteSections = listOf(
    R.string.notes_workflow_title to R.string.notes_workflow_body,
    R.string.notes_connections_title to R.string.notes_connections_body,
    R.string.notes_files_title to R.string.notes_files_body,
)

/** The titled paragraphs (16/24 600 over 14/22 onVar); shared by the page and the once-after-update dialog. */
@Composable
fun ReleaseNotesContent(showVersion: Boolean = true) {
    val c = M3.colors
    if (showVersion) Text(stringResource(R.string.notes_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        style = M3Type.meta.copy(textDirection = TextDirection.ContentOrLtr), color = c.onVar)
    releaseNoteSections.forEachIndexed { index, (title, body) ->
        Text(stringResource(title), Modifier.padding(top = if (index == 0 && !showVersion) 0.dp else 16.dp),
            style = M3Type.body.copy(fontWeight = FontWeight.SemiBold), color = c.on)
        Text(stringResource(body), Modifier.padding(top = 4.dp), style = M3Type.bodyMedium.copy(lineHeight = 22.sp), color = c.onVar)
    }
}

@Composable
fun ReleaseNotesScreen(onBack: () -> Unit) {
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).testTag("release-notes")) {
        M3TopBar(stringResource(R.string.settings_m3_whats_new),
            subtitle = stringResource(R.string.notes_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            onBack = onBack, modifier = Modifier.testTag("notes-title"))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SettingsCard { Column(Modifier.padding(20.dp)) { ReleaseNotesContent(showVersion = false) } }
            SettingsHint(stringResource(R.string.settings_m3_whats_new_hint))
        }
    }
}

/** "What's new" dialog, shown once after an update (AboutHelp › whatsNew). */
@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    M3Dialog(onDismissRequest = onDismiss, title = stringResource(R.string.settings_m3_whats_new), icon = R.drawable.ic_m3_star, content = {
        Text(stringResource(R.string.notes_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), Modifier.fillMaxWidth(),
            style = M3Type.meta.copy(textAlign = TextAlign.Center, textDirection = TextDirection.ContentOrLtr), color = M3.colors.onVar)
        ReleaseNotesContent(showVersion = false)
    }) { M3DialogAction(stringResource(R.string.notes_acknowledge), onDismiss, Modifier.testTag("notes-acknowledge")) }
}

@Preview(name = "AboutHelp · notes")
@Composable private fun ReleaseNotesPreview() = SyncMdTheme(darkTheme = false) { ReleaseNotesScreen {} }

@Preview(name = "AboutHelp · notes · dark")
@Composable private fun ReleaseNotesDarkPreview() = SyncMdTheme(darkTheme = true) { ReleaseNotesScreen {} }
