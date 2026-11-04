package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.bontecou.syncmd.ui.util.asString
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.BuildConfig
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.storage.CloneStorage
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBanner
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideChevron
import com.bontecou.syncmd.ui.theme.TideConfirmDialog
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideSwitchColors
import com.bontecou.syncmd.ui.util.FilesAppLauncher
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

// ─── Settings (iOS AppSettingsView) ─────────────────────────────────────────

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
) {
    val appSettings by viewModel.appSettings.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var supportNotice by remember { mutableStateOf<Int?>(null) }
    val signOutReview = githubViewModel.signOutReview.collectAsState().value
    val sessionRevision = githubViewModel.authManager.sessionRevision.collectAsState().value
    fun openSupport(intent: android.content.Intent) {
        try { context.startActivity(intent); supportNotice = null }
        catch (_: android.content.ActivityNotFoundException) { supportNotice = R.string.help_open_failed }
        catch (_: SecurityException) { supportNotice = R.string.help_open_failed }
    }
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val isLoggedIn by githubViewModel.isLoggedIn.collectAsState()
    val githubUser by githubViewModel.user.collectAsState()
    val cachedLogin by githubViewModel.authManager.cachedLogin.collectAsState()
    val cachedName by githubViewModel.authManager.cachedName.collectAsState()
    val accounts by githubViewModel.authManager.accounts.collectAsState()

    val displayName = githubUser?.name ?: githubUser?.login ?: cachedName ?: cachedLogin ?: stringResource(R.string.vault_l10n_github_user)
    val loginHandle = githubUser?.login ?: cachedLogin

    SettingsContent(
        isLoading = isLoading,
        errorMessage = errorMessage,
        account = if (isLoggedIn) SettingsAccount(displayName, loginHandle) else null,
        connectedAccounts = accounts.count { it.connected },
        defaultClonePath = runCatching { CloneStorage.defaultCloneBaseDir(context) }.getOrNull(),
        darkTheme = appSettings.isDarkTheme,
        showDebugInfo = appSettings.showDebugInfo,
        supportNotice = supportNotice,
        actions = SettingsActions(
            back = onNavigateBack,
            login = onNavigateToLogin,
            browseRepos = onNavigateToRepoPicker,
            signOut = { githubViewModel.prepareSignOut(sessionRevision) },
            manageAccounts = onManageAccounts,
            sshTrust = onManageSshTrust,
            lfsEndpoints = onManageLfsEndpoints,
            backgroundSync = onBackgroundSync,
            pushSync = onPushSync,
            obsidian = onObsidian,
            openFolder = { path -> FilesAppLauncher.openFolder(context, path) },
            manageStorage = onManageStorage,
            language = {
                try { context.startActivity(com.bontecou.syncmd.localization.AppLanguages.settings(context)) }
                catch (_: android.content.ActivityNotFoundException) { viewModel.reportError(context.getString(R.string.language_settings_unavailable)) }
                catch (_: SecurityException) { viewModel.reportError(context.getString(R.string.language_settings_unavailable)) }
            },
            darkTheme = { viewModel.updateSettings(appSettings.copy(isDarkTheme = it)) },
            debugInfo = { viewModel.updateSettings(appSettings.copy(showDebugInfo = it)) },
            diagnostics = onDiagnostics,
            tour = onTour,
            releaseNotes = onReleaseNotes,
            feedback = { openSupport(com.bontecou.syncmd.onboarding.SupportLinks.feedbackIntent()) },
            community = { openSupport(com.bontecou.syncmd.onboarding.SupportLinks.communityIntent()) },
            copyEmail = {
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.help_copy_email), com.bontecou.syncmd.onboarding.SupportLinks.email))
                supportNotice = R.string.help_email_copied
            },
            privacy = { uriHandler.openUri("https://gitsync.md/privacy-android") },
        ),
    )

    signOutReview?.let { review ->
        val subject = review.login?.let { "\u2066@$it\u2069" } ?: stringResource(R.string.tide_settings_this_account)
        TideConfirmDialog(
            title = stringResource(R.string.tide_settings_sign_out_title),
            message = stringResource(R.string.tide_settings_sign_out_message, subject),
            confirmLabel = stringResource(R.string.ui_sign_out_0b184bc5),
            cancelLabel = stringResource(R.string.publication_cancel),
            onConfirm = { githubViewModel.confirmSignOut(review) },
            onDismiss = { githubViewModel.dismissSignOut(review) },
        )
    }
}

