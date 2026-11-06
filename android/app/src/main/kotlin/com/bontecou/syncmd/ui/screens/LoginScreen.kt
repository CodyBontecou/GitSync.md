package com.bontecou.syncmd.ui.screens

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.bontecou.syncmd.ui.util.asString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.storage.CloneStorage
import com.bontecou.syncmd.ui.components.AddVaultHeadline
import com.bontecou.syncmd.ui.components.AddVaultIconTile
import com.bontecou.syncmd.ui.components.AddVaultMonoLtr
import com.bontecou.syncmd.ui.components.AddVaultSectionLabel
import com.bontecou.syncmd.ui.components.AddVaultTextButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBanner
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideCircleButton
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.TideWordmark
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/**
 * Full-screen GitHub sign-in. Mirrors the iOS Tide onboarding:
 *   • Welcome: always-dark stage, sync-point vignette, "Your notes. Real git."
 *     headline, paper "Sign in with GitHub" pill, outline token pill, sample link.
 *   • Token: paper step with a mono token field and one Sign in action.
 *   • Signed in: default save location (first run) or "account connected" (adding).
 */
@Composable
fun LoginScreen(
    viewModel: GitHubViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel,
    @Suppress("UNUSED_PARAMETER") onNavigateBack: (() -> Unit)? = null,
    onContinueAfterLogin: () -> Unit = {},
    addingAccount: Boolean = false,
) {
    val context      = LocalContext.current
    val focusManager = LocalFocusManager.current

    val isLoading   by viewModel.isLoading.collectAsState()
    val signedIn by viewModel.isLoggedIn.collectAsState()
    val revision by viewModel.authManager.sessionRevision.collectAsState()
    val startingRevision = rememberSaveable { viewModel.authManager.sessionRevision.value }
    val isLoggedIn = signedIn && (!addingAccount || revision != startingRevision)
    val error = viewModel.error.collectAsState().value?.asString()
    val user        by viewModel.user.collectAsState()

    var showPatFlow by remember { mutableStateOf(false) }
    var patToken by remember { mutableStateOf("") }
    var patVisible by remember { mutableStateOf(false) }
    val isOpeningSample by settingsViewModel.isOpeningSample.collectAsState()
    val sampleError by settingsViewModel.sampleError.collectAsState()

    when {
        !isLoggedIn && !showPatFlow -> LoginWelcomeContent(
            error = error,
            signingIn = isLoading,
            showSample = !addingAccount,
            isOpeningSample = isOpeningSample,
            sampleError = sampleError,
            onBack = if (addingAccount && onNavigateBack != null) onNavigateBack else null,
            onGitHub = {
                val url = viewModel.generateOAuthUrl()
                if (url != null) runCatching { CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    // AndroidX 1.9 names this protocol extra; supported browsers also honor
                    // it with 1.8. Unsupported browsers keep their ordinary Custom Tab behavior.
                    .also { it.intent.putExtra("androidx.browser.customtabs.extra.ENABLE_EPHEMERAL_BROWSING", true) }
                    .launchUrl(context, Uri.parse(url)) }.onFailure { viewModel.browserLaunchFailed() }
            },
            onToken = {
                viewModel.clearError()
                showPatFlow = true
            },
            onSample = { settingsViewModel.openSampleRepository(onContinueAfterLogin) },
        )

        !isLoggedIn -> LoginTokenContent(
            token = patToken,
            onToken = { patToken = it },
            visible = patVisible,
            onToggleVisible = { patVisible = !patVisible },
            error = error,
            isLoading = isLoading,
            onBack = {
                showPatFlow = false
                patToken = ""
                viewModel.clearError()
            },
            onCreateToken = {
                CustomTabsIntent.Builder().build().launchUrl(
                    context,
                    Uri.parse("https://github.com/settings/tokens/new?scopes=repo,user:email&description=Gitsync.md")
                )
            },
            onSubmit = {
                focusManager.clearFocus()
                viewModel.signInWithPAT(patToken.trim())
            },
        )

        addingAccount -> LoginConnectedContent(login = user?.login, error = error, onDone = onContinueAfterLogin)

        else -> LoginSaveLocationContent(
            login = user?.login,
            error = error,
            location = runCatching { CloneStorage.defaultCloneBaseDir(context) }.getOrDefault(stringResource(R.string.account_choose_storage)),
            onContinue = onContinueAfterLogin,
        )
    }
}

