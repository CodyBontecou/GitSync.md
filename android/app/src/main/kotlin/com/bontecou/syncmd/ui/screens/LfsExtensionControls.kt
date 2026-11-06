package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.lfs.LfsExtensionReview
import com.bontecou.syncmd.services.git.lfs.LfsTracking
import com.bontecou.syncmd.ui.components.LfsSizes
import com.bontecou.syncmd.ui.theme.*
import java.io.File

@Composable
internal fun LfsExtensionReviewButton(selected: Set<String>, busy: Boolean, onReview: (Set<String>) -> Unit) {
    M3OutlinedButton(stringResource(R.string.lfs_m3_match_extensions), onClick = { onReview(selected.toSet()) },
        modifier = Modifier.testTag("lfs-review-extensions"), size = M3ButtonSize.Medium,
        enabled = !busy && selected.any { LfsTracking.extensionPatterns(it).isNotEmpty() })
}

/** Board "Large files" › extensions: the exact patterns and every file they move before .gitattributes changes. */
@Composable
internal fun LfsExtensionConfirmation(review: LfsExtensionReview, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val c = M3.colors
    M3ModalSheet(onDismissRequest = onCancel, title = stringResource(R.string.lfs_m3_ext_title), footer = {
        M3FilledButton(stringResource(R.string.lfs_m3_apply_rules), onConfirm, Modifier.fillMaxWidth().testTag("lfs-confirm-extensions"),
            size = M3ButtonSize.Hero, enabled = !busy)
        M3TextButton(stringResource(R.string.lfs_m3_cancel), onCancel, Modifier.fillMaxWidth())
    }) {
        Column(Modifier.fillMaxWidth().testTag("lfs-extension-review")) {
            Text("${File(review.plan.path).name} · ${review.plan.branch?.removePrefix("refs/heads/").orEmpty()}".removeSuffix(" · "), style = M3Type.meta, color = c.onVar,
                modifier = Modifier.padding(top = 4.dp))
            Text(review.patterns.joinToString("\n"), style = M3Type.code.copy(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                textDirection = TextDirection.Ltr), color = c.on,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth().background(c.cHighest, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("lfs-extension-patterns"))
            Text(stringResource(R.string.lfs_m3_ext_scope), style = M3Type.bodyMedium, color = c.on, modifier = Modifier.padding(top = 12.dp))
            if (review.patterns.any { !it.startsWith("*.") }) Text(stringResource(R.string.lfs_extensions_exact_fallback), style = M3Type.bodySmall, color = c.onVar,
                modifier = Modifier.padding(top = 8.dp))
            Text(pluralStringResource(R.plurals.lfs_m3_ext_affected, review.affected.size, review.affected.size), style = M3Type.count, color = c.tertiary,
                modifier = Modifier.padding(top = 16.dp))
            M3InsetList(Modifier.padding(top = 8.dp).testTag("lfs-extension-affected")) {
                review.affected.take(20).forEachIndexed { index, file ->
                    Row(Modifier.fillMaxWidth().m3TopDivider(index > 0, c.outlineVar).heightIn(min = 44.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(file.path, style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(LfsSizes.format(file.size), style = M3Type.meta, color = c.onVar)
                    }
                }
            }
            if (review.affected.size > 20) Text(pluralStringResource(R.plurals.lfs_extensions_more, review.affected.size - 20, review.affected.size - 20),
                style = M3Type.bodySmall, color = c.onVar, modifier = Modifier.padding(top = 8.dp))
            val notes = buildList {
                if (review.retained.isNotEmpty()) add(pluralStringResource(R.plurals.lfs_extensions_retained, review.retained.size, review.retained.size))
                add(stringResource(R.string.lfs_m3_ext_note_existing))
                add(stringResource(R.string.lfs_m3_ext_note_stage))
                add(stringResource(R.string.lfs_m3_ext_note_limits))
            }
            notes.forEach { note ->
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    M3Icon(R.drawable.ic_m3_info, tint = c.onVar, size = 18.dp)
                    Text(note, style = M3Type.bodySmall, color = c.onVar, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
