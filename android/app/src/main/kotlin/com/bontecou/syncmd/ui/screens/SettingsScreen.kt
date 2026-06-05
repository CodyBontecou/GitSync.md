package com.bontecou.syncmd.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.storage.CloneStorage
import com.bontecou.syncmd.ui.util.FilesAppLauncher
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.BMonoRow
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.BSectionHeader
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    onNavigateToRepoPicker: () -> Unit = {},
    githubViewModel: GitHubViewModel = hiltViewModel(),
) {
    val bc       = LocalBrutalColors.current

    val appSettings        by viewModel.appSettings.collectAsState()
    val context            = LocalContext.current
    val isLoading          by viewModel.isLoading.collectAsState()
    val errorMessage       by viewModel.errorMessage.collectAsState()

    val isLoggedIn  by githubViewModel.isLoggedIn.collectAsState()
    val githubUser  by githubViewModel.user.collectAsState()
    val cachedLogin by githubViewModel.authManager.cachedLogin.collectAsState()
    val cachedName  by githubViewModel.authManager.cachedName.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(bc.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
        // Top Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bc.bg)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onNavigateBack,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("←", modifier = Modifier.offset(y = 2.dp), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false), color = bc.accent))
                    Text("BACK", style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false), letterSpacing = 1.sp, color = bc.accent))
                }
                Text("SETTINGS", style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 3.sp, color = bc.text))
                Box(Modifier.size(48.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(bc.border))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp, vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Error
            if (errorMessage != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bc.error.copy(alpha = 0.08f))
                            .border(1.dp, bc.error.copy(alpha = 0.4f))
                            .padding(12.dp)
                    ) {
                        Text(text = errorMessage!!, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.error))
                    }
                }
            }

            if (isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        BLoading(text = "Loading")
                    }
                }
            } else {
                // ── GitHub Section ────────────────────────────────────────
                item { BSectionHeader(title = "GitHub") }

                item {
                    BCard {
                        Column {
                            if (isLoggedIn) {
                                // Logged-in state
                                val displayName = githubUser?.name
                                    ?: githubUser?.login
                                    ?: cachedName
                                    ?: cachedLogin
                                    ?: "GitHub User"
                                val loginHandle = githubUser?.login ?: cachedLogin

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = displayName,
                                        style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = bc.text)
                                    )
                                    if (loginHandle != null) {
                                        Text(
                                            text = "@$loginHandle",
                                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textMid)
                                        )
                                    }
                                }
                                BDivider()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        BSecondaryButton(
                                            title   = "Browse Repos",
                                            onClick = onNavigateToRepoPicker,
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        BDestructiveButton(
                                            title   = "Sign Out",
                                            onClick = githubViewModel::signOut,
                                        )
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = "Connect your GitHub account to browse and sync repositories.",
                                        style = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp, color = bc.textMid)
                                    )
                                    BPrimaryButton(
                                        title          = "Sign in with GitHub",
                                        leadingIconRes = R.drawable.ic_github,
                                        onClick        = onNavigateToLogin,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── License Section ───────────────────────────────────────
                item { BSectionHeader(title = "License") }

                item {
                    LicenseCard()
                }

                // ── App Settings Section ──────────────────────────────────
                item { BSectionHeader(title = "App Settings") }

                item {
                    BCard {
                        Column {
                            // Clone directory (fixed)
                            CloneDirRow(
                                defaultPath = CloneStorage.defaultCloneBaseDir(context),
                            )

                            BDivider()

                            // Dark theme toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Dark Theme",
                                        style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = bc.text)
                                    )
                                    Text(
                                        text = "Override system appearance",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textMid)
                                    )
                                }
                                Switch(
                                    checked = appSettings.isDarkTheme,
                                    onCheckedChange = { viewModel.updateSettings(appSettings.copy(isDarkTheme = it)) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor  = bc.bg,
                                        checkedTrackColor  = bc.text,
                                        uncheckedThumbColor = bc.textFaint,
                                        uncheckedTrackColor = bc.surface,
                                        uncheckedBorderColor = bc.border,
                                    )
                                )
                            }

                            BDivider()

                            // Debug info toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Debug Info",
                                        style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = bc.text)
                                    )
                                    Text(
                                        text = "Show debug information",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textMid)
                                    )
                                }
                                Switch(
                                    checked = appSettings.showDebugInfo,
                                    onCheckedChange = { viewModel.updateSettings(appSettings.copy(showDebugInfo = it)) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor  = bc.bg,
                                        checkedTrackColor  = bc.text,
                                        uncheckedThumbColor = bc.textFaint,
                                        uncheckedTrackColor = bc.surface,
                                        uncheckedBorderColor = bc.border,
                                    )
                                )
                            }
                        }
                    }
                }

                // ── About Section ─────────────────────────────────────────
                item { BSectionHeader(title = "About") }

                item {
                    BCard {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BMonoRow(key = "App",     value = "Gitsync.md")
                            BDivider()
                            BMonoRow(key = "Platform", value = "Android")
                            BDivider()
                            BMonoRow(key = "Version", value = "1.0.2")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
        } // end Column
    }

}

// ─── License card ─────────────────────────────────────────────────────────────

@Composable
private fun LicenseCard() {
    val bc = LocalBrutalColors.current

    BCard {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text  = "Gitsync.md Full Version",
                        style = TextStyle(
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Black,
                            fontSize   = 18.sp,
                            color      = bc.text,
                        )
                    )
                    Text(
                        text  = "\$9.99 one-time purchase via Google Play",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 12.sp,
                            color      = bc.textMid,
                        )
                    )
                }
            }

            BDivider()

            Text(
                text = "All features are included after installing the paid app. No subscriptions or in-app purchases.",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                    color      = bc.textMid,
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun BDivider() {
    com.bontecou.syncmd.ui.theme.BDivider()
}

// ─── Clone Directory Row ──────────────────────────────────────────────────────

@Composable
private fun CloneDirRow(
    defaultPath: String,
) {
    val bc = LocalBrutalColors.current
    val context = LocalContext.current
    val displayPath = shortenClonePath(defaultPath)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Clone Directory",
            style = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = bc.text,
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(bc.surface)
                    .border(1.dp, bc.border)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = displayPath,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = bc.text,
                    )
                )
            }
            
            Box(
                modifier = Modifier
                    .background(bc.surface)
                    .border(1.dp, bc.border)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { FilesAppLauncher.openFolder(context, defaultPath) },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Open",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = bc.accent,
                    )
                )
            }
        }

        Text(
            text = "FIXED TO APP STORAGE FOR GIT COMPATIBILITY.",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp,
                color = bc.textFaint,
            )
        )
    }
}

private fun shortenClonePath(path: String): String {
    val prefixes = listOf("/storage/emulated/0/", "/sdcard/")
    for (prefix in prefixes) {
        if (path.startsWith(prefix)) return path.removePrefix(prefix)
    }
    return path
}
