package com.aipaca.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType

/**
 * Pill-shape status indicator: colored dot + small label.
 *
 * Pattern examples:
 *   • Tested · success
 *   • Experimental · warning
 *   • Online · success (pulsing dot replaced by a static one)
 *   • CPU fallback · warning
 *
 * The chip background is a desaturated tint of the dot color, so chips don't
 * fight the content. For accent-toned chips use [AlpacaColors.Accent.Soft] as
 * the bg.
 */

enum class ChipTone {
    Success,
    Warning,
    Error,
    Accent,
    Neutral
}

@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Neutral
) {
    val dotColor: Color = when (tone) {
        ChipTone.Success -> AlpacaColors.State.Success
        ChipTone.Warning -> AlpacaColors.State.Warning
        ChipTone.Error   -> AlpacaColors.State.Error
        ChipTone.Accent  -> AlpacaColors.Accent.Primary
        ChipTone.Neutral -> AlpacaColors.Text.Muted
    }
    val bgColor: Color = when (tone) {
        ChipTone.Success -> Color(0xFF1F2C1A) // muted success container
        ChipTone.Warning -> Color(0xFF2E2614) // muted warning container
        ChipTone.Error   -> Color(0xFF2C1715) // muted error container
        ChipTone.Accent  -> AlpacaColors.Accent.Soft
        ChipTone.Neutral -> AlpacaColors.Surface.Elevated
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text  = text,
            style = AlpacaType.LabelMd,
            color = dotColor
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14140F)
@Composable
private fun StatusChipPreview() {
    AIpacaTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip("Tested",        tone = ChipTone.Success)
            StatusChip("Experimental",  tone = ChipTone.Warning)
            StatusChip("Offline",       tone = ChipTone.Error)
            StatusChip("Active",        tone = ChipTone.Accent)
            StatusChip("Idle",          tone = ChipTone.Neutral)
        }
    }
}
