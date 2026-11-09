package com.bontecou.syncmd.ui.util

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bontecou.syncmd.R
import java.util.Locale

/** A complete localized quantity phrase whose value may have a separate visual style. */
data class CountedPhrase(val before: String, val value: String, val after: String) {
    val text: String get() = before + value + after
    val label: String get() = (before + after).trim()
}

/** The translated template owns placement/spacing; render the supplied value exactly once. */
fun countedPhrase(template: String, value: String): CountedPhrase {
    val marker = "\uE000quantity_value\uE001"
    val formatted = String.format(Locale.ROOT, template, marker)
    val start = formatted.indexOf(marker)
    require(start >= 0 && formatted.indexOf(marker, start + marker.length) < 0)
    return CountedPhrase(formatted.substring(0, start), value, formatted.substring(start + marker.length))
}

/** Keep quantity selection, localized digits and the label bound to the same value. */
@Composable
fun quantityPhrase(@PluralsRes id: Int, quantity: Int, subject: String? = null): CountedPhrase =
    countedPhrase(pluralStringResource(id, quantity), subject ?: stringResource(R.string.l10n_count, quantity))
