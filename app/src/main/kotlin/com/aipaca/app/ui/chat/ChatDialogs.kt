package com.aipaca.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aipaca.app.ui.components.FieldLabel
import com.aipaca.app.ui.components.InkDialog
import com.aipaca.app.ui.components.InkTextField
import com.aipaca.app.ui.components.TextAction
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType

/**
 * System prompt editor. Dismissing keeps the draft, exactly like the old
 * dialog: an edit is never lost to a stray tap outside.
 */
@Composable
fun SystemPromptDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    InkDialog(
        title     = "System prompt",
        subtitle  = "Instruction for the model",
        onDismiss = { onSave(value); onDismiss() },
        actions   = {
            TextAction("Clear", onClick = onClear, color = Ink.Meta)
            TextAction("Done", onClick = { onSave(value) })
        }
    ) {
        InkTextField(
            value         = value,
            onValueChange = { value = it },
            placeholder   = "Set system instructions",
            boxed         = true,
            minLines      = 4,
            maxLines      = 10
        )
    }
}

@Composable
fun WebSearchKeyDialog(
    hasExistingKey: Boolean,
    onSave: (String) -> Unit,
    onContinue: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    if (hasExistingKey) {
        InkDialog(
            title     = "Web search",
            subtitle  = "Tavily API key",
            onDismiss = onDismiss,
            actions   = {
                TextAction("Delete key", onClick = onClear, color = Ink.Meta)
                TextAction("Continue", onClick = onContinue)
            }
        ) {
            Text("API key  ••••••••••••", style = InkType.Body, color = Ink.Text)
            Text(
                "Keep the saved key to allow web searches, or delete it to enter a new one. " +
                    "Searches are the only thing that leaves the phone.",
                style = InkType.Secondary,
                color = Ink.Secondary
            )
        }
    } else {
        var key by remember { mutableStateOf("") }
        InkDialog(
            title     = "Web search",
            subtitle  = "Tavily API key for web search",
            onDismiss = onDismiss,
            actions   = {
                TextAction("Cancel", onClick = onDismiss, color = Ink.Meta)
                TextAction("Save", onClick = { onSave(key.trim()) }, enabled = key.isNotBlank())
            }
        ) {
            InkTextField(
                value                = key,
                onValueChange        = { key = it },
                placeholder          = "tvly-…",
                literalPlaceholder   = true,
                keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation()
            )
        }
    }
}

@Composable
fun OllamaConnectionDialog(
    isConnected: Boolean,
    currentUrl: String,
    currentModel: String,
    onConnect: (url: String, model: String) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    var model by remember { mutableStateOf(currentModel) }

    InkDialog(
        title     = "Ollama",
        subtitle  = if (isConnected) "Connected" else "Disconnected",
        onDismiss = onDismiss,
        actions   = {
            if (isConnected) TextAction("Disconnect", onClick = onDisconnect, color = Ink.Meta)
            else TextAction("Cancel", onClick = onDismiss, color = Ink.Meta)
            TextAction(
                if (isConnected) "Update" else "Connect",
                onClick = { onConnect(url.trim(), model.trim()) },
                enabled = url.isNotBlank() && model.isNotBlank()
            )
        }
    ) {
        Text(
            "Run chat on Ollama on your computer instead of this phone. Both devices must be on the same network.",
            style = InkType.Secondary,
            color = Ink.Secondary
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Server URL")
            InkTextField(
                value           = url,
                onValueChange   = { url = it },
                placeholder     = "http://192.168.1.100:11434",
                literalPlaceholder = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier) {
            FieldLabel("Model name")
            InkTextField(
                value         = model,
                onValueChange = { model = it },
                placeholder   = "qwen3:30b",
                literalPlaceholder = true
            )
        }
    }
}
