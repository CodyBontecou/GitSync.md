package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.bontecou.syncmd.ui.util.asString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors
import com.bontecou.syncmd.ui.viewmodels.AuthorViewModel

@Composable
fun AuthorScreen(path: String, onBack: () -> Unit, viewModel: AuthorViewModel = hiltViewModel()) {
    val state = viewModel.state.collectAsState().value
    // The first frame of a new review must not carry a previous review's draft.
    var name by remember(path, state.requestId, state.plan) { mutableStateOf(state.name) }
    var email by remember(path, state.requestId, state.plan) { mutableStateOf(state.email) }
    LaunchedEffect(path) { viewModel.load(path) }
    LaunchedEffect(state.requestId, state.saved) { if (state.saved && viewModel.state.value === state) onBack() }
    BackHandler(state.busy && state.plan != null) {}
    AuthorContent(
        busy = state.busy, hasPlan = state.plan != null, saved = state.saved, error = state.error?.asString(),
        name = name, email = email, onName = { name = it }, onEmail = { email = it },
        onBack = onBack, onSave = { viewModel.save(state, name, email) }, onReload = { viewModel.load(path) },
    )
}

@Composable
private fun AuthorContent(
    busy: Boolean,
    hasPlan: Boolean,
    saved: Boolean,
    error: String?,
    name: String,
    email: String,
    onName: (String) -> Unit,
    onEmail: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
) {
    val c = Tide.colors
    Scaffold(modifier = Modifier.testTag("author-screen"), containerColor = c.paper, topBar = {
        WrappingTopBar(
            title = stringResource(R.string.ui_commit_author_e9376b3a),
            backLabel = stringResource(R.string.publication_back),
            onBack = onBack, backEnabled = !busy, titleModifier = Modifier.testTag("author-title"))
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.ui_new_commits_in_this_repository_will_use_th_ac4a98e2),
                modifier = Modifier.padding(horizontal = 4.dp), style = TideType.copy, color = c.inkMid)
            if (busy) SettingsAreaProgress()
            if (hasPlan) {
                OutlinedTextField(name, onName, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.publication_author)) },
                    shape = Tide.fieldShape, colors = tideFieldColors(), textStyle = TideType.sans(16.sp),
                    modifier = Modifier.fillMaxWidth().testTag("author-name"))
                OutlinedTextField(email, onEmail, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.publication_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = Tide.fieldShape, colors = tideFieldColors(), textStyle = TideType.mono(15.sp),
                    modifier = Modifier.fillMaxWidth().testTag("author-email"))
                BPrimaryButton(
                    stringResource(R.string.ui_save_author_1c24b32a), Modifier.testTag("author-save"),
                    isDisabled = busy || saved || name.isBlank() || email.isBlank(),
                    onClick = onSave,
                )
            }
            if (error != null) {
                SettingsAreaNotice(error, tint = c.danger) {
                    BSecondaryButton(stringResource(R.string.ui_reload_author_18a711d8), isDisabled = busy, onClick = onReload)
                }
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Preview(name = "Author · ready")
@Composable private fun AuthorReadyPreview() = SyncMdTheme(darkTheme = false) {
    AuthorContent(false, true, false, null, "Cody Bontecou", "cody@example.com", {}, {}, {}, {}, {})
}

@Preview(name = "Author · ready · dark")
@Composable private fun AuthorReadyDarkPreview() = SyncMdTheme(darkTheme = true) {
    AuthorContent(false, true, false, null, "Cody Bontecou", "cody@example.com", {}, {}, {}, {}, {})
}

@Preview(name = "Author · loading")
@Composable private fun AuthorLoadingPreview() = SyncMdTheme(darkTheme = false) {
    AuthorContent(true, false, false, null, "", "", {}, {}, {}, {}, {})
}

@Preview(name = "Author · error · dark")
@Composable private fun AuthorErrorDarkPreview() = SyncMdTheme(darkTheme = true) {
    AuthorContent(false, true, false, "The repository author changed outside GitSync.md. Reload to review it.", "Cody", "cody@example.com", {}, {}, {}, {}, {})
}
