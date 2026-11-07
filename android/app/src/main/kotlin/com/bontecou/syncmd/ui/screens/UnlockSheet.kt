package com.bontecou.syncmd.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.syncmd.R
import com.bontecou.syncmd.billing.AppUnlock
import com.bontecou.syncmd.billing.UnlockPurchase
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.asString
import kotlinx.coroutines.launch

private const val TERMS_URL = "https://gitsync.md/terms"

/**
 * Paywall board P3 "Unlock sheet — Google Play" (port of iOS `UnlockSheet`). A finished purchase or
 * restore flips [AppUnlock.isUnlocked]; the gate then closes this sheet and continues. [detail] is the
 * checked vault's size ("238 MB") for the GitHub → Obsidian route card.
 */
@Composable
fun UnlockSheet(unlock: AppUnlock, vaultName: String?, onDismiss: () -> Unit, detail: String? = null) {
    val purchase by unlock.purchase.collectAsState()
    val restoring by unlock.restoring.collectAsState()
    val price by unlock.price.collectAsState()
    val busy = purchase == UnlockPurchase.Purchasing || restoring
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val parentDirection = LocalLayoutDirection.current
    val parentDensity = LocalDensity.current
    val activityView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(unlock) { unlock.clearFailure(); unlock.loadOffer() }
    val dismiss = { if (!busy) { unlock.clearFailure(); onDismiss() } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = false,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      androidx.compose.runtime.CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides parentConfiguration,
          LocalLayoutDirection provides parentDirection, LocalDensity provides parentDensity) {
        val c = M3.colors
        Box(Modifier.fillMaxSize().background(c.scrim), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .background(c.cLow, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .dialogSystemBarPadding(activityView, top = false).verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp).testTag("unlock-sheet"),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 32.dp, height = 4.dp).background(c.outline, RoundedCornerShape(2.dp)))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    M3IconButton(R.drawable.ic_m3_close, stringResource(R.string.paywall_close), dismiss, Modifier.testTag("unlock-close"), enabled = !busy)
                }
                if (vaultName != null) UnlockRoute(vaultName, detail)
                val title = stringResource(R.string.paywall_unlock_title).split('\n', limit = 2)
                Text(buildAnnotatedString {
                    append(title[0])
                    if (title.size > 1) { append('\n'); withStyle(SpanStyle(color = c.onVar)) { append(title[1]) } }
                }, color = c.on, style = M3Type.displaySmall.copy(fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp),
                    modifier = Modifier.padding(top = 20.dp).semantics { heading() }.testTag("unlock-title"))
                Text(stringResource(if (vaultName != null) R.string.paywall_unlock_subtitle_vault else R.string.paywall_unlock_subtitle),
                    color = c.onVar, style = M3Type.body.copy(fontSize = 15.sp, lineHeight = 22.sp), modifier = Modifier.padding(top = 8.dp))
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    UnlockPoint(stringResource(R.string.paywall_point_clone_title), stringResource(R.string.paywall_point_clone_body))
                    UnlockPoint(stringResource(R.string.paywall_point_signin_title), stringResource(R.string.paywall_point_signin_body))
                    UnlockPoint(stringResource(R.string.paywall_point_again_title), stringResource(R.string.paywall_point_again_body))
                }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp).background(c.cHighest, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(price ?: stringResource(R.string.paywall_price_fallback), color = c.on, style = M3Type.headline.copy(letterSpacing = 0.sp),
                            modifier = Modifier.testTag("unlock-price"))
                        Text(stringResource(R.string.paywall_price_note), color = c.onVar, style = M3Type.caption)
                    }
                    Text(stringResource(R.string.m3_paywall_paid_with_play), color = c.onVar, style = M3Type.caption,
                        textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 140.dp))
                }
                when (val state = purchase) {
                    UnlockPurchase.Pending -> Text(stringResource(R.string.paywall_pending), color = c.on, style = M3Type.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp).testTag("unlock-pending"))
                    is UnlockPurchase.Failed -> Text(state.message.asString(), color = c.error, style = M3Type.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp).testTag("unlock-error"))
                    else -> Unit
                }
                val buyLabel = vaultName?.let { stringResource(R.string.paywall_buy_vault, it) } ?: stringResource(R.string.paywall_buy)
                M3Button(buyLabel, onClick = { context.findActivity()?.let { activity -> scope.launch { unlock.buy(activity) } } },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("unlock-buy"), size = M3ButtonSize.Hero,
                    enabled = !restoring, loading = purchase == UnlockPurchase.Purchasing)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                    M3Button(stringResource(R.string.paywall_restore), { scope.launch { unlock.restore() } }, Modifier.testTag("unlock-restore"),
                        style = M3ButtonStyle.Text, enabled = !busy, loading = restoring)
                    M3Button(stringResource(R.string.paywall_terms), { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL))) } },
                        Modifier.testTag("unlock-terms"), style = M3ButtonStyle.Text)
                }
                Text(stringResource(R.string.paywall_bought_before), color = c.onVar, style = M3Type.caption,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
      }
    }
}

/** The inverse route card: GitHub ── 🔒 ── Obsidian, "notes · 238 MB · checked, ready to clone". */
@Composable
private fun UnlockRoute(name: String, detail: String?) {
    val c = M3.colors
    val description = stringResource(R.string.add_unlock_rail_cd, name)
    Column(Modifier.fillMaxWidth().background(c.inv, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp)
        .clearAndSetSemantics { contentDescription = description }.testTag("unlock-route")) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.add_unlock_rail_from), style = M3Type.metaSmall, color = c.invMeta, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.add_unlock_rail_to), style = M3Type.metaSmall, color = c.invMeta)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).border(2.dp, c.invBehind, CircleShape))
            Box(Modifier.weight(1f).height(2.dp).background(c.invBehind))
            Box(Modifier.size(28.dp).border(2.dp, c.onInv, CircleShape), contentAlignment = Alignment.Center) {
                M3Icon(R.drawable.ic_m3_lock, tint = c.onInv, size = 14.dp)
            }
            Box(Modifier.weight(1f).height(2.dp).background(c.invLine))
            Box(Modifier.size(10.dp).border(2.dp, c.invLine, CircleShape))
        }
        Text(if (detail != null) stringResource(R.string.add_unlock_rail, name, detail) else stringResource(R.string.add_unlock_rail_plain, name),
            style = M3Type.metaSmall, color = c.invMeta, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun UnlockPoint(title: String, body: String) {
    val c = M3.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        M3Icon(R.drawable.ic_m3_check, tint = c.ok, size = 20.dp, modifier = Modifier.padding(top = 1.dp))
        Column {
            Text(title, color = c.on, style = M3Type.step.copy(fontWeight = FontWeight.SemiBold))
            Text(body, color = c.onVar, style = M3Type.bodySmall)
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
