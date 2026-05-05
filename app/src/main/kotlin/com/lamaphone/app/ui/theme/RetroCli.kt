package com.lamaphone.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object RetroCliColors {
    val Void = Color(0xFF03040A)
    val Terminal = Color(0xEE080A18)
    val TerminalSoft = Color(0xCC10132B)
    val Cyan = Color(0xFF28D7FF)
    val Blue = Color(0xFF1288FF)
    val Purple = Color(0xFF6D2CFF)
    val Magenta = Color(0xFFFF22B8)
    val Text = Color(0xFFE9F9FF)
    val Muted = Color(0xFF7CA7C7)
    val Warning = Color(0xFFFFD38A)
    val Success = Color(0xFF5CFFCB)
    val Error = Color(0xFFFF5FA8)
}

val RetroBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        RetroCliColors.Void,
        Color(0xFF071134),
        Color(0xFF27106E),
        Color(0xFF4B076B)
    )
)

@Composable
fun TerminalBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RetroBackgroundBrush)
            .drawWithContent {
                drawContent()
                val spacing = 9.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawLine(
                        color = RetroCliColors.Cyan.copy(alpha = 0.045f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    y += spacing
                }
            }
    ) {
        content()
    }
}

@Composable
fun TerminalPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = RetroCliColors.Cyan,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = RetroCliColors.Terminal,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.72f)),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            if (title != null) {
                Text(
                    text = "[$title]",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}
