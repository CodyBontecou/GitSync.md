package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.BuildConfig
import com.bontecou.syncmd.R
import com.bontecou.syncmd.localization.AppLanguages
import com.bontecou.syncmd.localization.AppLocales
import com.bontecou.syncmd.storage.RepositoryLocations
import com.bontecou.syncmd.ui.components.SettingsFootnote
import com.bontecou.syncmd.ui.components.folderCrumbs
import com.bontecou.syncmd.ui.components.settingsEntryPoint
import com.bontecou.syncmd.ui.theme.AppAppearance
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Avatar
import com.bontecou.syncmd.ui.theme.M3Badge
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3RadioRow
import com.bontecou.syncmd.ui.theme.M3SettingsRow
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.ThemeMode
import com.bontecou.syncmd.ui.viewmodels.BackgroundSyncViewModel
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.PushSyncViewModel
import com.bontecou.syncmd.ui.viewmodels.RepositoryActionsViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

// ─── Settings (boards 15 · Settings and 28 · Settings — dark) ────────────────

/** Live values shown at the end of the settings rows. */
private data class SettingsSummary(
    val backgroundVaults: Int? = null,
    val pushOn: Boolean = false,
    val shortcuts: Int = 0,
    val cloneFolder: String = "",
    val commitAs: String = "",
    val unlocked: Boolean = true,
    val price: String? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: String? = null,
    val debugEntries: Int = 0,
    val accounts: Int = 0,
    val showDebugInfo: Boolean = false,
)

private data class SettingsAccount(val displayName: String, val login: String?)

