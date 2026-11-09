package com.bontecou.syncmd.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.storage.CloneStorage
import com.bontecou.syncmd.ui.components.addVaultInitials
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Avatar
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3LeadingTile
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/**
 * Board SignIn: welcome (browser sign-in, waiting on the browser, browser or verification errors),
 * personal access token (with its error), "GitHub account connected" when adding an account, and the
 * first-run default save location.
 */
@Composable
fun LoginScreen(
    viewModel: GitHubViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel,
    onNavigateBack: (() -> Unit)? = null,
    onContinueAfterLogin: () -> Unit = {},
    addingAccount: Boolean = false,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val isLoading by viewModel.isLoading.collectAsState()
    val signedIn by viewModel.isLoggedIn.collectAsState()
    val revision by viewModel.authManager.sessionRevision.collectAsState()
    val startingRevision = rememberSaveable { viewModel.authManager.sessionRevision.value }
    val isLoggedIn = signedIn && (!addingAccount || revision != startingRevision)
    val error = viewModel.error.collectAsState().value?.asString()
    val user by viewModel.user.collectAsState()
    var showPatFlow by rememberSaveable { mutableStateOf(false) }
    var patToken by remember { mutableStateOf("") }
    var patVisible by remember { mutableStateOf(false) }
    // Waiting on the browser tab: set when it opens, cleared by a result, an error or Back.
    var awaitingBrowser by rememberSaveable { mutableStateOf(false) }
    val isOpeningSample by settingsViewModel.isOpeningSample.collectAsState()
    val sampleError by settingsViewModel.sampleError.collectAsState()
    LaunchedEffect(error, isLoggedIn) { if (error != null || isLoggedIn) awaitingBrowser = false }

    when {
        !isLoggedIn && !showPatFlow -> {
            BackHandler(awaitingBrowser) { awaitingBrowser = false }
            LoginWelcomeContent(
                error = error,
                signingIn = awaitingBrowser || isLoading,
                showSample = !addingAccount,
                isOpeningSample = isOpeningSample,
                sampleError = sampleError,
                onBack = when {
                    awaitingBrowser -> ({ awaitingBrowser = false })
                    addingAccount && onNavigateBack != null -> onNavigateBack
                    else -> null
                },
                onGitHub = {
                    viewModel.clearError()
                    val url = viewModel.generateOAuthUrl()
                    if (url != null) runCatching {
                        CustomTabsIntent.Builder().setShowTitle(true).build()
                            // AndroidX 1.9 names this protocol extra; supported browsers also honor
                            // it with 1.8. Unsupported browsers keep their ordinary Custom Tab behavior.
                            .also { it.intent.putExtra("androidx.browser.customtabs.extra.ENABLE_EPHEMERAL_BROWSING", true) }
                            .launchUrl(context, Uri.parse(url))
                    }.onSuccess { awaitingBrowser = true }.onFailure { viewModel.browserLaunchFailed() }
                },
                onToken = { viewModel.clearError(); awaitingBrowser = false; showPatFlow = true },
                onSample = { settingsViewModel.openSampleRepository(onContinueAfterLogin) },
            )
        }
        !isLoggedIn -> {
            BackHandler { showPatFlow = false; patToken = ""; viewModel.clearError() }
            LoginTokenContent(
                token = patToken, onToken = { patToken = it }, visible = patVisible, onToggleVisible = { patVisible = !patVisible },
                error = error, isLoading = isLoading,
                onBack = { showPatFlow = false; patToken = ""; viewModel.clearError() },
                onCreateToken = {
                    runCatching { CustomTabsIntent.Builder().build().launchUrl(context,
                        Uri.parse("https://github.com/settings/tokens/new?scopes=repo,user:email&description=Gitsync.md")) }
                },
                onSubmit = { focusManager.clearFocus(); viewModel.signInWithPAT(patToken.trim()) },
            )
        }
        addingAccount -> LoginConnectedContent(login = user?.login, name = user?.name, error = error, onDone = onContinueAfterLogin)
        else -> LoginSaveLocationContent(
            login = user?.login, error = error,
            location = runCatching { CloneStorage.defaultCloneBaseDir(context) }.getOrDefault(stringResource(R.string.account_choose_storage)),
            onContinue = onContinueAfterLogin,
        )
    }
}