// ─── Welcome (always-dark stage) ────────────────────────────────────────────

@Composable
private fun LoginWelcomeContent(
    error: String?,
    signingIn: Boolean,
    showSample: Boolean,
    isOpeningSample: Boolean,
    sampleError: String?,
    onBack: (() -> Unit)?,
    onGitHub: () -> Unit,
    onToken: () -> Unit,
    onSample: () -> Unit,
) {
    val c = Tide.colors
    Box(Modifier.fillMaxSize().background(c.heroBg)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).border(1.dp, c.heroLine, CircleShape)
                            .clickable(role = Role.Button, onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.publication_back),
                            tint = c.heroText, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                }
                LoginHeroWordmark()
            }

            OnboardingSyncIllustration(Modifier.padding(top = 28.dp))

            Spacer(Modifier.height(28.dp))
            // Kept from the original welcome: a mono caps eyebrow over the headline.
            Text(
                stringResource(R.string.ui_any_repo_synced_to_your_android_d15779c7),
                style = TideType.mono(11.sp, FontWeight.Medium).copy(letterSpacing = 0.08.em),
                color = c.heroMeta,
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                val headline = TideType.display(40.sp).copy(lineHeight = 46.sp)
                Text(stringResource(R.string.tide_onboarding_headline_notes), style = headline, color = c.heroText)
                Text(stringResource(R.string.tide_onboarding_headline_git), style = headline, color = c.heroText)
                Text(stringResource(R.string.tide_onboarding_headline_step), style = headline, color = c.heroBehind)
            }
            Text(
                stringResource(R.string.tide_onboarding_welcome_body),
                style = TideType.sans(16.sp).copy(lineHeight = 23.sp),
                color = c.heroMeta,
                modifier = Modifier.padding(top = 16.dp),
            )

            if (error != null) {
                Text(
                    error,
                    style = TideType.sans(14.sp, FontWeight.Medium),
                    color = c.heroDanger,
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .fillMaxWidth()
                        .background(c.heroDanger.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(36.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HeroPaperPill(
                    title = stringResource(if (signingIn) R.string.account_signing_in else R.string.guide_login),
                    loading = signingIn,
                    onClick = onGitHub,
                )
                HeroOutlinePill(stringResource(R.string.ui_personal_access_token_5572fccc), onClick = onToken)
                if (showSample) {
                    AddVaultTextButton(
                        stringResource(if (isOpeningSample) R.string.account_opening_sample else R.string.account_try_sample),
                        onClick = onSample,
                        color = c.heroBehind,
                    )
                    Text(
                        text = sampleError ?: stringResource(R.string.account_sample_hint),
                        style = TideType.mono(12.sp),
                        color = if (sampleError != null) c.heroDanger else c.heroMeta,
                    )
                }
            }
        }
    }
}

/** "gitsync.md" on the dark stage. */
@Composable
private fun LoginHeroWordmark() {
    val c = Tide.colors
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = c.heroText)) { append("gitsync") }
            withStyle(SpanStyle(color = c.heroBehind)) { append(".md") }
        },
        modifier = Modifier.semantics { contentDescription = "GitSync.md" },
        style = TideType.mono(13.sp, FontWeight.SemiBold),
    )
}

/**
 * "this phone" and "GitHub" meeting at one sync point: fading local dots,
 * a ringed node, and fading remote rings on a hairline rail.
 */