private class SettingsActions(
    val back: () -> Unit = {},
    val account: () -> Unit = {},
    val login: () -> Unit = {},
    val signOut: () -> Unit = {},
    val backgroundSync: () -> Unit = {},
    val pushSync: () -> Unit = {},
    val shortcuts: () -> Unit = {},
    val storage: () -> Unit = {},
    val commitAs: () -> Unit = {},
    val purchase: () -> Unit = {},
    val theme: () -> Unit = {},
    val language: () -> Unit = {},
    val debugLog: () -> Unit = {},
    val discord: () -> Unit = {},
    val accounts: () -> Unit = {},
    val sshHosts: () -> Unit = {},
    val lfsEndpoints: () -> Unit = {},
    val obsidian: () -> Unit = {},
    val help: () -> Unit = {},
    val debugInfo: (Boolean) -> Unit = {},
    val privacy: () -> Unit = {},
    val terms: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    onNavigateToRepoPicker: () -> Unit = {},
    onManageAccounts: () -> Unit = {},
    onManageStorage: () -> Unit = {},
    onManageSshTrust: () -> Unit = {},
    onManageLfsEndpoints: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onTour: () -> Unit = {},
    onReleaseNotes: () -> Unit = {},
    onObsidian: () -> Unit = {},
    onPushSync: () -> Unit = {},
    onBackgroundSync: () -> Unit = {},
    githubViewModel: GitHubViewModel = hiltViewModel(),
    background: BackgroundSyncViewModel? = null,
    pushSync: PushSyncViewModel? = null,
    shortcuts: RepositoryActionsViewModel? = null,
    onShortcuts: () -> Unit = {},
    onCommitAs: () -> Unit = {},
    onHelp: () -> Unit = onTour,
    onUpgrade: () -> Unit = onBackgroundSync,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val appSettings by viewModel.appSettings.collectAsState()
    val signOutReview = githubViewModel.signOutReview.collectAsState().value
    val sessionRevision = githubViewModel.authManager.sessionRevision.collectAsState().value
    val isLoggedIn by githubViewModel.isLoggedIn.collectAsState()
    val githubUser by githubViewModel.user.collectAsState()
    val cachedLogin by githubViewModel.authManager.cachedLogin.collectAsState()
    val cachedName by githubViewModel.authManager.cachedName.collectAsState()
    val accounts by githubViewModel.authManager.accounts.collectAsState()
    val entry = remember(context) { context.settingsEntryPoint() }
    val defaultAuthor by entry.defaultAuthor().author.collectAsState()
    val diagnostics by entry.diagnostics().store.entries.collectAsState()
    LaunchedEffect(Unit) { runCatching { entry.diagnostics().store.load() } }
    val backgroundState = background?.state?.collectAsState()?.value
    val pushState = pushSync?.state?.collectAsState()?.value
    val shortcutState = shortcuts?.state?.collectAsState()?.value
    val unlock = com.bontecou.syncmd.ui.util.LocalAppUnlock.current
    val unlocked = unlock?.isUnlocked?.collectAsState()?.value ?: true
    val price = unlock?.price?.collectAsState()?.value
    val theme by AppAppearance.mode(context).collectAsState()
    var language by remember { mutableStateOf(AppLocales.current(context)) }
    var pickTheme by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }

    val displayName = githubUser?.name ?: githubUser?.login ?: cachedName ?: cachedLogin ?: stringResource(R.string.vault_l10n_github_user)
    val loginHandle = githubUser?.login ?: cachedLogin
    val cloneFolder = remember(context) { runCatching { RepositoryLocations(context).summary().path }.getOrNull() }
    val accountAuthor = cachedName ?: githubUser?.name ?: cachedLogin
    val backgroundVaults = backgroundState?.let { if (it.configuration.active) it.includedCount else null }

    fun open(intent: android.content.Intent) {
        try { context.startActivity(intent); notice = null }
        catch (_: android.content.ActivityNotFoundException) { notice = R.string.help_open_failed }
        catch (_: SecurityException) { notice = R.string.help_open_failed }
    }

    SettingsContent(
        account = if (isLoggedIn) SettingsAccount(displayName, loginHandle) else null,
        summary = SettingsSummary(
            backgroundVaults = backgroundVaults,
            pushOn = pushState?.enabled == true,
            shortcuts = shortcutState?.shortcutItems?.count { it.record.enabled } ?: 0,
            cloneFolder = folderCrumbs(cloneFolder),
            commitAs = defaultAuthor?.name ?: accountAuthor.orEmpty(),
            unlocked = unlock?.paywallEnabled != true || unlocked,
            price = price,
            theme = theme ?: ThemeMode.SYSTEM,
            language = language,
            debugEntries = diagnostics.size,
            accounts = accounts.count { it.connected },
            showDebugInfo = appSettings.showDebugInfo,
        ),
        notice = notice,
        actions = SettingsActions(
            back = onNavigateBack,
            account = onManageAccounts,
            login = onNavigateToLogin,
            signOut = { githubViewModel.prepareSignOut(sessionRevision) },
            backgroundSync = { if (backgroundState != null && !backgroundState.configuration.active) onUpgrade() else onBackgroundSync() },
            pushSync = onPushSync,
            shortcuts = onShortcuts,
            storage = onManageStorage,
            commitAs = onCommitAs,
            purchase = { if (unlock?.paywallEnabled == true && !unlocked) showUnlock = true },
            theme = { pickTheme = true },
            language = {
                if (AppLocales.inAppPicker) pickLanguage = true
                else try { context.startActivity(AppLanguages.settings(context)) }
                catch (_: android.content.ActivityNotFoundException) { viewModel.reportError(context.getString(R.string.language_settings_unavailable)) }
                catch (_: SecurityException) { viewModel.reportError(context.getString(R.string.language_settings_unavailable)) }
            },
            debugLog = onDiagnostics,
            discord = { open(com.bontecou.syncmd.onboarding.SupportLinks.communityIntent()) },
            accounts = onManageAccounts,
            sshHosts = onManageSshTrust,
            lfsEndpoints = onManageLfsEndpoints,
            obsidian = onObsidian,
            help = onHelp,
            debugInfo = { viewModel.updateSettings(appSettings.copy(showDebugInfo = it)) },
            privacy = { runCatching { uriHandler.openUri(PRIVACY_URL) }.onFailure { notice = R.string.help_open_failed } },
            terms = { runCatching { uriHandler.openUri(TERMS_URL) }.onFailure { notice = R.string.help_open_failed } },
        ),
    )

    if (pickTheme) ThemePicker(theme ?: ThemeMode.SYSTEM, onPick = { mode ->
        pickTheme = false
        runCatching { AppAppearance.set(context, mode) }.onFailure { viewModel.reportError(it.message.orEmpty()) }
    }, onDismiss = { pickTheme = false })
    if (pickLanguage) LanguagePicker(language, onPick = { tag ->
        pickLanguage = false
        language = tag
        runCatching { AppLocales.set(context, tag) }.onFailure { viewModel.reportError(context.getString(R.string.language_settings_unavailable)) }
    }, onDismiss = { pickLanguage = false })
    if (showUnlock && unlock != null) UnlockSheet(unlock, null, onDismiss = { showUnlock = false })

    signOutReview?.let { review ->
        val subject = review.login?.let { androidx.core.text.BidiFormatter.getInstance(androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl).unicodeWrap("@" + it) } ?: stringResource(R.string.tide_settings_this_account)
        M3Dialog(
            onDismissRequest = { githubViewModel.dismissSignOut(review) },
            title = stringResource(R.string.tide_settings_sign_out_title),
            icon = R.drawable.ic_m3_warning, iconTint = M3.colors.error,
            text = stringResource(R.string.tide_settings_sign_out_message, subject),
        ) {
            M3DialogAction(stringResource(R.string.publication_cancel), { githubViewModel.dismissSignOut(review) })
            M3DialogAction(stringResource(R.string.settings_m3_sign_out), { githubViewModel.confirmSignOut(review) }, destructive = true)
        }
    }
}