private data class SettingsAccount(val displayName: String, val login: String?)

private class SettingsActions(
    val back: () -> Unit = {},
    val login: () -> Unit = {},
    val browseRepos: () -> Unit = {},
    val signOut: () -> Unit = {},
    val manageAccounts: () -> Unit = {},
    val sshTrust: () -> Unit = {},
    val lfsEndpoints: () -> Unit = {},
    val backgroundSync: () -> Unit = {},
    val pushSync: () -> Unit = {},
    val obsidian: () -> Unit = {},
    val openFolder: (String) -> Unit = {},
    val manageStorage: () -> Unit = {},
    val language: () -> Unit = {},
    val darkTheme: (Boolean) -> Unit = {},
    val debugInfo: (Boolean) -> Unit = {},
    val diagnostics: () -> Unit = {},
    val tour: () -> Unit = {},
    val releaseNotes: () -> Unit = {},
    val feedback: () -> Unit = {},
    val community: () -> Unit = {},
    val copyEmail: () -> Unit = {},
    val privacy: () -> Unit = {},
)

@Composable
private fun SettingsContent(
    isLoading: Boolean,
    errorMessage: String?,
    account: SettingsAccount?,
    connectedAccounts: Int,
    defaultClonePath: String?,
    darkTheme: Boolean,
    showDebugInfo: Boolean,
    supportNotice: Int?,
    actions: SettingsActions,
) {
    val c = Tide.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        SettingsHeader(onBack = actions.back)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 6.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (errorMessage != null) {
                item(key = "error") { TideBanner(title = errorMessage, tone = TideBannerTone.DANGER) }
            }

            if (isLoading) {
                item(key = "loading") {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TidePulse()
                        Text(stringResource(R.string.ui_loading_dc380888), style = TideType.meta, color = c.inkFaint)
                    }
                }
            } else {
                item(key = "account") {
                    SettingsAccountCard(account, onLogin = actions.login, onBrowse = actions.browseRepos, onSignOut = actions.signOut)
                }

                item(key = "github") {
                    SettingsAreaSection("GitHub") {
                        SettingsAreaRow(
                            stringResource(R.string.ui_manage_github_accounts_e8277e8b),
                            value = if (connectedAccounts > 0) "$connectedAccounts" else null,
                            accessory = SettingsAccessory.CHEVRON, onClick = actions.manageAccounts,
                        )
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.ui_ssh_server_trust_e17d5257), accessory = SettingsAccessory.CHEVRON, onClick = actions.sshTrust)
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.ui_lfs_endpoint_approvals_4a5953cd), accessory = SettingsAccessory.CHEVRON, onClick = actions.lfsEndpoints)
                    }
                }

                item(key = "sync") {
                    SettingsAreaSection(stringResource(R.string.tide_settings_section_sync)) {
                        SettingsAreaRow(stringResource(R.string.background_title), Modifier.testTag("settings-background-sync"),
                            accessory = SettingsAccessory.CHEVRON, onClick = actions.backgroundSync)
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.push_title), Modifier.testTag("settings-push-sync"),
                            accessory = SettingsAccessory.CHEVRON, onClick = actions.pushSync)
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.obsidian_title), Modifier.testTag("settings-obsidian"),
                            accessory = SettingsAccessory.CHEVRON, onClick = actions.obsidian)
                    }
                }

                item(key = "new-clones") {
                    SettingsAreaSection(
                        stringResource(R.string.tide_settings_section_new_clones),
                        footer = stringResource(R.string.ui_default_for_new_clones_each_clone_can_use_f0929c99),
                    ) {
                        CloneDirRow(defaultClonePath, onOpen = actions.openFolder)
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.ui_choose_clone_storage_81f04f23), accessory = SettingsAccessory.CHEVRON, onClick = actions.manageStorage)
                    }
                }

                item(key = "app") {
                    SettingsAreaSection(stringResource(R.string.ui_app_settings_da645334)) {
                        SettingsAreaRow(
                            stringResource(R.string.language_settings_title),
                            Modifier.testTag("settings-language"),
                            subtitle = if (android.os.Build.VERSION.SDK_INT < 33) stringResource(R.string.language_settings_legacy) else null,
                            accessory = SettingsAccessory.EXTERNAL,
                            onClick = actions.language,
                        )
                        SettingsAreaSeparator()
                        SettingsAreaSwitchRow(
                            stringResource(R.string.ui_dark_theme_6f2a0915), darkTheme,
                            subtitle = stringResource(R.string.ui_override_system_appearance_2685888c),
                            onChange = actions.darkTheme,
                        )
                        SettingsAreaSeparator()
                        SettingsAreaSwitchRow(
                            stringResource(R.string.ui_debug_info_697b4941), showDebugInfo,
                            subtitle = stringResource(R.string.ui_show_debug_information_6e8abf80),
                            onChange = actions.debugInfo,
                        )
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.ui_diagnostics_268f14bb), Modifier.testTag("open-diagnostics"),
                            accessory = SettingsAccessory.CHEVRON, onClick = actions.diagnostics)
                    }
                }

                item(key = "help") {
                    SettingsAreaSection(stringResource(R.string.help_title)) {
                        SettingsAreaRow(stringResource(R.string.help_tour), Modifier.testTag("open-tour"), accessory = SettingsAccessory.CHEVRON, onClick = actions.tour)
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.notes_title), Modifier.testTag("open-release-notes"), accessory = SettingsAccessory.CHEVRON, onClick = actions.releaseNotes)
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.help_feedback), Modifier.testTag("send-feedback"), accessory = SettingsAccessory.EXTERNAL, onClick = actions.feedback)
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.help_community), Modifier.testTag("open-community"), accessory = SettingsAccessory.EXTERNAL, onClick = actions.community)
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.help_copy_email), Modifier.testTag("copy-support-email"), onClick = actions.copyEmail)
                    }
                }
                supportNotice?.let { notice ->
                    item(key = "support-notice") {
                        Text(
                            stringResource(notice),
                            modifier = Modifier.padding(horizontal = 4.dp).testTag("support-notice"),
                            style = TideType.sans(13.sp, FontWeight.Medium),
                            color = if (notice == R.string.help_email_copied) c.synced else c.danger,
                        )
                    }
                }

                item(key = "license") {
                    SettingsAreaSection(stringResource(R.string.ui_license_c011d609)) { LicenseRows() }
                }

                item(key = "about") {
                    SettingsAreaSection(stringResource(R.string.ui_about_4efca0d1)) {
                        SettingsAreaRow(stringResource(R.string.ui_app_0d04bfeb), value = "GitSync.md")
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.ui_platform_c78ffe19), value = "Android")
                        SettingsAreaSeparator()
                        SettingsAreaRow(stringResource(R.string.ui_privacy_policy_506ff394), accessory = SettingsAccessory.EXTERNAL, onClick = actions.privacy)
                    }
                }

                item(key = "footer") {
                    Text(
                        stringResource(R.string.tide_settings_version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        style = TideType.mono(11.sp).copy(textDirection = TextDirection.Ltr),
                        color = c.inkFaint,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Circle back button with the large Settings title beside it. */
@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    val c = Tide.colors
    val backLabel = stringResource(R.string.ui_back_639807a2)
    Row(
        Modifier.fillMaxWidth().background(c.paper).statusBarsPadding()
            .heightIn(min = 64.dp).padding(start = 4.dp, end = Tide.gutter, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(48.dp).testTag("settings-back-button").clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, tint = c.ink,
                modifier = Modifier.size(24.dp).testTag("settings-back-arrow"))
        }
        Text(
            stringResource(R.string.ui_settings_02f6ea12),
            modifier = Modifier.weight(1f).testTag("settings-title").semantics { heading() },
            style = TideType.title,
            color = c.ink,
        )
    }
}

@Composable
private fun SettingsAccountCard(account: SettingsAccount?, onLogin: () -> Unit, onBrowse: () -> Unit, onSignOut: () -> Unit) {
    val c = Tide.colors
    val shape = RoundedCornerShape(Tide.radiusCard)
    if (account != null) {
        Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape)) {
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsAreaAvatar(account.displayName)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(account.displayName, style = TideType.sans(17.sp, FontWeight.SemiBold), color = c.ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (account.login != null) {
                        Text("@${account.login} · GitHub", style = TideType.mono(11.sp).copy(textDirection = TextDirection.Ltr),
                            color = c.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TidePill(stringResource(R.string.tide_settings_signed_in), c.synced, c.synced.copy(alpha = 0.12f))
            }
            SettingsAreaSeparator()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) { BSecondaryButton(stringResource(R.string.ui_browse_repos_e9c9d50c), onClick = onBrowse) }
                Box(Modifier.weight(1f)) {
                    SettingsAreaPill(stringResource(R.string.ui_sign_out_0b184bc5), onSignOut, Modifier.fillMaxWidth(),
                        style = SettingsPillStyle.DANGER_OUTLINE, minHeight = 56.dp)
                }
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.card, shape)
                .clickable(role = Role.Button, onClick = onLogin)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(46.dp).background(c.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_github), contentDescription = null, tint = c.onTertiaryContainer, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.guide_login), style = TideType.sans(17.sp, FontWeight.SemiBold), color = c.ink)
                Text(stringResource(R.string.tide_settings_not_signed_in), style = TideType.sans(13.sp), color = c.inkMid)
                Text(stringResource(R.string.ui_connect_your_github_account_to_browse_and_b692e428), style = TideType.sans(12.5.sp), color = c.inkFaint)
            }
            TideChevron()
        }
    }
}

