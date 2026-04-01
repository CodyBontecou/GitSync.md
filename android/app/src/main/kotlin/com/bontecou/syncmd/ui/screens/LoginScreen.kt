package com.bontecou.syncmd.ui.screens

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.ui.components.rememberDirectoryPicker
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import androidx.compose.foundation.text.KeyboardActions

/**
 * Full-screen GitHub sign-in screen.
 * Matches the iOS SetupView brutal aesthetic:
 *   • Big black "SYNC / .MD" display title
 *   • Primary: Sign in with GitHub (OAuth)
 *   • Secondary: Personal Access Token
 */
@Composable
fun LoginScreen(
    viewModel: GitHubViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel? = null,
    @Suppress("UNUSED_PARAMETER") onNavigateBack: (() -> Unit)? = null,
    onContinueAfterLogin: () -> Unit = {},
) {
    val bc           = LocalBrutalColors.current
    val context      = LocalContext.current
    val focusManager = LocalFocusManager.current

    val isLoading   by viewModel.isLoading.collectAsState()
    val isLoggedIn  by viewModel.isLoggedIn.collectAsState()
    val error       by viewModel.error.collectAsState()

    val appSettings = settingsViewModel?.appSettings?.collectAsState()?.value

    var showPatFlow   by remember { mutableStateOf(false) }
    var patToken      by remember { mutableStateOf("") }
    var patVisible    by remember { mutableStateOf(false) }
    var cloneDirDraft by remember(appSettings?.defaultCloneDir) {
        mutableStateOf(appSettings?.defaultCloneDir ?: "")
    }
    var cloneDirError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bc.bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 60.dp),
        ) {

            // ── Hero ──────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 60.dp, bottom = 40.dp),
            ) {
                Text(
                    text = "SYNC",
                    style = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Black,
                        fontSize = 72.sp,
                        letterSpacing = (-2).sp,
                        color = bc.text,
                    )
                )
                Text(
                    text = ".MD",
                    style = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Black,
                        fontSize = 72.sp,
                        letterSpacing = (-2).sp,
                        color = bc.accent,
                    ),
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                // Rule
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(bc.border)
                        .padding(bottom = 10.dp)
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.width(20.dp).height(1.dp).background(bc.border))
                    Text(
                        text = "ANY REPO. SYNCED TO YOUR ANDROID.",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                            color = bc.textMid,
                        )
                    )
                }
            }

            // ── Error banner ──────────────────────────────────────────────
            if (error != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .background(bc.error.copy(alpha = 0.08f))
                        .border(1.dp, bc.error.copy(alpha = 0.4f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = error!!,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = bc.error,
                        )
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            if (!isLoggedIn) {
                // ── PAT back button ───────────────────────────────────────────
                AnimatedVisibility(visible = showPatFlow) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {
                                showPatFlow = false
                                patToken = ""
                                viewModel.clearError()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "←",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = bc.text,
                            )
                        )
                        Text(
                            text = "BACK",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp,
                                color = bc.text,
                            )
                        )
                    }
                }
            }

            // ── PAT input ─────────────────────────────────────────────────
            AnimatedVisibility(
                visible = showPatFlow,
                enter   = expandVertically(),
                exit    = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Token input
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bc.surface)
                                .border(1.dp, bc.border)
                                .padding(horizontal = 12.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Manual input row (using BTextField)
                            Box(modifier = Modifier.weight(1f)) {
                                if (patToken.isEmpty()) {
                                    Text(
                                        text = "ghp_...",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 15.sp,
                                            color = bc.textFaint,
                                        )
                                    )
                                }
                                androidx.compose.foundation.text.BasicTextField(
                                    value = patToken,
                                    onValueChange = { patToken = it },
                                    visualTransformation = if (patVisible)
                                        VisualTransformation.None
                                    else
                                        PasswordVisualTransformation(),
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 15.sp,
                                        color = bc.text,
                                    ),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    cursorBrush = SolidColor(bc.text),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            // Show/Hide toggle
                            Text(
                                text = if (patVisible) "HIDE" else "SHOW",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    letterSpacing = 1.sp,
                                    color = bc.accent,
                                ),
                                modifier = Modifier
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                    ) { patVisible = !patVisible }
                                    .padding(start = 12.dp),
                            )
                        }
                        Text(
                            text = "PERSONAL ACCESS TOKEN",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                letterSpacing = 2.sp,
                                color = bc.textMid,
                            )
                        )
                        Text(
                            text = "CREATE A PAT ON GITHUB →",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                letterSpacing = 1.sp,
                                color = bc.accent,
                            ),
                            modifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {
                                CustomTabsIntent.Builder().build().launchUrl(
                                    context,
                                    Uri.parse("https://github.com/settings/tokens/new?scopes=repo,user:email&description=Sync.md")
                                )
                            }
                        )
                    }

                    BPrimaryButton(
                        title     = if (isLoading) "Signing in…" else "Sign In",
                        isLoading = isLoading,
                        isDisabled = patToken.isBlank(),
                        onClick   = {
                            focusManager.clearFocus()
                            viewModel.signInWithPAT(patToken.trim())
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ── OAuth + PAT button (shown when not in PAT flow) ───────────
            AnimatedVisibility(visible = !showPatFlow) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    BPrimaryButton(
                        title  = "Sign in with GitHub",
                        onClick = {
                            val url = viewModel.generateOAuthUrl()
                            CustomTabsIntent.Builder()
                                .setShowTitle(true)
                                .build()
                                .launchUrl(context, Uri.parse(url))
                        }
                    )

                    Box(modifier = Modifier.padding(vertical = 20.dp).fillMaxWidth()) {
                        BDivider(label = "or")
                    }

                    BSecondaryButton(
                        title  = "Personal Access Token",
                        onClick = {
                            viewModel.clearError()
                            showPatFlow = true
                        }
                    )

                    BGhostButton(
                        title   = "Skip for now",
                        modifier = Modifier.fillMaxWidth(),
                        color   = bc.textFaint,
                        onClick = { /* no-op */ },
                    )
                }
            }

            }

            // ── Download location selector (post-login onboarding) ─────────
            if (isLoggedIn && settingsViewModel != null && appSettings != null) {
                Spacer(Modifier.height(8.dp))

                // Divider with label
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                ) {
                    BDivider(label = "onboarding")
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text  = "DOWNLOAD LOCATION",
                        style = TextStyle(
                            fontFamily    = FontFamily.Monospace,
                            fontWeight    = FontWeight.SemiBold,
                            fontSize      = 12.sp,
                            letterSpacing = 2.sp,
                            color         = bc.textMid,
                        )
                    )

                    val defaultPath = "${context.filesDir.absolutePath}/repos"
                    val openDirectoryPicker = rememberDirectoryPicker(
                        onDirectorySelected = { selectedPath ->
                            cloneDirDraft = selectedPath
                            cloneDirError = null
                            settingsViewModel.updateSettings(
                                appSettings.copy(defaultCloneDir = selectedPath)
                            )
                        },
                        onError = { message ->
                            cloneDirError = message
                        },
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bc.surface)
                            .border(1.dp, bc.border)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = cloneDirDraft.ifBlank { defaultPath },
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (cloneDirDraft.isBlank()) bc.textFaint else bc.text,
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            BSecondaryButton(
                                title = "Choose Folder",
                                onClick = {
                                    focusManager.clearFocus()
                                    openDirectoryPicker()
                                }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            BGhostButton(
                                title = "Use Default",
                                onClick = {
                                    cloneDirDraft = ""
                                    cloneDirError = null
                                    settingsViewModel.updateSettings(
                                        appSettings.copy(defaultCloneDir = "")
                                    )
                                }
                            )
                        }
                    }

                    Text(
                        text  = "WHERE REPOS ARE CLONED ON THIS DEVICE · SKIP TO USE DEFAULT",
                        style = TextStyle(
                            fontFamily    = FontFamily.Monospace,
                            fontSize      = 10.sp,
                            letterSpacing = 0.5.sp,
                            color         = bc.textFaint,
                        )
                    )

                    if (cloneDirError != null) {
                        Text(
                            text = cloneDirError!!,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = bc.error,
                            )
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    BPrimaryButton(
                        title = "Continue",
                        onClick = onContinueAfterLogin,
                    )
                }
            }
        }
    }
}


