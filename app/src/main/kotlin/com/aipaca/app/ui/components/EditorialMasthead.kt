package com.aipaca.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType

/**
 * Per-screen "magazine masthead" header.
 *
 * Renders as:
 * ```
 * Alpaca.                           ← display.masthead
 * ─────────────────────             ← hairline
 * VOL 0.1 · MAY 12, 2026 · ONLINE   ← optional MonoLabel meta
 * ```
 *
 * One per screen, replaces the persistent M3 TopAppBar.
 */
@Composable
fun EditorialMasthead(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text  = title,
            style = AlpacaType.DisplayMasthead,
            color = AlpacaColors.Text.Primary
        )
        Spacer(Modifier.height(8.dp))
        EditorialDivider()
        if (meta != null) {
            Spacer(Modifier.height(10.dp))
            MonoLabel(text = meta)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14140F)
@Composable
private fun MastheadPreview() {
    AIpacaTheme {
        Column {
            EditorialMasthead(title = "Alpaca.", meta = "VOL 0.1 · MAY 12, 2026 · ONLINE")
            EditorialMasthead(title = "Models.", meta = "CURATED LIBRARY · 03 ENTRIES")
            EditorialMasthead(title = "Server.")
        }
    }
}
