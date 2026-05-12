package com.aipaca.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType

/**
 * AIpaca Bottom Navigation — Editorial Retro.
 *
 * Pattern: icon (22dp, outlined) + sentence-case label, both in the same
 * color. Active tab gets accent color + a 2dp underline 28dp wide.
 * No pill indicator, no filled background.
 */

enum class AlpacaTab(val label: String, val icon: ImageVector, val route: String) {
    Chat   ("Chat",   Icons.Outlined.ChatBubbleOutline, "chat"),
    Models ("Models", Icons.Outlined.Inventory2,        "models"),
    Server ("Server", Icons.Outlined.Dns,               "server");

    companion object {
        fun fromRoute(route: String?): AlpacaTab = when (route) {
            Chat.route   -> Chat
            Models.route -> Models
            Server.route -> Server
            else         -> Chat
        }
    }
}

@Composable
fun AlpacaBottomNav(
    selected: AlpacaTab,
    onSelect: (AlpacaTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AlpacaColors.Surface.Canvas)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AlpacaColors.Line.Hairline)
        )

        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .height(72.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            AlpacaTab.entries.forEach { tab ->
                AlpacaNavItem(
                    tab        = tab,
                    isSelected = tab == selected,
                    onClick    = { onSelect(tab) },
                    modifier   = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AlpacaNavItem(
    tab: AlpacaTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (isSelected) AlpacaColors.Accent.Primary else AlpacaColors.Text.Muted

    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector        = tab.icon,
                contentDescription = tab.label,
                tint               = tint,
                modifier           = Modifier.size(22.dp)
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text  = tab.label,
                style = AlpacaType.LabelLg,
                color = tint
            )

            if (isSelected) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(2.dp)
                        .background(AlpacaColors.Accent.Primary)
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14140F)
@Composable
private fun AlpacaBottomNavPreview() {
    AIpacaTheme {
        AlpacaBottomNav(
            selected = AlpacaTab.Chat,
            onSelect = {}
        )
    }
}
