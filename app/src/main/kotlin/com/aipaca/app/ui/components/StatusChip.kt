package com.aipaca.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType

/**
 * Severity of a [StatusChip]. Instrument has no hues, so tone maps to how loud
 * the chip is: [Success] and [Accent] are white, [Warning] is dimmed, [Neutral]
 * and [Error] are dimmer still (errors say so in words).
 */
enum class ChipTone {
    Success,
    Warning,
    Error,
    Accent,
    Neutral
}

/** Outlined tracked label: `GPU · Q4_K_M`, `CPU ONLY · Q5_0`. */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Neutral
) {
    val (border, content) = when (tone) {
        ChipTone.Success, ChipTone.Accent -> Ink.White to Ink.White
        ChipTone.Warning                  -> Ink.Border to Ink.Meta
        ChipTone.Neutral, ChipTone.Error  -> Ink.Border to Ink.Placeholder
    }
    StatusChipFrame(text, border, content, modifier)
}

@Composable
private fun StatusChipFrame(text: String, border: Color, content: Color, modifier: Modifier) {
    Tracked(
        text,
        InkType.chrome(.18f),
        color    = content,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, border)
            .padding(horizontal = 9.dp, vertical = 7.dp)
    )
}