@Composable
private fun OnboardingSyncIllustration(modifier: Modifier = Modifier) {
    val c = Tide.colors
    val description = stringResource(R.string.tide_onboarding_illustration)
    val sizes = listOf(14.dp, 11.dp, 8.dp)
    val fades = listOf(1f, 0.6f, 0.35f)
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tide_onboarding_rail_device), style = TideType.mono(11.sp), color = c.heroMeta, modifier = Modifier.weight(1f))
            Text("GitHub", style = TideType.mono(11.sp), color = c.heroMeta)
        }
        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.heroLine))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in 2 downTo 0) Box(Modifier.size(sizes[i]).background(c.heroAhead.copy(alpha = fades[i]), CircleShape))
                Box(
                    Modifier.padding(horizontal = 6.dp).size(44.dp).background(c.heroBg, CircleShape).border(2.dp, c.heroText, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", style = TideType.sans(18.sp, FontWeight.Bold), color = c.heroText) }
                for (i in 0..2) Box(Modifier.size(sizes[i]).border(2.dp, c.heroBehind.copy(alpha = fades[i]), CircleShape))
            }
        }
    }
}

/** Paper pill on the dark stage, with the GitHub mark. */
@Composable
private fun HeroPaperPill(title: String, loading: Boolean, onClick: () -> Unit) {
    val c = Tide.colors
    val fg = c.heroBg
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(CircleShape)
            .background(c.heroText, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        else Icon(painterResource(R.drawable.ic_github), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, style = TideType.sans(17.sp, FontWeight.SemiBold), color = fg)
    }
}

/** Hairline pill on the dark stage. */
@Composable
private fun HeroOutlinePill(title: String, onClick: () -> Unit) {
    val c = Tide.colors
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(CircleShape)
            .border(1.dp, c.heroLine, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(title, style = TideType.sans(15.sp, FontWeight.Medium), color = c.heroText) }
}

// ─── Personal access token ──────────────────────────────────────────────────

@Composable
private fun LoginTokenContent(
    token: String,
    onToken: (String) -> Unit,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    error: String?,
    isLoading: Boolean,
    onBack: () -> Unit,
    onCreateToken: () -> Unit,
    onSubmit: () -> Unit,
) {
    val c = Tide.colors
    var focused by remember { mutableStateOf(false) }
    LoginPaperColumn {
        TideCircleButton(stringResource(R.string.guide_back), onBack) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        AddVaultHeadline(
            title = stringResource(R.string.ui_personal_access_token_5572fccc),
            subtitle = stringResource(R.string.tide_onboarding_pat_subtitle),
            modifier = Modifier.padding(top = 18.dp, start = 4.dp),
        )
        if (error != null) TideBanner(error, Modifier.padding(top = 18.dp), tone = TideBannerTone.DANGER)

        AddVaultSectionLabel(stringResource(R.string.ui_personal_access_token_702ef4a0), Modifier.padding(top = 26.dp, bottom = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(c.card, Tide.fieldShape)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) c.ink else c.lineSoft, Tide.fieldShape)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (token.isEmpty()) Text(stringResource(R.string.ui_ghp_ff475c83), style = TideType.mono(15.sp), color = c.inkFaint)
                BasicTextField(
                    value = token,
                    onValueChange = onToken,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag("login-pat-token"),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = TideType.mono(15.sp, FontWeight.Medium).copy(color = c.ink, textDirection = TextDirection.Ltr),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    cursorBrush = SolidColor(c.ink),
                )
            }
            AddVaultTextButton(
                stringResource(if (visible) R.string.tide_onboarding_hide_token else R.string.tide_onboarding_show_token),
                onClick = onToggleVisible,
                color = c.inkMid,
            )
        }
        AddVaultTextButton(stringResource(R.string.tide_onboarding_create_pat), onClick = onCreateToken, color = c.behind,
            modifier = Modifier.padding(top = 4.dp))

        BPrimaryButton(
            title     = stringResource(if (isLoading) R.string.account_signing_in else R.string.account_sign_in),
            modifier = Modifier.padding(top = 18.dp).testTag("login-pat-submit"),
            isLoading = isLoading,
            isDisabled = token.isBlank(),
            onClick   = onSubmit,
        )
    }
}

