package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.SettingsHint
import com.bontecou.syncmd.ui.components.SettingsIntro
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Avatar
import com.bontecou.syncmd.ui.theme.M3Badge
import com.bontecou.syncmd.ui.theme.M3BottomActionBar
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3FilledButton
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3LeadingTile
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3OutlinedCard
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3TonalButton
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.AuthorViewModel

// ─── Commit as (board CommitAs: account · vault · loading · changed) ────────

/** States of the board: from the account, saved in the vault (or globally), reading, changed outside the app. */
internal enum class CommitAsView { ACCOUNT, OVERRIDE, LOADING, CHANGED }

/**
 * A vault's author ([path]), or with [global] the app-wide default ("Settings › Commit as") that every
 * vault without its own author uses. [subtitle] is the vault's owner/name.
 */
@Composable
fun AuthorScreen(path: String, onBack: () -> Unit, viewModel: AuthorViewModel = hiltViewModel(), subtitle: String? = null, global: Boolean = false) {
    val state = viewModel.state.collectAsState().value
    // The first frame of a new review must not carry a previous review's draft.
    var name by remember(path, state.requestId, state.plan, state.busy && state.name.isEmpty() && state.email.isEmpty()) { mutableStateOf(state.name) }
    var email by remember(path, state.requestId, state.plan, state.busy && state.name.isEmpty() && state.email.isEmpty()) { mutableStateOf(state.email) }
    LaunchedEffect(path, global) { if (global) viewModel.loadGlobal() else viewModel.load(path) }
    LaunchedEffect(state.requestId, state.saved) { if (state.saved && viewModel.state.value === state) onBack() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, path, global) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && !global) viewModel.verify(path) }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(state.busy && (state.plan != null || state.global)) {}
    val view = when {
        state.busy && state.plan == null && state.name.isEmpty() && state.email.isEmpty() -> CommitAsView.LOADING
        state.changed -> CommitAsView.CHANGED
        state.overrides -> CommitAsView.OVERRIDE
        else -> CommitAsView.ACCOUNT
    }
    AuthorContent(
        view = view, global = state.global || global, subtitle = subtitle, login = state.login, busy = state.busy, saved = state.saved,
        error = state.error?.asString()?.takeIf { !state.changed }, name = name, email = email,
        onName = { name = it }, onEmail = { email = it }, onBack = onBack,
        onSave = { viewModel.save(state, name, email) },
        onReload = { if (global) viewModel.loadGlobal() else viewModel.load(path) },
        onUseAccount = if (global && state.overrides) viewModel::clearGlobal else null,
    )
}

