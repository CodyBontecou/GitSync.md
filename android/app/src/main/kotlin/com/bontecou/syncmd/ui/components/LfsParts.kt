package com.bontecou.syncmd.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3ModalHost
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.colors
import com.bontecou.syncmd.ui.theme.m3TopDivider
import java.util.Locale
import kotlin.math.roundToLong

// Building blocks the Large files, LFS endpoints, SSH hosts and Pull all boards share
// on top of M3Kit. Measurements follow the boards (px = dp).

/** Mono path style that always reads left to right (paths, hosts, fingerprints, URLs). */
internal val LfsMono get() = M3Type.code.copy(textDirection = TextDirection.Ltr)

/**
 * Two-way tab switch (Large files "Files 3 | Locks 2"): 40dp, 1dp outline, 20dp corners; the
 * selected tab is sc/onSc 600 with an 18dp check, a mono 12/500 count follows each label.
 */
@Composable
internal fun LfsTabs(tabs: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    val shape = RoundedCornerShape(20.dp)
    Row(modifier.fillMaxWidth().height(40.dp).clip(shape).border(1.dp, c.outline, shape)) {
        tabs.forEachIndexed { index, (label, meta) ->
            val on = index == selected
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.outline))
            Row(
                Modifier.weight(1f).fillMaxHeight().then(if (on) Modifier.background(c.sc) else Modifier)
                    .selectable(on, role = Role.Tab) { onSelect(index) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                val ink = if (on) c.onSc else c.on
                if (on) { M3Icon(R.drawable.ic_m3_check, tint = ink, size = 18.dp); Spacer(Modifier.width(6.dp)) }
                Text(label, style = M3Type.button.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = ink, maxLines = 1)
                if (meta != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(meta, style = M3Type.meta.copy(fontWeight = FontWeight.Medium), color = ink, maxLines = 1)
                }
            }
        }
    }
}

/** Tonal pill with an optional 16dp icon (lock ownership, endpoint authorization): 12/16 600, padding 4/10, 8dp corners. */
@Composable
internal fun LfsPill(text: String, tone: M3Tone, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val (bg, fg) = tone.colors()
    Row(modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) M3Icon(icon, tint = fg, size = 16.dp)
        Text(text, style = M3Type.badge, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 48dp sheet/dialog icon tile, 16dp corners (lock sheet, endpoint approval, SSH review). */
@Composable
internal fun LfsIconTile(@DrawableRes icon: Int, container: Color, content: Color, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(modifier.size(size).background(container, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        M3Icon(icon, tint = content, size = 24.dp)
    }
}

/** Inset row in a sheet: 6dp dot then a wrapping mono 12 path, 44dp min, padding 0/16. */
@Composable
internal fun LfsDotPathRow(path: String, divider: Boolean, modifier: Modifier = Modifier) {
    val c = M3.colors
    Row(modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(6.dp).background(c.onVar, CircleShape))
        Text(path, style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.on)
    }
}

/** Design checkbox (18dp, primary or error accent). */
@Composable
internal fun LfsCheckbox(checked: Boolean, enabled: Boolean = true, danger: Boolean = false) {
    val c = M3.colors
    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled,
        colors = CheckboxDefaults.colors(checkedColor = if (danger) c.error else c.primary, uncheckedColor = c.onVar,
            checkmarkColor = if (danger) c.onError else c.onPrimary))
}

/** One fact row in an approval sheet ("Repository  Documents/recipes"): 12sp label over a mono value. */
@Composable
internal fun LfsFactRow(label: String, value: String, divider: Boolean, strong: Boolean = false, modifier: Modifier = Modifier) {
    val c = M3.colors
    Column(modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = M3Type.caption, color = c.onVar)
        Text(value, style = M3Type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            textDirection = TextDirection.Ltr), color = c.on, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Decimal byte sizes the way the boards print them: "48 MB", "1.2 MB", "6.1 MB", "820 kB". */
object LfsSizes {
    private val units = listOf("B", "kB", "MB", "GB", "TB")
    private fun unit(bytes: Long): Int {
        var index = 0; var value = bytes.toDouble()
        while (value >= 1000 && index < units.lastIndex) { value /= 1000; index++ }
        return index
    }
    private fun number(bytes: Long, unit: Int): String {
        val value = bytes / Math.pow(1000.0, unit.toDouble())
        return if (unit == 0 || value >= 10) value.roundToLong().toString() else String.format(Locale.getDefault(), "%.1f", value).removeSuffix(".0").removeSuffix(",0")
    }
    fun format(bytes: Long): String { val u = unit(bytes.coerceAtLeast(0)); return "${number(bytes.coerceAtLeast(0), u)} ${units[u]}" }
    /** "15 / 38 MB": both values in the total's unit. */
    fun progress(done: Long, total: Long): String { val u = unit(total.coerceAtLeast(0)); return "${number(done.coerceAtLeast(0), u)} / ${number(total.coerceAtLeast(0), u)} ${units[u]}" }
}

/** Error-container note for plain-HTTP LFS endpoints: 13/18 text, 12/14 padding, 16dp corners. */
@Composable
internal fun LfsHttpWarning(modifier: Modifier = Modifier) {
    val c = M3.colors
    Row(modifier.fillMaxWidth().background(c.ec, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        M3Icon(R.drawable.ic_m3_warning, tint = c.onEc, size = 20.dp)
        Text(androidx.compose.ui.res.stringResource(R.string.lfs_m3_http_warning), style = M3Type.bodySmall, color = c.onEc, modifier = Modifier.weight(1f))
    }
}

/**
 * Alert dialog with a 48dp icon tile above a centered 24/32 title (SSH server review): cHigh,
 * 28dp corners, 24dp padding, 24dp from the screen edges, actions end-aligned 20dp below.
 */
@Composable
internal fun LfsTileDialog(
    onDismissRequest: () -> Unit,
    @DrawableRes icon: Int,
    tileContainer: Color,
    tileContent: Color,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val c = M3.colors
    M3ModalHost(onDismissRequest, true, true, Alignment.Center) { bars ->
        Column(modifier.padding(bars).padding(24.dp).imePadding().widthIn(min = 280.dp, max = 560.dp).fillMaxWidth()
            .background(c.cHigh, RoundedCornerShape(28.dp)).pointerInput(Unit) { detectTapGestures { } }.padding(24.dp)
            .semantics { paneTitle = title }) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                LfsIconTile(icon, tileContainer, tileContent, Modifier.align(Alignment.CenterHorizontally))
                Text(title, style = M3Type.dialogTitle, color = c.on, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                content()
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
