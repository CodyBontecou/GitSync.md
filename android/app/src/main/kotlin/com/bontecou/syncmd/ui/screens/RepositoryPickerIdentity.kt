package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType

/** Keep the account literal and let a localized count wrap independently at large text sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepositoryPickerIdentity(login: String, count: Int, modifier: Modifier = Modifier) {
    val c = Tide.colors
    FlowRow(modifier.fillMaxWidth().testTag("repo-picker-identity"),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("@$login", modifier = Modifier.testTag("repo-picker-login"),
            style = TideType.mono(12.5.sp, FontWeight.SemiBold).copy(textDirection = TextDirection.Ltr), color = c.ink)
        Text(pluralStringResource(R.plurals.repository_picker_count, count, count), modifier = Modifier.testTag("repo-picker-count"),
            style = TideType.mono(12.5.sp), color = c.inkMid)
    }
}
