package com.bontecou.syncmd.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

/**
 * Window facts the adaptive layouts need: the width class from the GitSync.md
 * Material 3 boards (phone, foldable inner / small tablet, tablet), the posture of a
 * foldable and, on a foldable, where the hinge or fold is so no pane is drawn across it.
 */
@Immutable
data class AdaptiveInfo(
    val widthDp: Int,
    val heightDp: Int,
    val fold: FoldInfo? = null,
    /** The activity shares the screen with another app (split screen, freeform). */
    val multiWindow: Boolean = false,
    /** The device folds (it has a hinge angle sensor), whether or not it is folded now. */
    val foldable: Boolean = false,
    /** Smallest side of the whole display in dp, not of this window. */
    val displaySmallestDp: Int = minOf(widthDp, heightDp),
) {
    /** Foldable inner display, small tablet, landscape phone: navigation rail + list-detail. */
    val isMedium get() = widthDp >= MEDIUM_DP
    /** Full tablet (board "Tablet"): rail, vault list, changes and detail side by side. */
    val isExpanded get() = widthDp >= EXPANDED_DP
    /**
     * A horizontal fold that matters: half-opened like a laptop, or a separating hinge. Emulators
     * (and some devices) report a half-opened hinge as FLAT + separating, so both count.
     */
    val isTabletop get() = fold?.let { !it.vertical && (it.halfOpened || it.separating) } == true
    /** A vertical hinge or fold that splits the window into two panes. */
    val verticalSplit get() = fold?.takeIf { it.vertical && (it.separating || it.halfOpened) }
    /** The copy says "this tablet" on tablets and "this phone" on phones and foldables (boards Tablet vs Fold*). */
    val isTabletDevice get() = !foldable && displaySmallestDp >= MEDIUM_DP

    /** Which of the adaptive boards this window gets. */
    val layout: AdaptiveLayout get() = when {
        // Half-open with the fold across the window: diff above, commit below (FoldTabletop).
        isTabletop -> AdaptiveLayout.Tabletop
        // A narrow window beside another app on a large screen (FoldSplit).
        multiWindow && widthDp < MEDIUM_DP && (foldable || displaySmallestDp >= MEDIUM_DP) -> AdaptiveLayout.Split
        // Folded: the cover display reports no folding feature, so a compact window on a foldable is the cover (FoldCover).
        foldable && fold == null && !multiWindow && widthDp < MEDIUM_DP -> AdaptiveLayout.Cover
        isExpanded -> AdaptiveLayout.Expanded
        isMedium -> AdaptiveLayout.Medium
        else -> AdaptiveLayout.Phone
    }

    companion object {
        const val MEDIUM_DP = 600
        /** Three panes from here (the Tablet board's `wide`), not 1100. */
        const val EXPANDED_DP = 1000
    }
}

/** The adaptive boards: phone screens, the foldable cover, split screen, tabletop, foldable inner / small tablet, tablet. */
enum class AdaptiveLayout { Phone, Cover, Split, Tabletop, Medium, Expanded }

/** Fold bounds in window pixels. */
@Immutable
data class FoldInfo(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val vertical: Boolean,
    val halfOpened: Boolean,
    val separating: Boolean,
)

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun WindowLayoutInfo.toFold(): FoldInfo? =
    displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()?.let { f ->
        FoldInfo(
            left = f.bounds.left, top = f.bounds.top, right = f.bounds.right, bottom = f.bounds.bottom,
            vertical = f.orientation == FoldingFeature.Orientation.VERTICAL,
            halfOpened = f.state == FoldingFeature.State.HALF_OPENED,
            separating = f.isSeparating,
        )
    }

