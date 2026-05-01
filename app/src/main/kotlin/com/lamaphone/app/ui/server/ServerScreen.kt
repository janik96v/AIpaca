package com.lamaphone.app.ui.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lamaphone.app.EngineState
import com.lamaphone.app.server.ServerManager
import com.lamaphone.app.ui.components.ModelPickerButton
import com.lamaphone.app.ui.theme.LamaPhoneTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ---- ViewModel --------------------------------------------------------------

class ServerViewModel : ViewModel() {
    val isServerRunning: StateFlow<Boolean> = ServerManager.isRunning
    val serverUrl: StateFlow<String?>        = ServerManager.serverUrl
    val modelLoaded: StateFlow<Boolean>      = EngineState.isLoaded
    val modelPath: StateFlow<String?>        = EngineState.modelPath
}

// ---- Screen -----------------------------------------------------------------

@Composable
fun ServerScreen(
    modifier: Modifier = Modifier,
    serverViewModel: ServerViewModel = viewModel()
) {
    val isRunning   by serverViewModel.isServerRunning.collectAsState()
    val serverUrl   by serverViewModel.serverUrl.collectAsState()
    val modelLoaded by serverViewModel.modelLoaded.collectAsState()
    val modelPath   by serverViewModel.modelPath.collectAsState()

    val context          = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState      = rememberScrollState()

    var showQrDialog by remember { mutableStateOf(false) }

    Column(
        modifier            = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        // ---- Status indicator -----------------------------------------------
        ServerStatusCard(isRunning = isRunning)

        // ---- Warning if no model --------------------------------------------
        if (!modelLoaded) {
            NoModelWarningCard(
                onModelSelected = { path ->
                    EngineState.scope.launch { EngineState.loadModel(path) }
                }
            )
        }

        // ---- URL card -------------------------------------------------------
        if (isRunning && serverUrl != null) {
            ServerUrlCard(
                url     = serverUrl!!,
                onCopy  = {
                    clipboardManager.setText(AnnotatedString(serverUrl!!))
                },
                onQrCode = { showQrDialog = true }
            )
        }

        // ---- Model info + request count ------------------------------------
        ModelInfoCard(
            modelPath = modelPath
        )

        // ---- Connect with section ------------------------------------------
        if (isRunning && serverUrl != null) {
            ConnectWithSection(url = serverUrl!!)
        }

        // ---- Start / Stop button -------------------------------------------
        Spacer(Modifier.height(4.dp))
        Button(
            onClick  = {
                if (isRunning) ServerManager.stop(context)
                else           ServerManager.start(context)
            },
            enabled  = modelLoaded || isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error
                                 else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text  = if (isRunning) "Stop Server" else "Start Server",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }

    // ---- QR code dialog -----------------------------------------------------
    if (showQrDialog && serverUrl != null) {
        QrCodeDialog(
            url      = serverUrl!!,
            onDismiss = { showQrDialog = false }
        )
    }
}

// ---- Sub-composables --------------------------------------------------------

@Composable
private fun ServerStatusCard(
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor by animateColorAsState(
        targetValue = if (isRunning) Color(0xFF22C55E) else Color(0xFFEF4444),
        label       = "dotColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.6f,
        targetValue   = 1f,
        animationSpec = InfiniteRepeatableSpec(
            animation  = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label         = "pulseAlpha"
    )

    Card(
        modifier  = modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .alpha(if (isRunning) pulseAlpha else 1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text  = if (isRunning) "Server Running" else "Server Stopped",
                style = MaterialTheme.typography.titleLarge,
                color = if (isRunning) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ServerUrlCard(
    url: String,
    onCopy: () -> Unit,
    onQrCode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text  = "Server URL",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier            = Modifier.fillMaxWidth(),
                verticalAlignment   = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text     = url,
                    style    = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color    = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector        = Icons.Filled.ContentCopy,
                            contentDescription = "Copy URL",
                            tint               = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    IconButton(onClick = onQrCode) {
                        Icon(
                            imageVector        = Icons.Filled.QrCode,
                            contentDescription = "Show QR code",
                            tint               = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelInfoCard(
    modelPath: String?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text  = "Model",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = modelPath?.substringAfterLast('/') ?: "None",
                style = MaterialTheme.typography.bodyMedium,
                color = if (modelPath != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoModelWarningCard(
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text  = "No model loaded",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = "Load a model first to start the server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(12.dp))
            ModelPickerButton(onModelSelected = onModelSelected)
        }
    }
}

@Composable
private fun ConnectWithSection(
    url: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier            = Modifier.fillMaxWidth(),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Text(
                text     = "  Connect with  ",
                style    = MaterialTheme.typography.labelMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }

        Spacer(Modifier.height(12.dp))

        ConnectCard(
            title   = "OpenClaw",
            description = "Set the API base URL in OpenClaw's settings:",
            code    = url
        )
        Spacer(Modifier.height(8.dp))
        ConnectCard(
            title   = "Open WebUI",
            description = "In Open WebUI, go to Settings → Connections → OpenAI API and set:",
            code    = url
        )
        Spacer(Modifier.height(8.dp))
        ConnectCard(
            title   = "curl",
            description = "Test from your computer:",
            code    = """curl $url/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"local","messages":[{"role":"user","content":"Hi!"}]}'"""
        )
    }
}

@Composable
private fun ConnectCard(
    title: String,
    description: String,
    code: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(10.dp)
            ) {
                Text(
                    text       = code,
                    style      = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color      = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun QrCodeDialog(
    url: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect via QR Code") },
        text  = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Placeholder — actual QR generation is a Week 2 feature
                Box(
                    modifier         = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Filled.QrCode,
                        contentDescription = null,
                        modifier           = Modifier.size(80.dp),
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text  = url,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = "Scan with your camera app to connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// ---- Previews ---------------------------------------------------------------

@Preview(showBackground = true, name = "ServerScreen — stopped, no model")
@Composable
private fun ServerScreenStoppedPreview() {
    LamaPhoneTheme(darkTheme = true) {
        ServerScreen()
    }
}

@Preview(showBackground = true, name = "StatusCard — running")
@Composable
private fun StatusCardRunningPreview() {
    LamaPhoneTheme(darkTheme = true) {
        ServerStatusCard(isRunning = true, modifier = Modifier.padding(16.dp))
    }
}

@Preview(showBackground = true, name = "ConnectCard")
@Composable
private fun ConnectCardPreview() {
    LamaPhoneTheme(darkTheme = true) {
        ConnectCard(
            title       = "curl",
            description = "Test with:",
            code        = "curl http://192.168.1.42:8080/v1/models",
            modifier    = Modifier.padding(16.dp)
        )
    }
}
