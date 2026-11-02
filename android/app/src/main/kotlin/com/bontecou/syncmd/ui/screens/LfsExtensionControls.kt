package com.bontecou.syncmd.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.lfs.LfsExtensionReview
import com.bontecou.syncmd.services.git.lfs.LfsTracking
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsDetail
import com.bontecou.syncmd.ui.components.VaultOpsNote
import com.bontecou.syncmd.ui.components.VaultOpsOutlineButton
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType
import java.io.File

@Composable
internal fun LfsExtensionReviewButton(selected: Set<String>, busy: Boolean, onReview: (Set<String>) -> Unit) {
    VaultOpsOutlineButton(stringResource(R.string.lfs_extensions_review), onClick = { onReview(selected.toSet()) },
        modifier = Modifier.testTag("lfs-review-extensions"),
        enabled = !busy && selected.any { LfsTracking.extensionPatterns(it).isNotEmpty() })
}

/** Review the exact patterns and every file they would move before .gitattributes changes. */
@Composable
internal fun LfsExtensionConfirmation(review: LfsExtensionReview, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val context = LocalContext.current
    val c = Tide.colors
    VaultOpsSheet(
        onDismissRequest = onCancel,
        title = stringResource(R.string.lfs_extensions_title),
        confirmButton = {
            VaultOpsButton(stringResource(R.string.lfs_extensions_apply), onConfirm, Modifier.testTag("lfs-confirm-extensions"), enabled = !busy)
        },
        dismissButton = { VaultOpsTextButton(stringResource(R.string.lfs_extensions_cancel), onCancel, Modifier.fillMaxWidth()) },
    ) {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).testTag("lfs-extension-review"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.lfs_extensions_repository, File(review.plan.path).name, review.plan.branch?.removePrefix("refs/heads/").orEmpty()),
                style = TideType.meta, color = c.inkFaint)
            VaultOpsDetail(review.patterns.joinToString("\n"), textModifier = Modifier.testTag("lfs-extension-patterns"))
            Text(stringResource(R.string.lfs_extensions_scope))
            if (review.patterns.any { !it.startsWith("*.") }) VaultOpsNote(stringResource(R.string.lfs_extensions_exact_fallback))
            Text(pluralStringResource(R.plurals.lfs_extensions_affected, review.affected.size, review.affected.size),
                style = TideType.mono(13.sp, FontWeight.SemiBold), color = c.ahead)
            VaultOpsDetail(review.affected.take(20).joinToString("\n") { "${it.path} · ${Formatter.formatFileSize(context, it.size)}" },
                textModifier = Modifier.testTag("lfs-extension-affected"))
            if (review.affected.size > 20) Text(pluralStringResource(R.plurals.lfs_extensions_more, review.affected.size - 20, review.affected.size - 20),
                style = TideType.meta, color = c.inkFaint)
            if (review.retained.isNotEmpty()) VaultOpsNote(pluralStringResource(R.plurals.lfs_extensions_retained, review.retained.size, review.retained.size))
            VaultOpsNote(stringResource(R.string.lfs_extensions_existing))
            VaultOpsNote(stringResource(R.string.lfs_extensions_commit))
            VaultOpsNote(stringResource(R.string.lfs_extensions_limits))
        }
    }
}