@Composable
private fun LicenseRows() {
    val c = Tide.colors
    val unlock = com.bontecou.syncmd.ui.util.LocalAppUnlock.current
    if (unlock?.paywallEnabled == true) { UnlockRows(unlock); return }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.ui_gitsync_md_full_version_dde459ca), style = TideType.body, color = c.ink)
        Text(stringResource(R.string.ui_9_99_one_time_purchase_via_google_play_fa8e82bc), style = TideType.mono(11.sp), color = c.synced)
    }
    SettingsAreaSeparator()
    Text(
        stringResource(R.string.ui_all_features_are_included_after_installing_a88c124e),
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        style = TideType.sans(13.sp), color = c.inkMid,
    )
}

/** The free listing's one-time unlock: status, and Restore purchase while locked. */
@Composable
private fun UnlockRows(unlock: com.bontecou.syncmd.billing.AppUnlock) {
    val c = Tide.colors
    val unlocked by unlock.isUnlocked.collectAsState()
    val price by unlock.price.collectAsState()
    val restoring by unlock.restoring.collectAsState()
    val purchase by unlock.purchase.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp).testTag("settings-unlock"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.paywall_settings_title), style = TideType.body, color = c.ink)
        Text(if (unlocked) stringResource(R.string.paywall_settings_unlocked)
            else stringResource(R.string.paywall_price_once, price ?: stringResource(R.string.paywall_price_fallback)),
            style = TideType.mono(11.sp), color = if (unlocked) c.synced else c.inkMid, modifier = Modifier.testTag("settings-unlock-status"))
    }
    if (!unlocked) {
        SettingsAreaSeparator()
        SettingsAreaRow(stringResource(R.string.paywall_restore), Modifier.testTag("settings-unlock-restore"), enabled = !restoring,
            onClick = { scope.launch { unlock.restore() } })
        (purchase as? com.bontecou.syncmd.billing.UnlockPurchase.Failed)?.let {
            Text(it.message.asString(), modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), style = TideType.sans(13.sp), color = c.danger)
        }
    }
}

