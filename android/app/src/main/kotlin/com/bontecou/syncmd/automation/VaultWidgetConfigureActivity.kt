package com.bontecou.syncmd.automation

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pick the vault a single-vault widget shows. Cancelling leaves the widget unplaced. */
class VaultWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        enableEdgeToEdge()
        setContent {
            SyncMdTheme {
                VaultPicker(onBack = ::finish) { item ->
                    VaultWidgets.assign(this, widgetId, item.path)
                    AppWidgetManager.getInstance(this).updateAppWidget(widgetId, VaultWidgets.vaultViews(this, widgetId, item))
                    VaultWidgets.refresh(this)
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                }
            }
        }
    }
}

@Composable
private fun VaultPicker(onBack: () -> Unit, onPick: (VaultSurfaceItem) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<VaultSurfaceItem>?>(null) }
    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { VaultWidgets.items(context) } }
    VaultPickerContent(items, onBack, onPick)
}

/** The production picker body, also exercised with a long synthetic catalog in inset tests. */
@Composable
internal fun VaultPickerContent(items: List<VaultSurfaceItem>?, onBack: () -> Unit, onPick: (VaultSurfaceItem) -> Unit) {
    val context = LocalContext.current
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).navigationBarsPadding().displayCutoutPadding()) {
        M3TopBar(stringResource(R.string.widget_m3_choose_vault), subtitle = stringResource(R.string.widget_m3_vault_widget), onBack = onBack,
            backIcon = R.drawable.ic_m3_close)
        val list = items
        when {
            list == null -> M3IndeterminateProgressBar(Modifier.padding(16.dp))
            list.isEmpty() -> M3Note(stringResource(R.string.pullall_m3_empty), Modifier.padding(16.dp), tone = M3NoteTone.Muted)
            else -> LazyColumn(Modifier.testTag("widget-vault-list"), contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
                itemsIndexed(list, key = { _, it -> it.path }) { index, item ->
                    val ahead = item.status?.toPush ?: 0
                    M3ListRow(item.name, Modifier.padding(horizontal = 16.dp).m3GroupedItem(index, list.size, c.cLow).testTag("widget-vault-$index"),
                        meta = listOfNotNull(item.status?.branch, VaultWidgets.ago(context, item.status?.lastSync)).joinToString(" · ").ifBlank { null },
                        leading = { M3LeadingAvatar(item.name.take(1).uppercase(), if (ahead > 0) M3Tone.Ahead else M3Tone.Ok) },
                        trailing = { if (ahead > 0) M3Badge("↑$ahead", M3Tone.Ahead, mono = true) else if (item.status != null) M3Badge(stringResource(R.string.widget_m3_in_sync), M3Tone.Ok) },
                        onClick = { onPick(item) }, divider = index > 0, titleStyle = M3Type.bodyStrong)
                }
            }
        }
    }
}
