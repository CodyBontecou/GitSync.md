package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.BuildConfig
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType

private val releaseNoteSections = listOf(
    R.string.notes_workflow_title to R.string.notes_workflow_body,
    R.string.notes_connections_title to R.string.notes_connections_body,
    R.string.notes_files_title to R.string.notes_files_body,
)

/** Version line plus titled paragraphs; also shown in the "What's new" dialog. */
@Composable
fun ReleaseNotesContent() {
    val c = Tide.colors
    Text(stringResource(R.string.notes_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        style = TideType.mono(12.sp).copy(textDirection = TextDirection.ContentOrLtr), color = c.inkFaint)
    releaseNoteSections.forEach { (title, body) ->
        Text(stringResource(title), style = TideType.title, color = c.ink)
        Text(stringResource(body), style = TideType.copy.copy(lineHeight = 22.sp), color = c.inkMid)
    }
}

@Composable
fun ReleaseNotesScreen(onBack: () -> Unit) {
    val c = Tide.colors
    Scaffold(modifier = Modifier.testTag("release-notes"), containerColor = c.paper, topBar = {
        WrappingTopBar(title = stringResource(R.string.notes_title), backLabel = stringResource(R.string.guide_back),
            onBack = onBack, titleModifier = Modifier.testTag("notes-title"), backModifier = Modifier.testTag("notes-back"))
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp),
        ) {
            val shape = RoundedCornerShape(Tide.radiusCard)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(c.card, shape).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { ReleaseNotesContent() }
        }
    }
}

@Preview(name = "Release notes")
@Composable private fun ReleaseNotesPreview() = SyncMdTheme(darkTheme = false) { ReleaseNotesScreen {} }

@Preview(name = "Release notes · dark")
@Composable private fun ReleaseNotesDarkPreview() = SyncMdTheme(darkTheme = true) { ReleaseNotesScreen {} }
