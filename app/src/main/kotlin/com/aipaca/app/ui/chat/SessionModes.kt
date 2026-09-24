package com.aipaca.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.aipaca.app.EngineState
import com.aipaca.app.data.OllamaPrefs

/** Which session-mode dialog is open, if any. */
enum class ModeDialog { SystemPrompt, WebSearch, Ollama }

/** Current state of the session switches, for the modes glyph and its menu. */
@Composable
fun rememberComposerModes(chat: ChatViewModel): ComposerModes {
    val systemPrompt    by chat.systemPrompt.collectAsState()
    val thinkingEnabled by chat.thinkingEnabled.collectAsState()
    val webSearch       by chat.webSearchConfigured.collectAsState()
    val modelInfo       by EngineState.modelInfo.collectAsState()
    val ollamaActive    by EngineState.useOllama.collectAsState()
    val ollamaModel     by EngineState.ollamaModelName.collectAsState()
    return ComposerModes(
        systemPromptSet  = systemPrompt.isNotBlank(),
        supportsThinking = modelInfo.supportsThinking,
        thinkingEnabled  = thinkingEnabled,
        webSearchOn      = webSearch,
        ollamaActive     = ollamaActive,
        ollamaModelName  = ollamaModel
    )
}

/** The dialogs behind the modes menu. */
@Composable
fun ModeDialogs(open: ModeDialog?, chat: ChatViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    when (open) {
        null -> Unit
        ModeDialog.SystemPrompt -> {
            val systemPrompt by chat.systemPrompt.collectAsState()
            SystemPromptDialog(
                initialValue = systemPrompt,
                onSave       = { chat.updateSystemPrompt(it); onDismiss() },
                onClear      = { chat.updateSystemPrompt(""); onDismiss() },
                onDismiss    = onDismiss
            )
        }
        ModeDialog.WebSearch -> WebSearchKeyDialog(
            hasExistingKey = !chat.agentPrefs.getTavilyApiKey().isNullOrBlank(),
            onSave = { key ->
                chat.agentPrefs.saveTavilyApiKey(key)
                chat.agentPrefs.setWebSearchEnabled(true)
                chat.refreshWebSearchConfigured()
                onDismiss()
            },
            onContinue = {
                chat.refreshWebSearchConfigured()
                onDismiss()
            },
            onClear = {
                chat.agentPrefs.clearTavilyApiKey()
                chat.agentPrefs.setWebSearchEnabled(false)
                chat.refreshWebSearchConfigured()
                // Stays open so a new key can be entered.
            },
            onDismiss = onDismiss
        )
        ModeDialog.Ollama -> {
            val ollamaActive by EngineState.useOllama.collectAsState()
            OllamaConnectionDialog(
                isConnected  = ollamaActive,
                currentUrl   = OllamaPrefs.getServerUrl(context),
                currentModel = OllamaPrefs.getModelName(context),
                onConnect    = { url, model ->
                    EngineState.enableOllama(url, model)
                    onDismiss()
                },
                onDisconnect = {
                    EngineState.disableOllama()
                    onDismiss()
                },
                onDismiss    = onDismiss
            )
        }
    }
}