// ─── Welcome ────────────────────────────────────────────────────────────────

@Composable
private fun LoginWelcomeContent(
    error: String?, signingIn: Boolean, showSample: Boolean, isOpeningSample: Boolean, sampleError: String?,
    onBack: (() -> Unit)?, onGitHub: () -> Unit, onToken: () -> Unit, onSample: () -> Unit,
) {
    val c = M3.colors
    LoginPage {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) M3IconButton(R.drawable.ic_arrow_back, stringResource(R.string.add_signin_back_accounts), onBack, Modifier.offset(x = (-12).dp), tint = c.on)
            LoginWordmark()
        }
        LoginRail(Modifier.padding(top = 20.dp))
        Text(stringResource(R.string.add_signin_eyebrow), style = M3Type.metaSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
            color = c.onVar, modifier = Modifier.padding(top = 28.dp))
        Text(buildAnnotatedString {
            append(stringResource(R.string.add_signin_head_1)); append('\n')
            append(stringResource(R.string.add_signin_head_2)); append('\n')
            withStyle(SpanStyle(color = c.onVar)) { append(stringResource(R.string.add_signin_head_3)) }
        }, style = M3Type.displayMedium.copy(fontSize = 40.sp, lineHeight = 46.sp), color = c.on,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Text(stringResource(R.string.add_signin_body), style = M3Type.body, color = c.onVar, modifier = Modifier.padding(top = 14.dp))
        if (error != null) LoginAlert(error, Modifier.padding(top = 16.dp))
        Spacer(Modifier.weight(1f).heightIn(min = 20.dp))
        M3Button(stringResource(if (signingIn) R.string.add_signin_busy else R.string.add_signin_github), onGitHub,
            Modifier.fillMaxWidth().testTag("login-github"), size = M3ButtonSize.Hero, icon = if (signingIn) null else R.drawable.ic_github,
            loading = false)
        if (signingIn) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            com.bontecou.syncmd.ui.theme.M3Spinner(color = c.onVar, size = 14.dp)
            Text(stringResource(R.string.add_signin_waiting), style = M3Type.bodySmall, color = c.onVar, textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 8.dp).testTag("login-waiting"))
        }
        M3Button(stringResource(R.string.add_signin_token), onToken, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("login-token"),
            style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large)
        if (showSample && !signingIn) {
            M3Button(stringResource(if (isOpeningSample) R.string.add_signin_sample_busy else R.string.add_signin_sample), onSample,
                Modifier.fillMaxWidth().padding(top = 4.dp).testTag("login-sample"), style = M3ButtonStyle.Text, enabled = !isOpeningSample)
            Text(sampleError ?: stringResource(R.string.add_signin_sample_hint), style = M3Type.meta, textAlign = TextAlign.Center,
                color = if (sampleError != null) c.error else c.onVar, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** "gitsync.md": 22/600, ".md" in primary. */
@Composable
private fun LoginWordmark(modifier: Modifier = Modifier) {
    val c = M3.colors
    Text(buildAnnotatedString { append("gitsync"); withStyle(SpanStyle(color = c.primary)) { append(".md") } }, modifier = modifier,
        style = M3Type.wordmark, color = c.on)
}

/** This phone and GitHub meeting at one sync point: the inverse card on SignIn's welcome. */
@Composable
private fun LoginRail(modifier: Modifier = Modifier) {
    val c = M3.colors
    val description = stringResource(R.string.add_signin_rail)
    Column(modifier.fillMaxWidth().background(c.inv, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 24.dp)
        .clearAndSetSemantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.add_rail_phone), style = M3Type.metaSmall, color = c.invMeta, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.add_rail_github), style = M3Type.metaSmall, color = c.invMeta)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp).height(28.dp)) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().padding(top = 13.dp).height(2.dp).background(c.invLine))
            Box(Modifier.offset(y = 7.dp).size(14.dp).background(c.invAhead, CircleShape))
            Box(Modifier.offset(x = w * 0.24f, y = 9.dp).size(10.dp).background(c.invAhead, CircleShape))
            Box(Modifier.offset(x = w / 2 - 14.dp).size(28.dp).background(c.inv, CircleShape).border(2.dp, c.onInv, CircleShape), contentAlignment = Alignment.Center) {
                M3Icon(R.drawable.ic_m3_check, tint = c.onInv, size = 16.dp)
            }
            Box(Modifier.offset(x = w * 0.76f - 10.dp, y = 9.dp).size(10.dp).background(c.inv, CircleShape).border(2.dp, c.invBehind, CircleShape))
            Box(Modifier.offset(x = w - 14.dp, y = 7.dp).size(14.dp).background(c.inv, CircleShape).border(2.dp, c.invBehind, CircleShape))
        }
    }
}

