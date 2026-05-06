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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.lamaphone.app.engine.BenchResult
import com.lamaphone.app.engine.ModelInfo
import com.lamaphone.app.server.ServerManager
import com.lamaphone.app.ui.components.ModelPickerButton
import com.lamaphone.app.ui.theme.LamaPhoneTheme
import com.lamaphone.app.ui.theme.RetroCliColors
import com.lamaphone.app.ui.theme.TerminalPanel
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
    val isLoadingModel by EngineState.isLoadingModel.collectAsState()
    val gpuLayers by EngineState.gpuLayers.collectAsState()
    val modelInfo by EngineState.modelInfo.collectAsState()
    val lastBenchmark by EngineState.lastBenchmark.collectAsState()
    val isBenchmarking by EngineState.isBenchmarking.collectAsState()

    val context          = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState      = rememberScrollState()

    var showQrDialog by remember { mutableStateOf(false) }

    Column(
        modifier            = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ---- Status indicator -----------------------------------------------
        ServerStatusCard(isRunning = isRunning)

        // ---- Warning if no model --------------------------------------------
        if (!modelLoaded) {
            NoModelWarningCard(
                isLoadingModel = isLoadingModel,
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
            modelPath = modelPath,
            gpuLayers = gpuLayers,
            modelInfo = modelInfo,
            lastBenchmark = lastBenchmark,
            isBenchmarking = isBenchmarking,
            onRunBenchmark = {
                EngineState.scope.launch { EngineState.benchmark(pp = 128, tg = 128, pl = 1, nr = 3) }
            }
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
            enabled  = (modelLoaded && !isLoadingModel) || isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) RetroCliColors.Error else RetroCliColors.Cyan,
                contentColor = RetroCliColors.Void,
                disabledContainerColor = RetroCliColors.Purple.copy(alpha = 0.35f),
                disabledContentColor = RetroCliColors.Muted
            )
        ) {
            Text(
                text  = if (isRunning) "STOP_SERVER" else "START_SERVER",
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
        targetValue = if (isRunning) RetroCliColors.Success else RetroCliColors.Error,
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

    TerminalPanel(
        modifier = modifier.fillMaxWidth(),
        title = "SERVER",
        accent = if (isRunning) RetroCliColors.Success else RetroCliColors.Error
    ) {
        Row(
            modifier            = Modifier
                .fillMaxWidth(),
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
                text  = if (isRunning) "RUNNING" else "STOPPED",
                style = MaterialTheme.typography.titleLarge,
                color = if (isRunning) RetroCliColors.Success else RetroCliColors.Muted,
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
    TerminalPanel(
        modifier = modifier.fillMaxWidth(),
        title = "ENDPOINT",
        accent = RetroCliColors.Cyan
    ) {
        Column {
            Text(
                text  = "OPENAI_COMPATIBLE_BASE_URL",
                style = MaterialTheme.typography.labelMedium,
                color = RetroCliColors.Muted
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
                    color    = RetroCliColors.Cyan,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector        = Icons.Filled.ContentCopy,
                            contentDescription = "Copy URL",
                            tint               = RetroCliColors.Cyan
                        )
                    }
                    IconButton(onClick = onQrCode) {
                        Icon(
                            imageVector        = Icons.Filled.QrCode,
                            contentDescription = "Show QR code",
                            tint               = RetroCliColors.Magenta
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
    gpuLayers: Int,
    modelInfo: ModelInfo,
    lastBenchmark: BenchResult,
    isBenchmarking: Boolean,
    onRunBenchmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    TerminalPanel(
        modifier = modifier.fillMaxWidth(),
        title = "MODEL",
        accent = if (modelPath != null) RetroCliColors.Cyan else RetroCliColors.Magenta
    ) {
        Column {
            Text(
                text  = "ACTIVE_MODEL",
                style = MaterialTheme.typography.labelMedium,
                color = RetroCliColors.Muted
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = modelPath?.substringAfterLast('/') ?: "NONE",
                style = MaterialTheme.typography.bodyMedium,
                color = if (modelPath != null) RetroCliColors.Cyan else RetroCliColors.Warning
            )
            if (modelPath != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "BACKEND: ${if (gpuLayers > 0) "GPU / $gpuLayers LAYERS" else "CPU"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (gpuLayers > 0) RetroCliColors.Magenta else RetroCliColors.Warning
                )
                Text(
                    text = "QUANT: ${modelInfo.quant} PURE_Q4_0=${modelInfo.pureQ4_0}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (modelInfo.pureQ4_0) RetroCliColors.Success else RetroCliColors.Warning
                )
                Text(
                    text = "TENSORS: ${modelInfo.tensorHistogram}",
                    style = MaterialTheme.typography.labelSmall,
                    color = RetroCliColors.Muted,
                    maxLines = 2
                )
                if (lastBenchmark.tgRuns > 0 || lastBenchmark.ppRuns > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "BENCH: PP ${"%.2f".format(lastBenchmark.ppAvg)} t/s  TG ${"%.2f".format(lastBenchmark.tgAvg)} t/s",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (lastBenchmark.tgAvg >= 5f) RetroCliColors.Success else RetroCliColors.Warning
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onRunBenchmark,
                    enabled = !isBenchmarking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RetroCliColors.Magenta,
                        contentColor = RetroCliColors.Void,
                        disabledContainerColor = RetroCliColors.Purple.copy(alpha = 0.35f),
                        disabledContentColor = RetroCliColors.Muted
                    )
                ) {
                    if (isBenchmarking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = RetroCliColors.Cyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isBenchmarking) "BENCH_RUNNING" else "RUN_NATIVE_BENCH",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun NoModelWarningCard(
    isLoadingModel: Boolean,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TerminalPanel(
        modifier = modifier.fillMaxWidth(),
        title = "WARNING",
        accent = RetroCliColors.Warning
    ) {
        Column {
            Text(
                text  = if (isLoadingModel) "MODEL_LOADING" else "NO_MODEL_LOADED",
                style = MaterialTheme.typography.titleSmall,
                color = RetroCliColors.Warning,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = if (isLoadingModel) "> Background load in progress." else "> Load a model first to start the server.",
                style = MaterialTheme.typography.bodySmall,
                color = RetroCliColors.Muted
            )
            Spacer(Modifier.height(12.dp))
            ModelPickerButton(
                onModelSelected = onModelSelected,
                isLoading = isLoadingModel
            )
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
            Box(modifier = Modifier.weight(1f).height(1.dp).background(RetroCliColors.Cyan.copy(alpha = 0.35f)))
            Text(
                text     = "  CONNECT_WITH  ",
                style    = MaterialTheme.typography.labelMedium,
                color    = RetroCliColors.Magenta
            )
            Box(modifier = Modifier.weight(1f).height(1.dp).background(RetroCliColors.Magenta.copy(alpha = 0.35f)))
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
    TerminalPanel(
        modifier = modifier.fillMaxWidth(),
        title = title.uppercase(),
        accent = RetroCliColors.Purple
    ) {
        Column {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = RetroCliColors.Cyan
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = description,
                style = MaterialTheme.typography.bodySmall,
                color = RetroCliColors.Muted
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(RetroCliColors.Void)
                    .padding(10.dp)
            ) {
                Text(
                    text       = code,
                    style      = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color      = RetroCliColors.Text
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
        title = { Text("CONNECT VIA QR") },
        text  = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier         = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RetroCliColors.TerminalSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Filled.QrCode,
                        contentDescription = null,
                        modifier           = Modifier.size(80.dp),
                        tint               = RetroCliColors.Cyan
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
                    color = RetroCliColors.Muted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE") }
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
