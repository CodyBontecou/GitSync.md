package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCircleButton
import com.bontecou.syncmd.ui.theme.TideType

/**
 * M3 top app bar: a back icon button beside a start-aligned 22/500 title.
 * The title wraps, so translated titles grow with system text size.
 */
@Composable
fun WrappingTopBar(title: String, backLabel: String, onBack: (() -> Unit)?, backEnabled: Boolean = true,
    modifier: Modifier = Modifier, titleModifier: Modifier = Modifier, backModifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Tide.colors.paper).statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(start = if (onBack != null) 4.dp else 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TideCircleButton(backLabel, onBack, backModifier, enabled = backEnabled) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        Text(
            title,
            style = TideType.title,
            color = Tide.colors.ink,
            modifier = titleModifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp, top = 10.dp, bottom = 10.dp).semantics { heading() },
        )
    }
}
