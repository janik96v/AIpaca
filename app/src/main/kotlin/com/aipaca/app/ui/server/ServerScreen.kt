package com.aipaca.app.ui.server

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aipaca.app.EngineState
import com.aipaca.app.server.ServerManager
import com.aipaca.app.server.security.AuthorizedKeysStore
import com.aipaca.app.server.security.PairingManager
import com.aipaca.app.server.security.TlsManager
import com.aipaca.app.ui.components.Emphasis
import com.aipaca.app.ui.components.IconAction
import com.aipaca.app.ui.components.InkDialog
import com.aipaca.app.ui.components.InkIcon
import com.aipaca.app.ui.components.OutlineButton
import com.aipaca.app.ui.components.ScreenTitle
import com.aipaca.app.ui.components.TextAction
import com.aipaca.app.ui.components.Tracked
import com.aipaca.app.ui.components.hairlineBottom
import com.aipaca.app.ui.shell.displayModelName
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import com.aipaca.app.ui.theme.Ph
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

// ---- ViewModel --------------------------------------------------------------

class ServerViewModel : ViewModel() {
    val isServerRunning: StateFlow<Boolean> = ServerManager.isRunning
    val serverUrl: StateFlow<String?>        = ServerManager.serverUrl
    val modelLoaded: StateFlow<Boolean>      = EngineState.isLoaded
    val modelPath: StateFlow<String?>        = EngineState.modelPath
}

// ---- Screen -----------------------------------------------------------------

/**
 * Server: expose the loaded model on the local network as an
 * OpenAI-compatible endpoint, and manage the devices allowed to use it.
 */
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
    val header         by EngineState.modelHeader.collectAsState()
    val gpuLayers      by EngineState.gpuLayers.collectAsState()
    val modelInfo      by EngineState.modelInfo.collectAsState()
    val lastBenchmark  by EngineState.lastBenchmark.collectAsState()
    val isBenchmarking by EngineState.isBenchmarking.collectAsState()
    val remote         by EngineState.useOllama.collectAsState()

    val context   = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showPairing   by remember { mutableStateOf(false) }
    var pairedClients by remember { mutableStateOf(listOf<AuthorizedKeysStore.AuthorizedKey>()) }

    LaunchedEffect(isRunning) {
        pairedClients = runCatching { AuthorizedKeysStore(context).getAll() }.getOrDefault(emptyList())
    }

    val canStart = modelLoaded && !isLoadingModel
    val url = serverUrl

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        ScreenTitle("Server")

        Text(
            text = when {
                isRunning -> "Any OpenAI-compatible client on your network can talk to this model."
                canStart  -> "Expose this model to your network as an OpenAI-compatible endpoint."
                else      -> "Load a model, then expose it to your network as an OpenAI-compatible endpoint."
            },
            style    = InkType.Body,
            color    = Ink.Meta,
            modifier = Modifier.widthIn(max = 268.dp)
        )

        if (isRunning && url != null) {
            Row(
                modifier = Modifier
                    .clickable(onClickLabel = "Copy URL", role = Role.Button) {
                        clipboard.setText(AnnotatedString(url))
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(url, style = InkType.Url, color = Ink.Text, modifier = Modifier.weight(1f, fill = false))
                InkIcon(Ph.Copy, 14.dp, tint = Ink.TertiaryIcon, contentDescription = "Copy URL")
            }
        } else {
            Tracked("Offline", InkType.Url, color = Ink.Meta)
        }

        OutlineButton(
            label     = if (isRunning) "Stop server" else "Start server",
            icon      = Ph.Power,
            iconSize  = 16.dp,
            emphasis  = if (isRunning) Emphasis.Secondary else Emphasis.Primary,
            enabled   = isRunning || canStart,
            fillWidth = true,
            centered  = true,
            padding   = PaddingValues(16.dp),
            style     = InkType.Label,
            onClick   = {
                if (isRunning) ServerManager.stop(context) else ServerManager.start(context)
            }
        )

        // ---- Paired devices ------------------------------------------------------
        Column(
            modifier            = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Tracked("Paired devices · ${pairedClients.size}", InkType.Count, color = Ink.Meta)
            if (pairedClients.isEmpty()) {
                Text("No devices paired yet.", style = InkType.Secondary, color = Ink.Secondary)
            }
            pairedClients.forEach { client ->
                PairedDeviceRow(
                    client   = client,
                    onRemove = {
                        AuthorizedKeysStore(context).remove(client.fingerprint)
                        pairedClients = AuthorizedKeysStore(context).getAll()
                    }
                )
            }
            if (isRunning) {
                TextAction("Pair a new device", onClick = { showPairing = true })
            } else {
                Text("Start the server to pair a device.", style = InkType.Secondary, color = Ink.Secondary)
            }
        }

        // ---- Active model ------------------------------------------------------------
        if (modelLoaded && !remote) {
            Column(
                modifier            = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Tracked("Active model", InkType.Count, color = Ink.Meta)
                Text(displayModelName(modelPath, header), style = InkType.Name, color = Ink.Text)
                Tracked(
                    listOfNotNull(
                        if (gpuLayers > 0) "GPU · $gpuLayers layers" else "CPU",
                        modelInfo.quant.takeIf { it != "unknown" },
                        if (modelInfo.pureQ4_0) "pure Q4_0" else null
                    ).joinToString(" · "),
                    InkType.Meta,
                    color = Ink.Meta
                )
                if (lastBenchmark.tgRuns > 0 || lastBenchmark.ppRuns > 0) {
                    Tracked(
                        String.format(
                            Locale.US, "Last bench · pp %.2f t/s · tg %.2f t/s",
                            lastBenchmark.ppAvg, lastBenchmark.tgAvg
                        ),
                        InkType.Meta,
                        color = Ink.Text
                    )
                }
                TextAction(
                    label   = if (isBenchmarking) "Benchmarking" else "Run native bench",
                    enabled = !isBenchmarking,
                    onClick = {
                        EngineState.scope.launch { EngineState.benchmark(pp = 128, tg = 128, pl = 1, nr = 3) }
                    }
                )
            }
        }

        // ---- Quickstart ------------------------------------------------------------
        if (isRunning) {
            Column(
                modifier            = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Tracked("Python quickstart · scripts/ in repo", InkType.Count, color = Ink.Meta)
                QuickstartStep(
                    description = "Pair once — tap “Pair a new device”, note the PIN, then run:",
                    code        = "python3 pair.py"
                )
                QuickstartStep(
                    description = "Send a request — add --stream for streamed output:",
                    code        = "python3 chat.py \"Hello\" --stream"
                )
            }
        }
    }

    if (showPairing && url != null) {
        val certFingerprint = remember { TlsManager.getCertFingerprint(context) }
        PairingDialog(
            serverUrl       = url,
            certFingerprint = certFingerprint,
            onDismiss       = {
                showPairing = false
                PairingManager.cancel()
                pairedClients = AuthorizedKeysStore(context).getAll()
            }
        )
    }
}