// ─── Clone directory row ────────────────────────────────────────────────────

@Composable
private fun CloneDirRow(defaultPath: String?, onOpen: (String) -> Unit) {
    val c = Tide.colors
    val displayPath = defaultPath?.let(::shortenClonePath) ?: stringResource(R.string.settings_folder_unavailable)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ui_clone_directory_157fc791), style = TideType.body, color = c.ink)
            Text(
                displayPath,
                modifier = Modifier.testTag("settings-clone-path"),
                style = if (defaultPath != null) TideType.mono(11.sp).copy(textDirection = TextDirection.Ltr)
                    else TideType.sans(12.5.sp, FontWeight.Medium).copy(textDirection = TextDirection.ContentOrLtr),
                color = if (defaultPath != null) c.inkFaint else c.ahead,
            )
        }
        if (defaultPath != null) {
            SettingsAreaPill(stringResource(R.string.ui_open_ed077f3d), { onOpen(defaultPath) }, Modifier.testTag("settings-open-folder"))
        }
    }
}

private fun shortenClonePath(path: String): String {
    val prefixes = listOf("/storage/emulated/0/", "/sdcard/")
    for (prefix in prefixes) {
        if (path.startsWith(prefix)) return path.removePrefix(prefix)
    }
    return path
}

// ─── Shared settings-area building blocks (used by the detail screens) ──────