@Composable
fun rememberAdaptiveInfo(): AdaptiveInfo {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val fold by produceState<FoldInfo?>(initialValue = null, activity) {
        val host = activity ?: return@produceState
        WindowInfoTracker.getOrCreate(host).windowLayoutInfo(host).collect { info ->
            if (com.bontecou.syncmd.BuildConfig.DEBUG) info.displayFeatures.filterIsInstance<FoldingFeature>().forEach { f ->
                android.util.Log.d("GitSyncFold", "fold state=${f.state} orientation=${f.orientation} separating=${f.isSeparating} " +
                    "occlusion=${f.occlusionType} bounds=${f.bounds} window=${host.resources.configuration.screenWidthDp}x${host.resources.configuration.screenHeightDp}dp")
            }
            if (com.bontecou.syncmd.BuildConfig.DEBUG && info.displayFeatures.none { it is FoldingFeature })
                android.util.Log.d("GitSyncFold", "no folding feature reported")
            value = info.toFold()
        }
    }
    val hingeFeature = remember(context) {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_SENSOR_HINGE_ANGLE)
    }
    // Not every foldable declares the hinge-angle feature (some emulator images don't), but any
    // device whose WindowManager has reported a FoldingFeature folds; remember that once seen so a
    // folded (cover) window, which reports no fold, still counts.
    var sawFold by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(fold != null) { if (fold != null) sawFold = true }
    val foldable = hingeFeature || sawFold || fold != null
    // Entering or leaving split screen is a configuration change, so these re-read with it.
    val multiWindow = activity?.isInMultiWindowMode == true
    val displaySmallest = remember(activity, configuration) {
        activity?.let { host ->
            runCatching {
                val bounds = androidx.window.layout.WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(host).bounds
                val density = host.resources.displayMetrics.density
                (minOf(bounds.width(), bounds.height()) / density).toInt()
            }.getOrNull()
        } ?: configuration.smallestScreenWidthDp
    }
    return AdaptiveInfo(configuration.screenWidthDp, configuration.screenHeightDp, fold, multiWindow, foldable, displaySmallest)
}

/**
 * Two panes side by side. With a vertical hinge or fold the split lands on it:
 * the first pane ends where the hinge starts and the second begins after it, so
 * neither pane is drawn across the fold. Otherwise the first pane is
 * [firstWidth] wide.
 */
@Composable
fun HingeAwareRow(
    info: AdaptiveInfo,
    firstWidth: Dp,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var width by remember { mutableStateOf(0) }
    val split = info.verticalSplit
    Row(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow(); width = it.size.width }) {
        if (split != null && split.left > origin.x) {
            val firstDp = with(density) { (split.left - origin.x).toDp() }
            val gapDp = with(density) { (split.right - split.left).toDp() }
            Box(Modifier.width(firstDp).fillMaxHeight()) { first() }
            Box(Modifier.width(gapDp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) { second() }
        } else {
            // Never let the list take more than half: the detail needs room too.
            val available = with(density) { width.toDp() }
            val listWidth = if (available > 0.dp) minOf(firstWidth, available / 2) else firstWidth
            Box(Modifier.width(listWidth).fillMaxHeight()) { first() }
            Box(Modifier.weight(1f).fillMaxHeight()) { second() }
        }
    }
}

/**
 * Tabletop: [top] fills the half above a horizontal fold and [bottom] the half
 * below it, with the fold itself left empty. Without a fold, [top] takes
 * [topFraction] of the height.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HingeAwareColumn(
    info: AdaptiveInfo,
    modifier: Modifier = Modifier,
    topFraction: Float = 0.5f,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val fold = info.fold?.takeIf { !it.vertical }
    Column(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        if (fold != null && fold.top > origin.y) {
            val topDp = with(density) { (fold.top - origin.y).toDp() }
            val gapDp = with(density) { (fold.bottom - fold.top).toDp() }.coerceAtLeast(0.dp)
            Box(Modifier.fillMaxWidth().height(topDp)) { top() }
            Box(Modifier.fillMaxWidth().height(gapDp))
            // The lower half sits below the fold, not under the status bar.
            Box(Modifier.fillMaxWidth().weight(1f).consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.Companion.statusBars)) { bottom() }
        } else {
            Box(Modifier.fillMaxWidth().weight(topFraction)) { top() }
            Box(Modifier.fillMaxWidth().weight(1f - topFraction)) { bottom() }
        }
    }
}
