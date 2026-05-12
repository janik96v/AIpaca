package com.aipaca.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors

/**
 * Editorial hairline divider.
 *
 * Two variants:
 *  • Plain hairline (1dp, [AlpacaColors.Line.Hairline])
 *  • With accent mark + MonoLabel (e.g. `── DISPATCH`)
 */
@Composable
fun EditorialDivider(
    modifier: Modifier = Modifier,
    color: Color = AlpacaColors.Line.Hairline,
    thickness: androidx.compose.ui.unit.Dp = 1.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color)
    )
}

/**
 * Section marker — short accent line followed by a [MonoLabel].
 * Pattern: `── DISPATCH`
 */
@Composable
fun EditorialSectionMark(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier            = modifier,
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(2.dp)
                .background(AlpacaColors.Accent.Primary)
        )
        MonoLabel(text = label, tone = MonoLabelTone.Accent)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14140F)
@Composable
private fun DividerPreview() {
    AIpacaTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(24.dp)
        ) {
            EditorialDivider()
            Spacer(Modifier.height(16.dp))
            EditorialSectionMark("DISPATCH")
            Spacer(Modifier.height(16.dp))
            EditorialDivider(color = AlpacaColors.Line.Strong)
        }
    }
}