internal enum class SettingsAccessory { NONE, CHEVRON, EXTERNAL }

/** M3 list section: a primary sentence-case header over a 28dp tonal container. */
@Composable
internal fun SettingsAreaSection(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Tide.colors
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp).semantics { heading() },
                style = TideType.section,
                color = c.primary,
            )
        }
        val shape = RoundedCornerShape(Tide.radiusCard)
        Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape), content = content)
        if (footer != null) {
            Text(footer, style = TideType.sans(12.sp).copy(lineHeight = 16.sp), color = c.inkMid,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
        }
    }
}

/** Hairline between rows in a grouped card, inset 18dp like iOS. */
@Composable
internal fun SettingsAreaSeparator() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tide.colors.line))
}

/** Label/value row (iOS AppSettingsRow): sentence-case title, optional subtitle, mono value, accessory. */
@Composable
internal fun SettingsAreaRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleIsPath: Boolean = false,
    value: String? = null,
    valueIsMono: Boolean = true,
    valueTint: Color = Tide.colors.inkMid,
    titleTint: Color = Tide.colors.ink,
    accessory: SettingsAccessory = SettingsAccessory.NONE,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Tide.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.inkMid) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TideType.body, color = titleTint)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (subtitleIsPath) TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr) else TideType.sans(14.sp).copy(lineHeight = 20.sp),
                    color = c.inkMid,
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = if (valueIsMono) TideType.mono(12.sp, if (valueTint == c.synced) FontWeight.SemiBold else FontWeight.Normal)
                    else TideType.sans(14.sp),
                color = valueTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        trailing?.invoke(this)
        when (accessory) {
            SettingsAccessory.CHEVRON -> TideChevron()
            SettingsAccessory.EXTERNAL -> Icon(painterResource(R.drawable.ic_m3_open_in_new), contentDescription = null, tint = c.inkMid, modifier = Modifier.size(20.dp))
            SettingsAccessory.NONE -> Unit
        }
    }
}

/** Toggle row: the whole row is the switch; the label (and optional subtitle) is its text. */
@Composable
internal fun SettingsAreaSwitchRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
    padded: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val c = Tide.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = if (padded) 20.dp else 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.45f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = TideType.body, color = c.ink)
            if (subtitle != null) Text(subtitle, style = TideType.sans(14.sp).copy(lineHeight = 20.sp), color = c.inkMid)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = tideSwitchColors())
    }
}

internal enum class SettingsPillStyle { INK, OUTLINE, DANGER_OUTLINE, BEHIND, DANGER_TEXT, TEXT }

