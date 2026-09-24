package com.aipaca.app.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aipaca.app.EngineState
import com.aipaca.app.engine.formatContext
import com.aipaca.app.ui.components.InkDialog
import com.aipaca.app.ui.components.TextAction
import com.aipaca.app.ui.components.Tracked
import com.aipaca.app.ui.components.hairlineBottom
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picks the KV-cache size before a chat model loads. Options come from the
 * file's header and the device's RAM (see [EngineState.computeContextConfig]),
 * computed off the main thread.
 */
@Composable
fun ContextWindowDialog(
    path: String,
    name: String,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val config by produceState<EngineState.ContextSizeConfig?>(null, path) {
        value = withContext(Dispatchers.IO) { EngineState.computeContextConfig(modelPath = path) }
    }

    InkDialog(
        title     = "Context window",
        subtitle  = name,
        onDismiss = onDismiss,
        actions   = { TextAction("Cancel", onClick = onDismiss, color = Ink.Meta) }
    ) {
        val c = config
        Text(
            text  = if (c?.isRecurrent == true) {
                "This model is recurrent: memory per token is constant, so large windows are cheap."
            } else {
                "How many tokens the model holds at once. Options are capped to fit this phone's RAM."
            },
            style = InkType.Secondary,
            color = Ink.Secondary
        )
        if (c == null) {
            Tracked("Reading header", InkType.Label, color = Ink.Meta)
        } else {
            Column {
                c.options.forEach { size ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .hairlineBottom(Ink.Divider)
                            .clickable(onClickLabel = "Load with this context", role = Role.Button) { onPick(size) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${formatContext(size)} tokens",
                            style    = InkType.Body,
                            color    = Ink.Text,
                            modifier = Modifier.weight(1f)
                        )
                        if (size == c.recommended) {
                            Tracked("Recommended", InkType.Label, color = Ink.White)
                        }
                    }
                }
            }
        }
    }
}
