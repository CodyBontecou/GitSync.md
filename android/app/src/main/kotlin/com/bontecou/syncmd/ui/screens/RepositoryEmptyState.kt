package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.LocalBrutalColors

/**
 * Empty state shown when no repository is selected.
 * Matches the iOS brutal design: "—" dash + uppercase title + action buttons.
 */
@Composable
fun RepositoryEmptyState(
    onNavigateToSettings: () -> Unit,
    onConnectGitHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bc = LocalBrutalColors.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bc.bg)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .padding(vertical = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // "—" dash
            Text(
                text = "—",
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Black,
                    fontSize = 72.sp,
                    color = bc.text,
                ),
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Title
            Text(
                text = "NO REPOSITORY",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 2.sp,
                    color = bc.text,
                ),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Subtitle
            Text(
                text = "Connect a GitHub repository to start syncing.",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    color = bc.textMid,
                ),
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Paid-app note
            Text(
                text = "FULL VERSION · NO SUBSCRIPTION",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    color = bc.accent,
                ),
                modifier = Modifier.padding(bottom = 32.dp),
            )

            // Primary CTA — GitHub
            BPrimaryButton(
                title   = "Connect with GitHub",
                onClick = onConnectGitHub,
            )

            Spacer(Modifier.height(20.dp))

            // Divider "or"
            BDivider(label = "or")

            Spacer(Modifier.height(20.dp))

            // Secondary CTA — manual
            BSecondaryButton(
                title   = "Add Local Repository",
                onClick = onNavigateToSettings,
            )
        }
    }
}
