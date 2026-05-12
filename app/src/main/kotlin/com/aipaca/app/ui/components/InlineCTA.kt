package com.aipaca.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType

/**
 * Editorial inline call-to-action.
 *
 * Renders as `Open on Hugging Face  →` with a 1dp underline drawn the full
 * text width. Replaces block-style buttons for *secondary* actions.
 *
 * For primary actions (Start server, Send message), keep the standard M3
 * [androidx.compose.material3.Button].
 */
@Composable
fun InlineCTA(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AlpacaColors.Accent.Primary,
    enabled: Boolean = true,
    underline: Boolean = true
) {
    val displayColor = if (enabled) color else AlpacaColors.Text.Subtle

    Text(
        text     = "$text  →",
        style    = AlpacaType.LabelLg,
        color    = displayColor,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp)
            .then(
                if (underline) Modifier.drawBehind {
                    val y = size.height - 2.dp.toPx()
                    drawLine(
                        color       = displayColor,
                        start       = Offset(0f, y),
                        end         = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                } else Modifier
            )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF14140F)
@Composable
private fun InlineCTAPreview() {
    AIpacaTheme {
        Column(
            modifier            = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            InlineCTA(text = "Open on Hugging Face", onClick = {})
            InlineCTA(text = "Pair device",          onClick = {})
            InlineCTA(text = "Copy URL",             onClick = {})
            InlineCTA(text = "Disabled action",      onClick = {}, enabled = false)
        }
    }
}
