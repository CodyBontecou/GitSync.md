package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCircleButton
import com.bontecou.syncmd.ui.theme.TideType

/**
 * Tide screen header: a circular back button over a large left-aligned title.
 * The title wraps, so translated titles grow with system text size.
 */
@Composable
fun WrappingTopBar(title: String, backLabel: String, onBack: (() -> Unit)?, backEnabled: Boolean = true,
    modifier: Modifier = Modifier, titleModifier: Modifier = Modifier, backModifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().background(Tide.colors.paper).statusBarsPadding()
            .padding(start = Tide.gutter, end = Tide.gutter, top = 8.dp, bottom = 10.dp)
    ) {
        if (onBack != null) {
            TideCircleButton(backLabel, onBack, backModifier, enabled = backEnabled) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            title,
            style = TideType.display(30.sp),
            color = Tide.colors.ink,
            modifier = titleModifier.padding(start = 4.dp, top = if (onBack != null) 14.dp else 4.dp).semantics { heading() },
        )
    }
}