@Composable
private fun LoginAlert(text: String, modifier: Modifier = Modifier) {
    val c = M3.colors
    Row(modifier.fillMaxWidth().background(c.ec, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp).testTag("login-error"),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        M3Icon(R.drawable.ic_m3_warning, tint = c.onEc, size = 20.dp)
        Text(text, style = M3Type.bodyMedium.copy(fontWeight = FontWeight.Medium), color = c.onEc, modifier = Modifier.weight(1f))
    }
}

// ─── Token ──────────────────────────────────────────────────────────────────

@Composable
private fun LoginTokenContent(
    token: String, onToken: (String) -> Unit, visible: Boolean, onToggleVisible: () -> Unit, error: String?, isLoading: Boolean,
    onBack: () -> Unit, onCreateToken: () -> Unit, onSubmit: () -> Unit,
) {
    val c = M3.colors
    LoginPage {
        M3IconButton(R.drawable.ic_arrow_back, stringResource(R.string.publication_back), onBack, Modifier.offset(x = (-12).dp).testTag("login-token-back"), tint = c.on)
        Text(stringResource(R.string.add_token_title), style = M3Type.headline.copy(fontSize = 32.sp, lineHeight = 40.sp), color = c.on,
            modifier = Modifier.padding(top = 20.dp).semantics { heading() })
        Text(stringResource(R.string.add_token_body), style = M3Type.body, color = c.onVar, modifier = Modifier.padding(top = 6.dp))
        if (error != null) LoginAlert(error, Modifier.padding(top = 20.dp))
        OutlinedTextField(token, onToken, label = { Text(stringResource(R.string.add_token_label)) },
            placeholder = { Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_ghp_af5282c5), style = M3Type.code.copy(fontSize = 15.sp)) }, singleLine = true, isError = error != null,
            textStyle = M3Type.code.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.on),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (token.isNotBlank()) onSubmit() }),
            trailingIcon = { M3Button(stringResource(if (visible) R.string.add_token_hide else R.string.add_token_show), onToggleVisible,
                style = M3ButtonStyle.Text, modifier = Modifier.testTag("login-token-visibility")) },
            shape = RoundedCornerShape(4.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedBorderColor = c.primary, unfocusedBorderColor = c.primary, errorBorderColor = c.error, focusedLabelColor = c.primary,
                unfocusedLabelColor = c.primary, errorLabelColor = c.error, cursorColor = c.primary, focusedTextColor = c.on, unfocusedTextColor = c.on),
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp).testTag("login-pat-token"))
        M3Button(stringResource(R.string.add_token_create), onCreateToken, Modifier.padding(top = 8.dp), style = M3ButtonStyle.Text, icon = R.drawable.ic_m3_open_in_new)
        Text(stringResource(R.string.add_token_scopes), style = M3Type.bodySmall, color = c.onVar, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
        M3Button(stringResource(R.string.add_token_sign_in), onSubmit, Modifier.fillMaxWidth().testTag("login-pat-submit"), size = M3ButtonSize.Hero,
            enabled = token.isNotBlank(), loading = isLoading)
    }
}

// ─── Connected / save location ──────────────────────────────────────────────

