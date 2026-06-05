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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.billing.PurchaseViewModel
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.LocalBrutalColors

/**
 * Full-screen paywall — mirrors iOS PaywallView.
 *
 * Shown when the user has consumed their 1 free repository slot and tries to add
 * another. Offers a one-time purchase via Google Play Billing and a restore flow.
 */
@Composable
fun PaywallScreen(
    purchaseViewModel: PurchaseViewModel,
    onDismiss: () -> Unit,
) {
    val bc            = LocalBrutalColors.current
    val activity      = LocalContext.current as Activity
    val isUnlocked    by purchaseViewModel.isUnlocked.collectAsState()
    val isPurchasing  by purchaseViewModel.isPurchasing.collectAsState()
    val isRestoring   by purchaseViewModel.isRestoring.collectAsState()
    val purchaseError by purchaseViewModel.purchaseError.collectAsState()
    val productDetails by purchaseViewModel.productDetails.collectAsState()

    // Auto-dismiss once the user successfully unlocks
    LaunchedEffect(isUnlocked) {
        if (isUnlocked) onDismiss()
    }

    LaunchedEffect(Unit) {
        purchaseViewModel.refreshStatus()
        if (productDetails == null) purchaseViewModel.loadProduct()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bc.bg)
    ) {
        // ── Scrollable body (declared first = rendered below close button) ───
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(36.dp))

            // ── Hero ──────────────────────────────────────────────────────
            Text(
                text = "UNLOCK",
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Black,
                    fontSize = 52.sp,
                    letterSpacing = (-1).sp,
                    color = bc.text,
                )
            )
            Text(
                text = "GITSYNC.MD",
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Black,
                    fontSize = 52.sp,
                    letterSpacing = (-1).sp,
                    color = bc.accent,
                )
            )

            Spacer(Modifier.height(16.dp))

            // Separator line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(bc.border)
            )

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 20.dp, height = 1.dp)
                        .background(bc.border)
                )
                Text(
                    text = "YOU'VE REACHED THE 1 FREE REPOSITORY LIMIT",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = bc.text,
                    )
                )
            }

            Spacer(Modifier.height(32.dp))
            BDivider()
            Spacer(Modifier.height(24.dp))

            // ── What's included ───────────────────────────────────────────
            Text(
                text = "WHAT'S INCLUDED",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    color = bc.textMid,
                )
            )

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, bc.border)
            ) {
                Column {
                    FeatureRow(icon = "📦", text = "Unlimited repositories")
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) { BDivider() }
                    FeatureRow(icon = "🌿", text = "Sync any number of repositories")
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) { BDivider() }
                    FeatureRow(icon = "✨", text = "All future features included")
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) { BDivider() }
                    FeatureRow(icon = "🔓", text = "One-time payment — no subscription")
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Error card ────────────────────────────────────────────────
            if (purchaseError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bc.error.copy(alpha = 0.06f))
                        .border(1.dp, bc.error.copy(alpha = 0.3f))
                        .padding(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val isContactError = purchaseError?.contains("cody@isolated.tech") == true
                        BBadge(
                            text  = "ERROR",
                            style = if (isContactError) BBadgeStyle.DEFAULT else BBadgeStyle.ERROR,
                        )
                        Text(
                            text  = purchaseError ?: "",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 13.sp,
                                color      = if (isContactError) bc.textMid else bc.error,
                            )
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── CTA buttons ───────────────────────────────────────────────
            BPrimaryButton(
                title      = priceLabel(productDetails),
                isLoading  = isPurchasing,
                isDisabled = isPurchasing || isRestoring,
                onClick    = {
                    purchaseViewModel.clearPurchaseError()
                    purchaseViewModel.purchase(activity)
                },
            )

            Spacer(Modifier.height(12.dp))

            BSecondaryButton(
                title      = "Restore Purchase",
                isLoading  = isRestoring,
                isDisabled = isPurchasing || isRestoring,
                onClick    = {
                    purchaseViewModel.clearPurchaseError()
                    purchaseViewModel.restore()
                },
            )

            Spacer(Modifier.height(32.dp))
        }

        // ── Close button — declared last so it renders on top of the scroll content ──
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 20.dp)
                .size(36.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✕",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = bc.textMid,
                )
            )
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

@Composable
private fun FeatureRow(icon: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = icon, style = TextStyle(fontSize = 18.sp))
        Text(
            text  = text,
            style = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize   = 14.sp,
                color      = LocalBrutalColors.current.text,
            )
        )
    }
}

private fun priceLabel(productDetails: com.android.billingclient.api.ProductDetails?): String {
    val price = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
    return if (price != null) "Unlock for $price" else "Unlock Unlimited"
}