private const val PRIVACY_URL = "https://gitsync.md/privacy-android"
private const val TERMS_URL = "https://gitsync.md/terms"

@Composable
private fun SettingsContent(account: SettingsAccount?, summary: SettingsSummary, notice: Int?, actions: SettingsActions) {
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).testTag("settings-screen")) {
        M3TopBar(stringResource(R.string.settings_m3_title), onBack = actions.back, backLabel = stringResource(R.string.ui_back_639807a2),
            backTestTag = "settings-back-button", titleTestTag = "settings-title")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsAccountCard(account, onOpen = actions.account, onLogin = actions.login)

            M3Group(stringResource(R.string.settings_m3_group_sync)) {
                M3SettingsRow(stringResource(R.string.settings_m3_background_sync), Modifier.testTag("settings-background-sync"),
                    icon = R.drawable.ic_m3_sync, onClick = actions.backgroundSync,
                    value = summary.backgroundVaults?.let { stringResource(R.string.settings_m3_background_on, it) } ?: stringResource(R.string.settings_m3_off))
                M3SettingsRow(stringResource(R.string.settings_m3_push_sync), Modifier.testTag("settings-push-sync"), icon = R.drawable.ic_m3_notifications,
                    value = stringResource(if (summary.pushOn) R.string.settings_m3_on else R.string.settings_m3_off), onClick = actions.pushSync, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_shortcuts), Modifier.testTag("settings-shortcuts"), icon = R.drawable.ic_m3_shortcut,
                    value = summary.shortcuts.toString(), onClick = actions.shortcuts, divider = true)
            }
            M3Group(stringResource(R.string.settings_m3_group_new_vaults)) {
                M3SettingsRow(stringResource(R.string.settings_m3_save_clones_in), Modifier.testTag("settings-storage"), icon = R.drawable.ic_m3_folder,
                    value = summary.cloneFolder.ifEmpty { stringResource(R.string.settings_folder_unavailable) }, onClick = actions.storage)
                M3SettingsRow(stringResource(R.string.settings_m3_commit_as), Modifier.testTag("settings-commit-as"), icon = R.drawable.ic_m3_person,
                    value = summary.commitAs.ifEmpty { null }, onClick = actions.commitAs, divider = true)
            }
            M3Group(stringResource(R.string.settings_m3_group_purchase)) {
                M3SettingsRow(stringResource(R.string.settings_m3_full_access), Modifier.testTag("settings-unlock"), icon = R.drawable.ic_m3_lock,
                    value = if (summary.unlocked) stringResource(R.string.settings_m3_unlocked) else summary.price ?: stringResource(R.string.paywall_price_fallback),
                    onClick = if (summary.unlocked) null else actions.purchase)
            }
            M3Group(stringResource(R.string.settings_m3_group_app)) {
                M3SettingsRow(stringResource(R.string.settings_m3_theme), Modifier.testTag("settings-theme"), icon = R.drawable.ic_m3_palette,
                    value = stringResource(summary.theme.label()), onClick = actions.theme)
                M3SettingsRow(stringResource(R.string.settings_m3_language), Modifier.testTag("settings-language"), icon = R.drawable.ic_m3_language,
                    value = summary.language?.let(AppLocales::displayName) ?: stringResource(R.string.settings_m3_system_default),
                    subtitle = if (!AppLocales.inAppPicker) stringResource(R.string.language_settings_legacy) else null,
                    onClick = actions.language, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_debug_log), Modifier.testTag("open-diagnostics"), icon = R.drawable.ic_m3_bug_report,
                    value = androidx.compose.ui.res.pluralStringResource(R.plurals.settings_m3_entries, summary.debugEntries, summary.debugEntries),
                    onClick = actions.debugLog, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_discord), Modifier.testTag("open-community"), icon = R.drawable.ic_m3_chat,
                    onClick = actions.discord, divider = true)
            }
            // Deeper settings pages the design moved off the main list (Accounts, SSH, LFS, Obsidian, Help & about).
            M3Group(stringResource(R.string.settings_m3_group_more)) {
                M3SettingsRow(stringResource(R.string.settings_m3_accounts), Modifier.testTag("settings-accounts"), icon = R.drawable.ic_m3_person,
                    value = summary.accounts.takeIf { it > 0 }?.toString(), onClick = actions.accounts)
                M3SettingsRow(stringResource(R.string.settings_m3_ssh_hosts), Modifier.testTag("settings-ssh"), icon = R.drawable.ic_m3_key,
                    onClick = actions.sshHosts, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_lfs_endpoints), Modifier.testTag("settings-lfs"), icon = R.drawable.ic_m3_dns,
                    onClick = actions.lfsEndpoints, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_obsidian), Modifier.testTag("settings-obsidian"), icon = R.drawable.ic_m3_link,
                    onClick = actions.obsidian, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_debug_info), Modifier.testTag("settings-debug-info"), icon = R.drawable.ic_m3_info,
                    checked = summary.showDebugInfo, onCheckedChange = actions.debugInfo, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_help), Modifier.testTag("settings-help"), icon = R.drawable.ic_m3_help,
                    onClick = actions.help, divider = true)
            }
            notice?.let { M3Note(stringResource(it), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("support-notice"), tone = M3NoteTone.Warn) }

            Spacer(Modifier.height(20.dp))
            SettingsFootnote(stringResource(R.string.tide_settings_version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                Modifier.testTag("settings-version"), mono = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                M3Button(stringResource(R.string.settings_m3_privacy), actions.privacy, style = M3ButtonStyle.Text)
                M3Button(stringResource(R.string.settings_m3_terms), actions.terms, style = M3ButtonStyle.Text)
            }
            if (account != null) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                M3Button(stringResource(R.string.settings_m3_sign_out), actions.signOut, Modifier.testTag("settings-sign-out"),
                    style = M3ButtonStyle.Outlined, destructive = true)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Account card: 48dp initials avatar, name 18/24 500, "@login · GitHub", "signed in" badge. Tapping opens GitHub accounts. */
@Composable
private fun SettingsAccountCard(account: SettingsAccount?, onOpen: () -> Unit, onLogin: () -> Unit) {
    val c = M3.colors
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).clip(shape).background(c.cLow, shape)
            .clickable(role = Role.Button, onClick = if (account != null) onOpen else onLogin)
            .semantics(mergeDescendants = true) {}.padding(horizontal = 20.dp, vertical = 16.dp).testTag("settings-account"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (account != null) {
            M3Avatar(initials(account.displayName), size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(account.displayName, style = M3Type.lead.copy(fontWeight = FontWeight.Medium), color = c.on, maxLines = 2, overflow = TextOverflow.Ellipsis)
                account.login?.let {
                    Text(stringResource(R.string.settings_m3_account_meta, it), style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.onVar,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            M3Badge(stringResource(R.string.tide_settings_signed_in), M3Tone.Ok)
        } else {
            com.bontecou.syncmd.ui.theme.M3LeadingTile(R.drawable.ic_github, size = 48.dp, container = c.tc, content = c.onTc)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.guide_login), style = M3Type.lead.copy(fontWeight = FontWeight.Medium), color = c.on)
                Text(stringResource(R.string.tide_settings_not_signed_in), style = M3Type.bodySmall, color = c.onVar)
            }
            com.bontecou.syncmd.ui.theme.M3Chevron()
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

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_m3_system_default
    ThemeMode.LIGHT -> R.string.settings_m3_theme_light
    ThemeMode.DARK -> R.string.settings_m3_theme_dark
}

@Composable
private fun ThemePicker(current: ThemeMode, onPick: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    M3Dialog(onDismissRequest = onDismiss, title = stringResource(R.string.settings_m3_theme), content = {
        com.bontecou.syncmd.ui.theme.M3InsetList(radius = 12.dp) {
            ThemeMode.values().forEachIndexed { index, mode ->
                M3RadioRow(stringResource(mode.label()), mode == current, { onPick(mode) }, Modifier.testTag("theme-${mode.name}"),
                    mono = false, divider = index > 0)
            }
        }
    }) { M3DialogAction(stringResource(R.string.publication_cancel), onDismiss) }
}

@Composable
private fun LanguagePicker(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    M3Dialog(onDismissRequest = onDismiss, title = stringResource(R.string.settings_m3_language), content = {
        com.bontecou.syncmd.ui.theme.M3InsetList(Modifier.heightIn(max = 420.dp), radius = 12.dp) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                M3RadioRow(stringResource(R.string.settings_m3_system_default), current == null, { onPick(null) }, Modifier.testTag("language-system"), mono = false)
                AppLocales.supported.forEach { tag ->
                    M3RadioRow(AppLocales.displayName(tag), current == tag, { onPick(tag) }, Modifier.testTag("language-$tag"), mono = false, divider = true)
                }
            }
        }
    }) { M3DialogAction(stringResource(R.string.publication_cancel), onDismiss) }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewSummary = SettingsSummary(backgroundVaults = 4, pushOn = true, shortcuts = 3, cloneFolder = "Documents › Obsidian",
    commitAs = "Cody Bontecou", debugEntries = 128, accounts = 2)

@Preview(name = "15 · Settings", heightDp = 1600)
@Composable private fun SettingsPreview() = SyncMdTheme(darkTheme = false) {
    SettingsContent(SettingsAccount("Cody Bontecou", "CodyBontecou"), previewSummary, null, SettingsActions())
}

@Preview(name = "28 · Settings — dark", heightDp = 1600)
@Composable private fun SettingsDarkPreview() = SyncMdTheme(darkTheme = true) {
    SettingsContent(SettingsAccount("Cody Bontecou", "CodyBontecou"), previewSummary, null, SettingsActions())
}

@Preview(name = "Settings · signed out")
@Composable private fun SettingsSignedOutPreview() = SyncMdTheme(darkTheme = false) {
    SettingsContent(null, previewSummary.copy(backgroundVaults = null, pushOn = false), null, SettingsActions())
}
