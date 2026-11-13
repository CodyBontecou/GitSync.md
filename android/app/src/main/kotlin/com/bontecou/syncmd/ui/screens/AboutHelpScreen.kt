package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.BuildConfig
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.SettingsChipFlow
import com.bontecou.syncmd.ui.components.SettingsFootnote
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Badge
import com.bontecou.syncmd.ui.theme.M3BottomActionBar
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3Chevron
import com.bontecou.syncmd.ui.theme.M3FilledButton
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3SettingsRow
import com.bontecou.syncmd.ui.theme.M3Snackbar
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.SyncMdTheme

// ─── Help & about (board AboutHelp: help · copied · whatsNew · notes · tour) ──

internal enum class AboutHelpView { HELP, NOTES, TOUR }

/** Where "How GitSync works › Start from here" can go. */
class AboutHelpStarts(
    val signIn: () -> Unit = {},
    val cloneUrl: () -> Unit = {},
    val sample: () -> Unit = {},
    val storage: () -> Unit = {},
)

@Composable
fun AboutHelpScreen(onBack: () -> Unit, starts: AboutHelpStarts = AboutHelpStarts(), onNotesSeen: () -> Unit = {}) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var view by rememberSaveable { mutableStateOf(AboutHelpView.HELP) }
    var copied by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val unlock = com.bontecou.syncmd.ui.util.LocalAppUnlock.current
    val unlocked = unlock?.isUnlocked?.collectAsState()?.value ?: true
    val price = unlock?.price?.collectAsState()?.value
    fun open(intent: android.content.Intent) {
        try { context.startActivity(intent); failed = false }
        catch (_: android.content.ActivityNotFoundException) { failed = true }
        catch (_: SecurityException) { failed = true }
    }
    BackHandler(view != AboutHelpView.HELP) { view = AboutHelpView.HELP }
    when (view) {
        AboutHelpView.NOTES -> ReleaseNotesScreen(onBack = { view = AboutHelpView.HELP })
        AboutHelpView.TOUR -> HowItWorksPage(onDone = { view = AboutHelpView.HELP }, starts = starts)
        AboutHelpView.HELP -> AboutHelpContent(
            licensed = unlock?.paywallEnabled != true || unlocked,
            price = price,
            copied = copied,
            failed = failed,
            onBack = onBack,
            onTour = { view = AboutHelpView.TOUR },
            onNotes = { onNotesSeen(); view = AboutHelpView.NOTES },
            onFeedback = { open(com.bontecou.syncmd.onboarding.SupportLinks.feedbackIntent()) },
            onDiscord = { open(com.bontecou.syncmd.onboarding.SupportLinks.communityIntent()) },
            onCopy = {
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.help_copy_email), com.bontecou.syncmd.onboarding.SupportLinks.email))
                copied = true
            },
            onDismissCopied = { copied = false },
            onPrivacy = { runCatching { uriHandler.openUri("https://gitsync.md/privacy-android") }.onFailure { failed = true } },
        )
    }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(4000); copied = false } }
}