// ---- Sub-composables --------------------------------------------------------

@Composable
private fun PairedDeviceRow(
    client: AuthorizedKeysStore.AuthorizedKey,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hairlineBottom(Ink.Divider)
            .padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(client.displayName, style = InkType.Name, color = Ink.Text)
            Tracked(shortFingerprint(client.fingerprint), InkType.Meta, color = Ink.Meta, maxLines = 1)
        }
        IconAction(
            icon               = Ph.X,
            contentDescription = "Remove ${client.displayName}",
            onClick            = onRemove,
            size               = 14.dp,
            tint               = Ink.TertiaryIcon
        )
    }
}

/** `SHA256:8F3A…C21D` from a hex SHA-256 fingerprint. */
internal fun shortFingerprint(hex: String): String {
    val h = hex.uppercase(Locale.ROOT)
    return if (h.length <= 8) "SHA256:$h" else "SHA256:${h.take(4)}…${h.takeLast(4)}"
}

@Composable
private fun QuickstartStep(description: String, code: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(description, style = InkType.Secondary, color = Ink.Secondary)
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Ink.Border)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                code,
                style = InkType.Secondary.copy(fontFamily = FontFamily.Monospace),
                color = Ink.Text
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

    InkDialog(
        title     = "Pair a new device",
        subtitle  = "Scan the QR or enter the PIN",
        onDismiss = onDismiss,
        actions   = {
            if (remainingSec <= 0) {
                TextAction("New PIN", onClick = {
                    pin = PairingManager.generatePin()
                    remainingSec = (PairingManager.remainingMs() / 1000).toInt()
                })
            }
            TextAction("Close", onClick = onDismiss, color = Ink.Meta)
        }
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // QR codes need a light quiet zone to scan: the one white surface in the app.
            Box(
                Modifier
                    .size(220.dp)
                    .background(androidx.compose.ui.graphics.Color.White)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap             = qrBitmap.asImageBitmap(),
                        contentDescription = "Pairing QR code",
                        modifier           = Modifier.fillMaxSize()
                    )
                } else {
                    InkIcon(Ph.QrCode, 80.dp, tint = Ink.Black)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tracked("PIN", InkType.Label, color = Ink.Meta)
            Tracked(pin, InkType.ScreenTitle.copy(fontSize = 26.sp), color = Ink.White)
            Tracked(
                if (remainingSec > 0) "Expires in ${remainingSec}s" else "Expired",
                InkType.Label,
                color = if (remainingSec > 30) Ink.Meta else Ink.White
            )
        }
        Text(
            "Scan the code with your client app, or type the PIN.",
            style = InkType.Secondary,
            color = Ink.Secondary
        )
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
