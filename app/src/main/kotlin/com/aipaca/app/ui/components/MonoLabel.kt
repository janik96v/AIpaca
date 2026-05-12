package com.aipaca.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType

/**
 * Editorial mono microlabel.
 *
 * Pattern examples:
 *   • `№ 01 · TESTED`
 *   • `YOU · 09:41`
 *   • `VOL 0.1 · MAY 12, 2026 · ONLINE`
 *   • `ALPACA · ON-DEVICE`
 *
 * Uses [AlpacaType.MonoLabel] (10sp bold mono, 1.8sp letter-spacing).
 * Default color is [AlpacaColors.Text.Muted]; pass a tone to override.
 */

enum class MonoLabelTone {
    Muted,
    Accent,
    Success,
    Warning,
    Error
}

@Composable
fun MonoLabel(
    text: String,
    modifier: Modifier = Modifier,
    tone: MonoLabelTone = MonoLabelTone.Muted
) {
    val color: Color = when (tone) {
        MonoLabelTone.Muted   -> AlpacaColors.Text.Muted
        MonoLabelTone.Accent  -> AlpacaColors.Accent.Primary
        MonoLabelTone.Success -> AlpacaColors.State.Success
        MonoLabelTone.Warning -> AlpacaColors.State.Warning
        MonoLabelTone.Error   -> AlpacaColors.State.Error
    }

    Text(
        text     = text,
        modifier = modifier,
        style    = AlpacaType.MonoLabel,
        color    = color
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF14140F)
@Composable
private fun MonoLabelPreview() {
    AIpacaTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            MonoLabel("VOL 0.1 · MAY 12, 2026 · ONLINE")
            Spacer(Modifier.height(8.dp))
            MonoLabel("№ 01 · TESTED",     tone = MonoLabelTone.Accent)
            Spacer(Modifier.height(8.dp))
            MonoLabel("STATUS · RUNNING",  tone = MonoLabelTone.Success)
            Spacer(Modifier.height(8.dp))
            MonoLabel("EXPERIMENTAL",      tone = MonoLabelTone.Warning)
            Spacer(Modifier.height(8.dp))
            MonoLabel("OFFLINE",           tone = MonoLabelTone.Error)
        }
    }
}