@Composable
private fun LoginConnectedContent(login: String?, name: String?, error: String?, onDone: () -> Unit) {
    val c = M3.colors
    LoginPage {
        Box(Modifier.padding(top = 88.dp).size(56.dp).background(c.okc, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            M3Icon(R.drawable.ic_m3_check, tint = c.onOkc, size = 28.dp)
        }
        Text(stringResource(R.string.add_connected_title), style = M3Type.headline.copy(fontSize = 32.sp, lineHeight = 40.sp), color = c.on,
            modifier = Modifier.padding(top = 20.dp).semantics { heading() })
        if (login != null) Text("@$login", style = M3Type.code, color = c.onVar, modifier = Modifier.padding(top = 8.dp))
        if (error != null) LoginAlert(error, Modifier.padding(top = 16.dp))
        Row(Modifier.fillMaxWidth().padding(top = 28.dp).background(c.cLow, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            M3Avatar(addVaultInitials(name, login), size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(name ?: login?.let { "@$it" }.orEmpty(), style = M3Type.bodyStrong, color = c.on)
                Text(stringResource(R.string.add_connected_added), style = M3Type.bodySmall, color = c.onVar)
            }
        }
        Spacer(Modifier.weight(1f))
        M3Button(stringResource(R.string.add_done), onDone, Modifier.fillMaxWidth().testTag("account-login-done"), size = M3ButtonSize.Hero)
    }
}

@Composable
private fun LoginSaveLocationContent(login: String?, error: String?, location: String, onContinue: () -> Unit) {
    val c = M3.colors
    LoginPage {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            LoginWordmark(Modifier.weight(1f))
            if (login != null) Text("@$login", style = M3Type.meta, color = c.onVar)
        }
        Text(buildAnnotatedString {
            append(stringResource(R.string.add_location_head)); append(' ')
            withStyle(SpanStyle(color = c.onVar)) { append(stringResource(R.string.add_location_head_muted)) }
        }, style = M3Type.displayMedium.copy(fontSize = 40.sp, lineHeight = 46.sp), color = c.on, modifier = Modifier.padding(top = 48.dp).semantics { heading() })
        Text(stringResource(R.string.add_location_body), style = M3Type.body, color = c.onVar, modifier = Modifier.padding(top = 12.dp))
        if (error != null) LoginAlert(error, Modifier.padding(top = 16.dp))
        M3SectionLabel(stringResource(R.string.add_location_label), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 4.dp, end = 4.dp, top = 28.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().background(c.cLow, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            M3LeadingTile(R.drawable.ic_m3_folder, iconSize = 22.dp)
            Text(location, style = M3Type.code, color = c.on, modifier = Modifier.weight(1f).testTag("login-location"))
        }
        Spacer(Modifier.weight(1f))
        M3Button(stringResource(R.string.add_continue), onContinue, Modifier.fillMaxWidth().testTag("login-continue"), size = M3ButtonSize.Hero)
    }
}

/** Board padding 16/24/24 on the surface, scrolling when the keyboard or a small screen needs it. */
@Composable
private fun LoginPage(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(M3.colors.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp)
            .heightIn(min = maxHeight - 40.dp), content = content)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Preview(name = "Sign in · welcome", widthDp = 412, heightDp = 915) @Composable
private fun WelcomePreview() = SyncMdTheme(darkTheme = false) { LoginWelcomeContent(null, false, true, false, null, null, {}, {}, {}) }

@Preview(name = "Sign in · waiting · dark", widthDp = 412, heightDp = 915) @Composable
private fun WelcomeBusyPreview() = SyncMdTheme(darkTheme = true) { LoginWelcomeContent(null, true, true, false, null, {}, {}, {}, {}) }

@Preview(name = "Sign in · browser error", widthDp = 412, heightDp = 915) @Composable
private fun WelcomeErrorPreview() = SyncMdTheme(darkTheme = false) {
    LoginWelcomeContent("Couldn’t open the browser. Retry, or use a personal access token.", false, true, false, null, null, {}, {}, {})
}

@Preview(name = "Sign in · token error · dark", widthDp = 412, heightDp = 915) @Composable
private fun TokenErrorPreview() = SyncMdTheme(darkTheme = true) {
    LoginTokenContent("ghp_x7Kq2", {}, false, {}, "GitHub token is invalid or expired (HTTP 401).", false, {}, {}, {})
}

@Preview(name = "Sign in · save location", widthDp = 412, heightDp = 915) @Composable
private fun SaveLocationPreview() = SyncMdTheme(darkTheme = false) { LoginSaveLocationContent("CodyBontecou", null, "/storage/emulated/0/GitSync.md", {}) }

@Preview(name = "Sign in · connected", widthDp = 412, heightDp = 915) @Composable
private fun ConnectedPreview() = SyncMdTheme(darkTheme = false) { LoginConnectedContent("CodyBontecou", "Cody Bontecou", null, {}) }