@Composable
private fun AboutHelpContent(licensed: Boolean, price: String?, copied: Boolean, failed: Boolean, onBack: () -> Unit, onTour: () -> Unit, onNotes: () -> Unit,
    onFeedback: () -> Unit, onDiscord: () -> Unit, onCopy: () -> Unit, onDismissCopied: () -> Unit, onPrivacy: () -> Unit) {
    val c = M3.colors
    Box(Modifier.fillMaxSize().background(c.surface).testTag("about-help-screen")) {
        Column(Modifier.fillMaxSize()) {
            M3TopBar(stringResource(R.string.settings_m3_help), onBack = onBack)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                M3Group(stringResource(R.string.help_title)) {
                    M3SettingsRow(stringResource(R.string.settings_m3_how_title), Modifier.testTag("open-tour"), icon = R.drawable.ic_m3_help,
                        subtitle = stringResource(R.string.settings_m3_how_subtitle), chevron = true, onClick = onTour)
                    M3SettingsRow(stringResource(R.string.settings_m3_whats_new), Modifier.testTag("open-release-notes"), icon = R.drawable.ic_m3_star,
                        subtitle = stringResource(R.string.settings_m3_whats_new_subtitle, BuildConfig.VERSION_NAME), chevron = true, onClick = onNotes, divider = true)
                    M3SettingsRow(stringResource(R.string.help_feedback), Modifier.testTag("send-feedback"), icon = R.drawable.ic_m3_mail,
                        subtitle = stringResource(R.string.settings_m3_feedback_subtitle), onClick = onFeedback, divider = true,
                        trailing = { M3Icon(R.drawable.ic_m3_open_in_new, size = 20.dp) })
                    M3SettingsRow(stringResource(R.string.settings_m3_discord), Modifier.testTag("open-community"), icon = R.drawable.ic_m3_chat,
                        onClick = onDiscord, divider = true, trailing = { M3Icon(R.drawable.ic_m3_open_in_new, size = 20.dp) })
                    M3SettingsRow(stringResource(R.string.help_copy_email), Modifier.testTag("copy-support-email"), icon = R.drawable.ic_m3_content_copy,
                        onClick = onCopy, divider = true, chevron = true)
                }
                M3Group(stringResource(R.string.settings_m3_license)) {
                    M3SettingsRow(stringResource(R.string.settings_m3_full_access), Modifier.testTag("settings-unlock-status"), icon = R.drawable.ic_m3_lock,
                        subtitle = stringResource(R.string.settings_m3_license_subtitle),
                        trailing = { if (licensed) M3Badge(stringResource(R.string.settings_m3_unlocked), M3Tone.Ok) else M3Badge(price ?: stringResource(R.string.paywall_price_fallback), M3Tone.Muted) })
                }
                M3Group(stringResource(R.string.ui_about_4efca0d1)) {
                    M3SettingsRow(stringResource(R.string.ui_app_0d04bfeb), value = "GitSync.md")
                    M3SettingsRow(stringResource(R.string.ui_platform_c78ffe19), value = "Android", divider = true)
                    M3SettingsRow(stringResource(R.string.settings_m3_privacy_policy), onClick = onPrivacy, divider = true,
                        trailing = { M3Icon(R.drawable.ic_m3_open_in_new, size = 20.dp) })
                }
                if (failed) com.bontecou.syncmd.ui.theme.M3Note(stringResource(R.string.help_open_failed), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("support-notice"),
                    tone = com.bontecou.syncmd.ui.theme.M3NoteTone.Warn)
                Spacer(Modifier.height(20.dp))
                SettingsFootnote(stringResource(R.string.tide_settings_version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), mono = true)
                Spacer(Modifier.height(32.dp))
            }
        }
        if (copied) M3Snackbar(stringResource(R.string.help_email_copied), Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp).testTag("support-notice"), actionLabel = stringResource(R.string.ui_ok_565339bc), onAction = onDismissCopied)
    }
}

@Composable
private fun HowItWorksPage(onDone: () -> Unit, starts: AboutHelpStarts) {
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).testTag("how-it-works")) {
        M3TopBar(stringResource(R.string.settings_m3_how_title), subtitle = stringResource(R.string.settings_m3_how_count), onBack = onDone)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            HowItWorksContent()
            M3SectionLabel(stringResource(R.string.settings_m3_how_start))
            SettingsChipFlow {
                StartChip(R.drawable.ic_github, stringResource(R.string.guide_login), starts.signIn)
                StartChip(R.drawable.ic_m3_link, stringResource(R.string.settings_m3_how_clone_url), starts.cloneUrl)
                StartChip(R.drawable.ic_m3_file, stringResource(R.string.settings_m3_how_sample), starts.sample)
                StartChip(R.drawable.ic_m3_folder, stringResource(R.string.settings_m3_how_storage), starts.storage)
            }
            Spacer(Modifier.height(32.dp))
        }
        M3BottomActionBar {
            M3FilledButton(stringResource(R.string.settings_m3_done), onDone, Modifier.fillMaxWidth(), size = M3ButtonSize.XLarge)
        }
    }
}

/** "Start from here" link chip: 36dp, 8dp corners, 1dp outlineVar, 14/500 on. */
@Composable
private fun StartChip(icon: Int, label: String, onClick: () -> Unit) {
    val c = M3.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    androidx.compose.foundation.layout.Row(
        Modifier.height(36.dp).clip(shape).border(1.dp, c.outlineVar, shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        M3Icon(icon, tint = c.primary, size = 18.dp)
        androidx.compose.material3.Text(label, style = com.bontecou.syncmd.ui.theme.M3Type.chip, color = c.on)
    }
}

@Preview(name = "AboutHelp · help", heightDp = 1000)
@Composable private fun AboutHelpPreview() = SyncMdTheme(darkTheme = false) {
    AboutHelpContent(true, null, false, false, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "AboutHelp · copied · dark", heightDp = 1000)
@Composable private fun AboutHelpCopiedPreview() = SyncMdTheme(darkTheme = true) {
    AboutHelpContent(true, null, true, false, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "AboutHelp · tour", heightDp = 1200)
@Composable private fun HowItWorksPreview() = SyncMdTheme(darkTheme = false) { HowItWorksPage({}, AboutHelpStarts()) }