@Composable
private fun AuthorContent(
    view: CommitAsView,
    global: Boolean,
    subtitle: String?,
    login: String?,
    busy: Boolean,
    saved: Boolean,
    error: String?,
    name: String,
    email: String,
    onName: (String) -> Unit,
    onEmail: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onUseAccount: (() -> Unit)?,
) {
    val c = M3.colors
    val handle = login?.let { "@$it" } ?: stringResource(R.string.settings_m3_author_your_account)
    Column(Modifier.fillMaxSize().background(c.surface).imePadding().testTag("author-screen")) {
        M3TopBar(stringResource(R.string.settings_m3_commit_as), subtitle = if (global) stringResource(R.string.settings_m3_author_global_subtitle) else subtitle,
            onBack = if (busy && view != CommitAsView.LOADING) null else onBack, modifier = Modifier.testTag("author-title"))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsIntro(stringResource(if (global) R.string.settings_m3_author_intro_global else R.string.settings_m3_author_intro))
            when (view) {
                CommitAsView.ACCOUNT -> SourceCard(
                    leading = { M3Avatar(initials(name.ifBlank { login.orEmpty() }), size = 40.dp) },
                    title = stringResource(R.string.settings_m3_author_from_account),
                    meta = stringResource(if (global) R.string.settings_m3_author_account_meta_global else R.string.settings_m3_author_account_meta, handle),
                    badge = stringResource(R.string.settings_m3_author_default), tone = M3Tone.Muted)
                CommitAsView.OVERRIDE, CommitAsView.CHANGED -> SourceCard(
                    leading = { M3LeadingTile(R.drawable.ic_m3_folder) },
                    title = stringResource(if (global) R.string.settings_m3_author_saved_global else R.string.settings_m3_author_saved_vault),
                    meta = stringResource(if (global) R.string.settings_m3_author_saved_global_meta else R.string.settings_m3_author_saved_vault_meta, handle),
                    badge = stringResource(R.string.settings_m3_author_override), tone = M3Tone.Ahead)
                CommitAsView.LOADING -> Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                    M3IndeterminateProgressBar(description = stringResource(R.string.settings_m3_author_reading))
                    Text(stringResource(R.string.settings_m3_author_reading), Modifier.padding(top = 8.dp), style = M3Type.meta, color = c.onVar)
                }
            }
            if (view != CommitAsView.LOADING) {
                M3TextField(name, onName, stringResource(R.string.settings_m3_author_name), Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp).testTag("author-name"),
                    enabled = !busy)
                M3TextField(email, onEmail, stringResource(R.string.settings_m3_author_email), Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp).testTag("author-email"),
                    mono = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Text(stringResource(when {
                    view == CommitAsView.CHANGED -> R.string.settings_m3_author_help_changed
                    email.trim().endsWith("@users.noreply.github.com") -> R.string.settings_m3_author_help_noreply
                    else -> R.string.settings_m3_author_help_match
                }), Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp), style = M3Type.caption, color = c.onVar)
                M3SectionLabel(stringResource(R.string.settings_m3_author_preview_title), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp))
                M3OutlinedCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                    Text(stringResource(R.string.settings_m3_author_preview_message), style = M3Type.body, color = c.on)
                    Text(stringResource(R.string.settings_m3_author_preview_meta, name.trim(), email.trim()), style = M3Type.meta.copy(textDirection = TextDirection.Ltr),
                        color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SettingsHint(stringResource(if (global) R.string.settings_m3_author_scope_global else R.string.settings_m3_author_scope))
                if (onUseAccount != null) com.bontecou.syncmd.ui.theme.M3TextButton(stringResource(R.string.settings_m3_author_use_account), onUseAccount,
                    Modifier.padding(start = 8.dp, top = 4.dp), enabled = !busy)
            }
            if (view == CommitAsView.CHANGED) M3Note(stringResource(R.string.settings_m3_author_changed), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
                tone = M3NoteTone.Warn, icon = R.drawable.ic_m3_warning, action = {
                    M3TonalButton(stringResource(R.string.ui_reload_author_18a711d8), onReload, icon = R.drawable.ic_m3_refresh, enabled = !busy)
                })
            error?.let { message ->
                M3Note(message, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), tone = M3NoteTone.Warn, action = {
                    M3TonalButton(stringResource(R.string.ui_reload_author_18a711d8), onReload, icon = R.drawable.ic_m3_refresh, enabled = !busy)
                })
            }
            Spacer(Modifier.height(32.dp))
        }
        M3BottomActionBar {
            M3FilledButton(stringResource(R.string.settings_m3_author_save), onSave, Modifier.fillMaxWidth().testTag("author-save"), size = M3ButtonSize.XLarge,
                enabled = view != CommitAsView.LOADING && view != CommitAsView.CHANGED && !busy && !saved && name.isNotBlank() && email.isNotBlank(),
                loading = busy && view != CommitAsView.LOADING)
            Text(stringResource(R.string.settings_m3_author_save_note), Modifier.fillMaxWidth(), style = M3Type.caption.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                color = c.onVar)
        }
    }
}

@Composable
private fun SourceCard(leading: @Composable () -> Unit, title: String, meta: String, badge: String, tone: M3Tone) {
    val c = M3.colors
    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(title, style = M3Type.bodyStrong, color = c.on)
                Text(meta, style = M3Type.meta.copy(textDirection = TextDirection.ContentOrLtr), color = c.onVar)
            }
            M3Badge(badge, tone)
        }
    }
}

private fun initials(name: String): String {
    val parts = name.trim().removePrefix("@").split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun AuthorPreview(view: CommitAsView, name: String = "Cody Bontecou", email: String = "cody@codybontecou.com") =
    AuthorContent(view, false, "CodyBontecou/VoxVault", "CodyBontecou", false, false, null, name, email, {}, {}, {}, {}, {}, null)

@Preview(name = "CommitAs · account", heightDp = 915)
@Composable private fun AuthorAccountPreview() = SyncMdTheme(darkTheme = false) { AuthorPreview(CommitAsView.ACCOUNT, email = "CodyBontecou@users.noreply.github.com") }

@Preview(name = "CommitAs · vault · dark", heightDp = 915)
@Composable private fun AuthorVaultDarkPreview() = SyncMdTheme(darkTheme = true) { AuthorPreview(CommitAsView.OVERRIDE) }

@Preview(name = "CommitAs · loading")
@Composable private fun AuthorLoadingPreview() = SyncMdTheme(darkTheme = false) { AuthorPreview(CommitAsView.LOADING, "", "") }

@Preview(name = "CommitAs · changed", heightDp = 1000)
@Composable private fun AuthorChangedPreview() = SyncMdTheme(darkTheme = false) { AuthorPreview(CommitAsView.CHANGED, "Cody B.") }