/** Compact pill that wraps long translations instead of clipping; 48dp minimum target. */
@Composable
internal fun SettingsAreaPill(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SettingsPillStyle = SettingsPillStyle.OUTLINE,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
) {
    val c = Tide.colors
    val (fill, outline, tint) = when (style) {
        SettingsPillStyle.INK -> Triple(c.primary, null, c.onPrimary)
        SettingsPillStyle.OUTLINE -> Triple(null, c.lineSoft, c.primary)
        SettingsPillStyle.DANGER_OUTLINE -> Triple(null, c.lineSoft, c.danger)
        SettingsPillStyle.BEHIND -> Triple(null, c.lineSoft, c.primary)
        SettingsPillStyle.DANGER_TEXT -> Triple(null, null, c.danger)
        SettingsPillStyle.TEXT -> Triple(null, null, c.primary)
    }
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = minHeight)
            .clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill, CircleShape) else Modifier)
            .then(if (outline != null) Modifier.border(1.dp, outline, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TideType.sans(14.sp, FontWeight.SemiBold), color = tint, textAlign = TextAlign.Center)
    }
}

/** Ink circle with the account's initial (no avatar loading on Android). */
@Composable
internal fun SettingsAreaAvatar(name: String, size: Dp = 46.dp) {
    val c = Tide.colors
    Box(Modifier.size(size).background(c.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.trim().removePrefix("@").firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = TideType.sans((size.value * 0.4f).sp, FontWeight.SemiBold),
            color = c.onTertiaryContainer,
        )
    }
}

/** Thin ink progress line on a quiet well. */
@Composable
internal fun SettingsAreaProgress(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier.fillMaxWidth().clip(CircleShape),
        color = Tide.colors.primary,
        trackColor = Tide.colors.primaryContainer,
    )
}

/** Tinted inline message (notice/error/warning) on paper. */
@Composable
internal fun SettingsAreaNotice(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Tide.colors.ink,
    textModifier: Modifier = Modifier,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Tide.colors
    val shape = RoundedCornerShape(16.dp)
    val neutral = tint == c.ink
    val (fill, ink) = if (neutral) c.well to c.inkMid else (c.containerFor(tint) ?: (tint.copy(alpha = 0.12f) to tint))
    Column(
        modifier.fillMaxWidth()
            .background(fill, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, textModifier, style = TideType.sans(14.sp).copy(lineHeight = 20.sp), color = ink)
        action?.invoke(this)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun SettingsPreviewBody(account: SettingsAccount?, loading: Boolean = false, error: String? = null, path: String? = "GitSync") {
    SettingsContent(
        isLoading = loading, errorMessage = error, account = account, connectedAccounts = if (account != null) 2 else 0,
        defaultClonePath = path, darkTheme = false, showDebugInfo = true, supportNotice = null, actions = SettingsActions(),
    )
}

@Preview(name = "Settings · signed in", heightDp = 1800)
@Composable private fun SettingsSignedInPreview() = SyncMdTheme(darkTheme = false) { SettingsPreviewBody(SettingsAccount("Cody Bontecou", "codybontecou")) }

@Preview(name = "Settings · signed in · dark", heightDp = 1800)
@Composable private fun SettingsSignedInDarkPreview() = SyncMdTheme(darkTheme = true) { SettingsPreviewBody(SettingsAccount("Cody Bontecou", "codybontecou")) }

@Preview(name = "Settings · signed out, folder missing")
@Composable private fun SettingsSignedOutPreview() = SyncMdTheme(darkTheme = false) { SettingsPreviewBody(null, path = null) }

@Preview(name = "Settings · signed out · dark")
@Composable private fun SettingsSignedOutDarkPreview() = SyncMdTheme(darkTheme = true) { SettingsPreviewBody(null, path = null) }

@Preview(name = "Settings · loading with error")
@Composable private fun SettingsLoadingPreview() = SyncMdTheme(darkTheme = false) {
    SettingsPreviewBody(null, loading = true, error = "Language settings are unavailable on this device.")
}

@Preview(name = "Settings · loading · dark")
@Composable private fun SettingsLoadingDarkPreview() = SyncMdTheme(darkTheme = true) { SettingsPreviewBody(null, loading = true) }
