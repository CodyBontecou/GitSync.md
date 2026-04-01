package com.bontecou.syncmd.ui.screens

import android.app.Activity
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.billing.PurchaseManager
import com.bontecou.syncmd.billing.PurchaseViewModel
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BCardRow
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
    purchaseViewModel: PurchaseViewModel = hiltViewModel(),
) {
    val bc       = LocalBrutalColors.current
    val activity = LocalContext.current as Activity

    val appSettings        by viewModel.appSettings.collectAsState()
    val allRepos           by viewModel.allRepositories.collectAsState()
    val isLoading          by viewModel.isLoading.collectAsState()
    val errorMessage       by viewModel.errorMessage.collectAsState()

    val isUnlocked    by purchaseViewModel.isUnlocked.collectAsState()
    val isPurchasing  by purchaseViewModel.isPurchasing.collectAsState()
    val isRestoring   by purchaseViewModel.isRestoring.collectAsState()
    val purchaseError by purchaseViewModel.purchaseError.collectAsState()
    val productDetails by purchaseViewModel.productDetails.collectAsState()

    LaunchedEffect(Unit) {
        purchaseViewModel.refreshStatus()
        if (productDetails == null) purchaseViewModel.loadProduct()
    }

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
                    Text("←", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = bc.accent))
                    Text("BACK", style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 1.sp, color = bc.accent))
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
                                        title   = "Sign in with GitHub",
                                        onClick = onNavigateToLogin,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Premium Section ───────────────────────────────────────
                item { BSectionHeader(title = "Premium") }

                item {
                    PremiumCard(
                        isUnlocked     = isUnlocked,
                        isPurchasing   = isPurchasing,
                        isRestoring    = isRestoring,
                        purchaseError  = purchaseError,
                        productDetails = productDetails,
                        reposUsed      = allRepos.size,
                        reposEverAdded = purchaseViewModel.uniqueReposEverAdded,
                        onUnlock       = {
                            purchaseViewModel.clearPurchaseError()
                            purchaseViewModel.purchase(activity)
                        },
                        onRestore      = {
                            purchaseViewModel.clearPurchaseError()
                            purchaseViewModel.restore()
                        },
                    )
                }

                // ── App Settings Section ──────────────────────────────────
                item { BSectionHeader(title = "App Settings") }

                item {
                    BCard {
                        Column {
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
                            BMonoRow(key = "App",     value = "Sync.md")
                            BDivider()
                            BMonoRow(key = "Platform", value = "Android")
                            BDivider()
                            BMonoRow(key = "Version", value = "1.0.0")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
        } // end Column
    }

}

// ─── Premium card ─────────────────────────────────────────────────────────────

@Composable
private fun PremiumCard(
    isUnlocked:     Boolean,
    isPurchasing:   Boolean,
    isRestoring:    Boolean,
    purchaseError:  String?,
    productDetails: com.android.billingclient.api.ProductDetails?,
    reposUsed:      Int,
    reposEverAdded: Int,
    onUnlock:       () -> Unit,
    onRestore:      () -> Unit,
) {
    val bc = LocalBrutalColors.current

    BCard {
        Column {
            // ── Status header row ───────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text  = if (isUnlocked) "Sync.md Pro" else "Sync.md Free",
                        style = TextStyle(
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Black,
                            fontSize   = 18.sp,
                            color      = bc.text,
                        )
                    )
                    Text(
                        text  = if (isUnlocked)
                            "Unlimited repositories"
                        else
                            "${reposEverAdded} of ${PurchaseManager.FREE_REPO_LIMIT} free repo used",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 12.sp,
                            color      = bc.textMid,
                        )
                    )
                }

                if (isUnlocked) {
                    BBadge(text = "PRO", style = BBadgeStyle.ACCENT)
                } else {
                    BBadge(text = "FREE", style = BBadgeStyle.DEFAULT)
                }
            }

            // ── Repo usage bar (free tier only) ─────────────────────────
            if (!isUnlocked) {
                BDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text  = "REPOSITORIES",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize   = 11.sp,
                                letterSpacing = 1.sp,
                                color = bc.textMid,
                            )
                        )
                        Text(
                            text  = "$reposEverAdded / ${PurchaseManager.FREE_REPO_LIMIT}",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize   = 11.sp,
                                letterSpacing = 1.sp,
                                color = if (reposEverAdded >= PurchaseManager.FREE_REPO_LIMIT)
                                    bc.error else bc.textMid,
                            )
                        )
                    }
                    // Progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(bc.surface)
                            .border(1.dp, bc.border)
                    ) {
                        val fill = (reposEverAdded.toFloat() / PurchaseManager.FREE_REPO_LIMIT)
                            .coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fill)
                                .height(4.dp)
                                .background(
                                    if (fill >= 1f) bc.error else bc.text
                                )
                        )
                    }
                }
            }

            BDivider()

            // ── Error ─────────────────────────────────────────────────
            if (purchaseError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bc.error.copy(alpha = 0.06f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    val isContactError = purchaseError.contains("cody@isolated.tech")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        BBadge(
                            text  = "ERROR",
                            style = if (isContactError) BBadgeStyle.DEFAULT else BBadgeStyle.ERROR,
                        )
                        Text(
                            text  = purchaseError,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 12.sp,
                                color      = if (isContactError) bc.textMid else bc.error,
                            )
                        )
                    }
                }
                BDivider()
            }

            // ── Actions ───────────────────────────────────────────────
            if (isUnlocked) {
                // Already unlocked — just show restore option
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text  = "🔓",
                            style = TextStyle(fontSize = 18.sp)
                        )
                        Column {
                            Text(
                                text  = "Full access unlocked",
                                style = TextStyle(
                                    fontFamily = FontFamily.Default,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize   = 15.sp,
                                    color      = bc.text,
                                )
                            )
                            Text(
                                text  = "Thank you for supporting Sync.md!",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize   = 12.sp,
                                    color      = bc.textMid,
                                )
                            )
                        }
                    }
                }
                BDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onRestore,
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text  = if (isRestoring) "Checking…" else "Restore Purchase",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            fontSize   = 13.sp,
                            color      = bc.textMid,
                        )
                    )
                }
            } else {
                // Not unlocked — show buy + restore
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BPrimaryButton(
                        title      = unlockButtonLabel(productDetails),
                        isLoading  = isPurchasing,
                        isDisabled = isPurchasing || isRestoring,
                        onClick    = onUnlock,
                    )
                    BSecondaryButton(
                        title      = "Restore Purchase",
                        isLoading  = isRestoring,
                        isDisabled = isPurchasing || isRestoring,
                        onClick    = onRestore,
                    )
                }
            }
        }
    }
}

private fun unlockButtonLabel(
    productDetails: com.android.billingclient.api.ProductDetails?
): String {
    val price = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
    return if (price != null) "Unlock for $price" else "Unlock Unlimited"
}

@Composable
private fun BDivider() {
    com.bontecou.syncmd.ui.theme.BDivider()
}
