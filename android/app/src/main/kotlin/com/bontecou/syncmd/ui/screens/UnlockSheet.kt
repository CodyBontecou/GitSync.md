package com.bontecou.syncmd.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.syncmd.R
import com.bontecou.syncmd.billing.AppUnlock
import com.bontecou.syncmd.billing.UnlockPurchase
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.util.asString
import kotlinx.coroutines.launch

private const val TERMS_URL = "https://gitsync.md/terms"

/**
 * The dark one-time unlock sheet (port of iOS `UnlockSheet`). A finished purchase or
 * restore flips [AppUnlock.isUnlocked]; the gate then closes this sheet and continues.
 */
@Composable
fun UnlockSheet(unlock: AppUnlock, vaultName: String?, onDismiss: () -> Unit) {
    val purchase by unlock.purchase.collectAsState()
    val restoring by unlock.restoring.collectAsState()
    val price by unlock.price.collectAsState()
    val busy = purchase == UnlockPurchase.Purchasing || restoring
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val parentDirection = LocalLayoutDirection.current
    val parentDensity = LocalDensity.current
    val bars = activitySystemBarPadding()
    androidx.compose.runtime.LaunchedEffect(unlock) { unlock.clearFailure(); unlock.loadOffer() }
    val dismiss = { if (!busy) { unlock.clearFailure(); onDismiss() } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      androidx.compose.runtime.CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides parentConfiguration,
          LocalLayoutDirection provides parentDirection, LocalDensity provides parentDensity) {
        val c = Tide.colors
        Box(Modifier.fillMaxSize().background(Color(0xFF111113).copy(alpha = 0.55f)), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .background(c.heroBg, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .padding(bottom = bars.calculateBottomPadding()).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp).testTag("unlock-sheet"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val closeLabel = stringResource(R.string.paywall_close)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !busy, role = Role.Button, onClick = dismiss)
                        .semantics { contentDescription = closeLabel }.testTag("unlock-close"), contentAlignment = Alignment.Center) {
                        Text("×", color = c.heroMeta, style = TideType.sans(26.sp))
                    }
                }
                Text(stringResource(R.string.paywall_unlock_title), color = c.heroText, style = TideType.sans(30.sp, FontWeight.SemiBold).copy(lineHeight = 34.sp),
                    modifier = Modifier.semantics { heading() }.testTag("unlock-title"))
                Text(stringResource(if (vaultName != null) R.string.paywall_unlock_subtitle_vault else R.string.paywall_unlock_subtitle),
                    color = c.heroMeta, style = TideType.sans(15.sp).copy(lineHeight = 21.sp))
                UnlockPoint(stringResource(R.string.paywall_point_clone_title), stringResource(R.string.paywall_point_clone_body))
                UnlockPoint(stringResource(R.string.paywall_point_signin_title), stringResource(R.string.paywall_point_signin_body))
                UnlockPoint(stringResource(R.string.paywall_point_again_title), stringResource(R.string.paywall_point_again_body))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(price ?: stringResource(R.string.paywall_price_fallback), color = c.heroText, style = TideType.sans(28.sp, FontWeight.SemiBold),
                        modifier = Modifier.testTag("unlock-price"))
                    Text(stringResource(R.string.paywall_price_note), color = c.heroMeta, style = TideType.mono(12.sp), modifier = Modifier.padding(bottom = 5.dp))
                }
                when (val state = purchase) {
                    UnlockPurchase.Pending -> Text(stringResource(R.string.paywall_pending), color = c.heroText, style = TideType.sans(14.sp),
                        modifier = Modifier.testTag("unlock-pending"))
                    is UnlockPurchase.Failed -> Text(state.message.asString(), color = Color(0xFFFF8A80), style = TideType.sans(14.sp),
                        modifier = Modifier.testTag("unlock-error"))
                    else -> Unit
                }
                val buyLabel = vaultName?.let { stringResource(R.string.paywall_buy_vault, it) } ?: stringResource(R.string.paywall_buy)
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(CircleShape).background(c.heroText.copy(alpha = if (busy) 0.5f else 1f))
                    .clickable(enabled = !busy, role = Role.Button) { context.findActivity()?.let { activity -> scope.launch { unlock.buy(activity) } } }
                    .padding(horizontal = 20.dp, vertical = 16.dp).testTag("unlock-buy"), contentAlignment = Alignment.Center) {
                    Text(buyLabel, color = c.heroBg, style = TideType.sans(16.sp, FontWeight.SemiBold))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.paywall_restore), color = c.heroText, style = TideType.sans(14.sp, FontWeight.Medium),
                        modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = !busy, role = Role.Button) { scope.launch { unlock.restore() } }
                            .padding(vertical = 14.dp).testTag("unlock-restore"))
                    Text(stringResource(R.string.paywall_terms), color = c.heroMeta, style = TideType.sans(14.sp),
                        modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL))) }
                        }.padding(vertical = 14.dp).testTag("unlock-terms"))
                }
                Text(stringResource(R.string.paywall_bought_before), color = c.heroMeta, style = TideType.sans(12.sp).copy(lineHeight = 17.sp))
                Spacer(Modifier.padding(bottom = 4.dp))
            }
        }
      }
    }
}

@Composable
private fun UnlockPoint(title: String, body: String) {
    val c = Tide.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).background(c.synced, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = c.heroText, style = TideType.sans(15.sp, FontWeight.SemiBold))
            Text(body, color = c.heroMeta, style = TideType.sans(13.sp).copy(lineHeight = 18.sp))
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