// ─── Signed in ──────────────────────────────────────────────────────────────

@Composable
private fun LoginConnectedContent(login: String?, error: String?, onDone: () -> Unit) {
    LoginPaperColumn {
        Spacer(Modifier.height(44.dp))
        AddVaultIconTile("✓", tint = Tide.colors.synced, background = Tide.colors.synced.copy(alpha = 0.12f), size = 48.dp)
        AddVaultHeadline(
            title = stringResource(R.string.ui_github_account_connected_ccc5f749),
            modifier = Modifier.padding(top = 18.dp, start = 4.dp),
        )
        if (login != null) Text("@$login", style = TideType.mono(13.sp).copy(textDirection = TextDirection.Ltr), color = Tide.colors.inkMid,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        if (error != null) TideBanner(error, Modifier.padding(top = 18.dp), tone = TideBannerTone.DANGER)
        Spacer(Modifier.height(28.dp))
        BPrimaryButton(title = stringResource(R.string.guide_done), onClick = onDone, modifier = Modifier.testTag("account-login-done"))
    }
}

@Composable
private fun LoginSaveLocationContent(login: String?, error: String?, location: String, onContinue: () -> Unit) {
    val c = Tide.colors
    LoginPaperColumn {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TideWordmark() }
            if (login != null) Text("@$login", style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.inkMid, maxLines = 1)
        }
        AddVaultHeadline(
            title = stringResource(R.string.tide_onboarding_save_title),
            muted = stringResource(R.string.tide_onboarding_save_muted),
            subtitle = stringResource(R.string.tide_onboarding_save_subtitle),
            size = 38.sp,
            modifier = Modifier.padding(top = 36.dp, start = 4.dp),
        )
        if (error != null) TideBanner(error, Modifier.padding(top = 18.dp), tone = TideBannerTone.DANGER)
        AddVaultSectionLabel(stringResource(R.string.ui_download_location_7cc4e69d), Modifier.padding(top = 28.dp, bottom = 8.dp))
        TideCard(padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AddVaultIconTile("▤", size = 40.dp)
                Text(location, style = AddVaultMonoLtr, color = c.ink, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(28.dp))
        BPrimaryButton(title = stringResource(R.string.guide_continue), onClick = onContinue)
    }
}

/** Paper scroll column for the light onboarding steps. */
@Composable
private fun LoginPaperColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Tide.colors.paper)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Tide.gutter)
                .padding(top = 8.dp, bottom = 40.dp),
        ) { content() }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Preview(name = "Welcome", heightDp = 820) @Composable
private fun WelcomePreview() = SyncMdTheme(darkTheme = false) {
    LoginWelcomeContent(null, false, true, false, null, null, {}, {}, {})
}

@Preview(name = "Welcome · signing in, error · dark", heightDp = 820) @Composable
private fun WelcomeBusyPreview() = SyncMdTheme(darkTheme = true) {
    LoginWelcomeContent("Could not open the browser. Retry or use a Personal Access Token.", true, true, true, null, {}, {}, {}, {})
}

@Preview(name = "Token", heightDp = 700) @Composable
private fun TokenPreview() = SyncMdTheme(darkTheme = false) {
    LoginTokenContent("", {}, false, {}, null, false, {}, {}, {})
}

@Preview(name = "Token · error · dark", heightDp = 700) @Composable
private fun TokenErrorPreview() = SyncMdTheme(darkTheme = true) {
    LoginTokenContent("ghp_fixture", {}, true, {}, "Bad credentials", false, {}, {}, {})
}

@Preview(name = "Save location", heightDp = 700) @Composable
private fun SaveLocationPreview() = SyncMdTheme(darkTheme = false) {
    LoginSaveLocationContent("octocat", null, "/storage/emulated/0/GitSync.md", {})
}

@Preview(name = "Account connected · dark", heightDp = 600) @Composable
private fun ConnectedPreview() = SyncMdTheme(darkTheme = true) {
    LoginConnectedContent("octocat", null, {})
}
