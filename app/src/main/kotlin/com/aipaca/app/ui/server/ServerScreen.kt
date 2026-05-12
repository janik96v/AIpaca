package com.aipaca.app.ui.server

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.aipaca.app.EngineState
import com.aipaca.app.engine.BenchResult
import com.aipaca.app.engine.ModelInfo
import com.aipaca.app.server.ServerManager
import com.aipaca.app.server.security.AuthorizedKeysStore
import com.aipaca.app.server.security.PairingManager
import com.aipaca.app.server.security.TlsManager
import com.aipaca.app.ui.components.ChipTone
import com.aipaca.app.ui.components.EditorialDivider
import com.aipaca.app.ui.components.EditorialMasthead
import com.aipaca.app.ui.components.InlineCTA
import com.aipaca.app.ui.components.ModelPickerButton
import com.aipaca.app.ui.components.MonoLabel
import com.aipaca.app.ui.components.MonoLabelTone
import com.aipaca.app.ui.components.StatusChip
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType
import kotlinx.coroutines.delay
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
    val isRunning      by serverViewModel.isServerRunning.collectAsState()
    val serverUrl      by serverViewModel.serverUrl.collectAsState()
    val modelLoaded    by serverViewModel.modelLoaded.collectAsState()
    val modelPath      by serverViewModel.modelPath.collectAsState()
    val isLoadingModel by EngineState.isLoadingModel.collectAsState()
    val gpuLayers      by EngineState.gpuLayers.collectAsState()
    val modelInfo      by EngineState.modelInfo.collectAsState()
    val lastBenchmark  by EngineState.lastBenchmark.collectAsState()
    val isBenchmarking by EngineState.isBenchmarking.collectAsState()

    val context          = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState      = rememberScrollState()

    var showPairingDialog by remember { mutableStateOf(false) }
    var pairedClients     by remember { mutableStateOf(listOf<AuthorizedKeysStore.AuthorizedKey>()) }

    LaunchedEffect(isRunning) {
        pairedClients = AuthorizedKeysStore(context).getAll()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AlpacaColors.Surface.Canvas)
            .verticalScroll(scrollState)
    ) {
        EditorialMasthead(title = "Server.")

        // ---- Hero status block --------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            StatusChip(
                text = if (isRunning) "Running" else "Stopped",
                tone = if (isRunning) ChipTone.Success else ChipTone.Neutral
            )

            Spacer(Modifier.height(16.dp))

            if (isRunning && serverUrl != null) {
                Text(
                    text  = serverUrl!!,
                    style = AlpacaType.DisplayHeadline,
                    color = AlpacaColors.Text.Primary
                )
                Spacer(Modifier.height(4.dp))
                MonoLabel("OPENAI-COMPATIBLE BASE URL")

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    InlineCTA(
                        text = "Copy URL",
                        onClick = {
                            clipboardManager.setText(AnnotatedString(serverUrl!!))
                        }
                    )
                    InlineCTA(
                        text = "Pair device",
                        onClick = { showPairingDialog = true }
                    )
                }
            } else {
                Text(
                    text  = "Server idle",
                    style = AlpacaType.DisplayHeadline,
                    color = AlpacaColors.Text.Primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Load a model, then start the server to expose an OpenAI-compatible endpoint on your LAN.",
                    style = AlpacaType.BodyMd,
                    color = AlpacaColors.Text.Muted
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        EditorialDivider(
            color    = AlpacaColors.Line.Subtle,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        // ---- Model section ------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            MonoLabel("ACTIVE MODEL")
            Spacer(Modifier.height(8.dp))

            if (!modelLoaded) {
                Text(
                    text  = if (isLoadingModel) "Loading…" else "No model loaded",
                    style = AlpacaType.TitleMd,
                    color = if (isLoadingModel) AlpacaColors.State.Warning else AlpacaColors.Text.Muted
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = if (isLoadingModel) "Background load in progress." else "Load a model first to start the server.",
                    style = AlpacaType.BodySm,
                    color = AlpacaColors.Text.Muted
                )
                Spacer(Modifier.height(16.dp))
                ModelPickerButton(
                    onModelSelected = { path ->
                        EngineState.scope.launch { EngineState.loadModel(path) }
                    },
                    isLoading = isLoadingModel
                )
            } else {
                Text(
                    text  = modelPath?.substringAfterLast('/').orEmpty(),
                    style = AlpacaType.TitleMd,
                    color = AlpacaColors.Text.Primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = listOfNotNull(
                        if (gpuLayers > 0) "GPU · $gpuLayers layers" else "CPU",
                        "Quant ${modelInfo.quant}",
                        if (modelInfo.pureQ4_0) "pure Q4_0" else null
                    ).joinToString(" · "),
                    style = AlpacaType.BodySm,
                    color = AlpacaColors.Text.Muted
                )

                if (lastBenchmark.tgRuns > 0 || lastBenchmark.ppRuns > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text  = "Last bench · pp ${"%.2f".format(lastBenchmark.ppAvg)} t/s · tg ${"%.2f".format(lastBenchmark.tgAvg)} t/s",
                        style = AlpacaType.MonoMetric,
                        color = if (lastBenchmark.tgAvg >= 5f) AlpacaColors.State.Success else AlpacaColors.State.Warning
                    )
                }

                Spacer(Modifier.height(16.dp))
                InlineCTA(
                    text    = if (isBenchmarking) "Benchmarking…" else "Run native bench",
                    enabled = !isBenchmarking,
                    onClick = {
                        EngineState.scope.launch { EngineState.benchmark(pp = 128, tg = 128, pl = 1, nr = 3) }
                    }
                )
            }
        }

        // ---- Paired clients (only when running) ---------------------------
        if (isRunning) {
            EditorialDivider(
                color    = AlpacaColors.Line.Subtle,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                MonoLabel("AUTHORIZED CLIENTS · ${pairedClients.size}")
                Spacer(Modifier.height(12.dp))

                if (pairedClients.isEmpty()) {
                    Text(
                        text  = "No devices paired yet.",
                        style = AlpacaType.BodyMd,
                        color = AlpacaColors.Text.Muted
                    )
                } else {
                    pairedClients.forEach { client ->
                        PairedClientRow(
                            client = client,
                            onRemove = {
                                AuthorizedKeysStore(context).remove(client.fingerprint)
                                pairedClients = AuthorizedKeysStore(context).getAll()
                            }
                        )
                        EditorialDivider(color = AlpacaColors.Line.Subtle)
                    }
                }

                Spacer(Modifier.height(12.dp))
                InlineCTA(
                    text    = "Pair new device",
                    onClick = { showPairingDialog = true }
                )
            }

            // ---- Connect-with cheat-sheet -----------------------------------
            EditorialDivider(
                color    = AlpacaColors.Line.Subtle,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            ConnectWithSection(url = serverUrl!!)
        }

        // ---- Start / Stop primary button ----------------------------------
        Spacer(Modifier.height(24.dp))
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Button(
                onClick = {
                    if (isRunning) ServerManager.stop(context)
                    else           ServerManager.start(context)
                },
                enabled  = (modelLoaded && !isLoadingModel) || isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = RoundedCornerShape(6.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = if (isRunning) AlpacaColors.State.Error else AlpacaColors.Accent.Primary,
                    contentColor           = AlpacaColors.Text.OnAccent,
                    disabledContainerColor = AlpacaColors.Surface.Elevated,
                    disabledContentColor   = AlpacaColors.Text.Subtle
                )
            ) {
                Text(
                    text  = if (isRunning) "Stop server" else "Start server",
                    style = AlpacaType.TitleMd
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showPairingDialog && serverUrl != null) {
        val certFingerprint = remember { TlsManager.getCertFingerprint(context) }
        PairingDialog(
            serverUrl       = serverUrl!!,
            certFingerprint = certFingerprint,
            onDismiss = {
                showPairingDialog = false
                PairingManager.cancel()
                pairedClients = AuthorizedKeysStore(context).getAll()
            }
        )
    }
}

// ---- Sub-composables --------------------------------------------------------

@Composable
private fun PairedClientRow(
    client: AuthorizedKeysStore.AuthorizedKey,
    onRemove: () -> Unit
) {
    Row(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = client.displayName,
                style = AlpacaType.BodyMd,
                color = AlpacaColors.Text.Primary
            )
            Spacer(Modifier.height(2.dp))
            MonoLabel(client.fingerprint.take(16) + "…")
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector        = Icons.Outlined.Delete,
                contentDescription = "Remove ${client.displayName}",
                tint               = AlpacaColors.Text.Muted,
                modifier           = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun PairingDialog(
    serverUrl: String,
    certFingerprint: String,
    onDismiss: () -> Unit
) {
    var pin          by remember { mutableStateOf(PairingManager.generatePin()) }
    var remainingSec by remember { mutableIntStateOf((PairingManager.remainingMs() / 1000).toInt()) }

    LaunchedEffect(pin) {
        while (remainingSec > 0) {
            delay(1000)
            remainingSec = (PairingManager.remainingMs() / 1000).toInt()
        }
    }

    val qrPayload = remember(pin, serverUrl, certFingerprint) {
        """{"endpoint":"$serverUrl","pin":"$pin","fingerprint":"$certFingerprint"}"""
    }
    val qrBitmap = remember(qrPayload) { generateQrBitmap(qrPayload, 512) }

    AlertDialog(
        onDismissRequest  = onDismiss,
        containerColor    = AlpacaColors.Surface.Card,
        titleContentColor = AlpacaColors.Text.Primary,
        textContentColor  = AlpacaColors.Text.Body,
        shape             = RoundedCornerShape(12.dp),
        title = {
            Column {
                Text("Pair new device", style = AlpacaType.TitleMd, color = AlpacaColors.Text.Primary)
                Spacer(Modifier.height(4.dp))
                MonoLabel("SCAN QR OR ENTER PIN")
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.fillMaxWidth()
            ) {
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap             = qrBitmap.asImageBitmap(),
                            contentDescription = "Pairing QR code",
                            modifier           = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier         = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AlpacaColors.Surface.Elevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.QrCode,
                            contentDescription = null,
                            modifier           = Modifier.size(80.dp),
                            tint               = AlpacaColors.Accent.Primary
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                MonoLabel("PIN CODE")
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = pin,
                    style = AlpacaType.DisplayHeadline.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = AlpacaColors.Accent.Primary
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text  = if (remainingSec > 0) "Expires in ${remainingSec}s" else "Expired",
                    style = AlpacaType.BodySm,
                    color = if (remainingSec > 30) AlpacaColors.State.Success else AlpacaColors.State.Warning
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text  = "Scan the QR code with your client app, or use the PIN manually.",
                    style = AlpacaType.BodySm,
                    color = AlpacaColors.Text.Muted
                )

                if (remainingSec <= 0) {
                    Spacer(Modifier.height(8.dp))
                    InlineCTA(
                        text    = "Regenerate PIN",
                        onClick = {
                            pin = PairingManager.generatePin()
                            remainingSec = (PairingManager.remainingMs() / 1000).toInt()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", style = AlpacaType.LabelLg, color = AlpacaColors.Text.Primary)
            }
        }
    )
}

@Composable
private fun ConnectWithSection(
    url: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        MonoLabel("PYTHON QUICKSTART · scripts/ IN REPO")
        Spacer(Modifier.height(16.dp))

        ConnectEntry(
            title       = "1. Pair once",
            description = "Tap \"Pair device\", note the 6-digit PIN, then run:",
            code        = "python3 pair.py"
        )
        Spacer(Modifier.height(16.dp))
        ConnectEntry(
            title       = "2. Send a request",
            description = "Chat with the model — add --stream for streamed output:",
            code        = "python3 chat.py \"Hello\" --stream"
        )
    }
}

@Composable
private fun ConnectEntry(
    title: String,
    description: String,
    code: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text  = title,
            style = AlpacaType.TitleMd,
            color = AlpacaColors.Text.Primary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = description,
            style = AlpacaType.BodySm,
            color = AlpacaColors.Text.Muted
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(AlpacaColors.Surface.Recess)
                .padding(12.dp)
        ) {
            Text(
                text  = code,
                style = AlpacaType.MonoBody,
                color = AlpacaColors.Text.Body
            )
        }
    }
}

// ---- QR code generation -----------------------------------------------------

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}

// ---- Previews ---------------------------------------------------------------

@Preview(showBackground = true, name = "ServerScreen — stopped, no model")
@Composable
private fun ServerScreenStoppedPreview() {
    AIpacaTheme {
        ServerScreen()
    }
}
