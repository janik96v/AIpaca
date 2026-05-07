package com.lamaphone.app.ui.models

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lamaphone.app.ui.theme.LamaPhoneTheme
import com.lamaphone.app.ui.theme.RetroCliColors
import com.lamaphone.app.ui.theme.TerminalPanel

// ---- Model data -------------------------------------------------------------

private data class RecommendedModel(
    val name: String,
    val manufacturer: String,
    val manufacturerDescription: String,
    val features: String,
    val size: String,
    val quantization: String,
    val architecture: String,
    val downloadUrl: String,
    val tested: Boolean,
    val experimental: Boolean = false,
    val notes: String? = null
)

private val recommendedModels = listOf(
    RecommendedModel(
        name = "Gemma-3-4B-it-GGUF Q4_0",
        manufacturer = "Bartowski",
        manufacturerDescription = "Community quantizer known for high-quality GGUF conversions of popular open models.",
        features = "General-purpose instruction-following, multi-turn chat, reasoning",
        size = "~2.5 GB",
        quantization = "Q4_0",
        architecture = "Gemma 3 (Google DeepMind)",
        downloadUrl = "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/blob/main/google_gemma-3-4b-it-Q4_0.gguf",
        tested = true
    ),
    RecommendedModel(
        name = "Qwen2.5-3B-Instruct-GGUF Q4_0",
        manufacturer = "Bartowski",
        manufacturerDescription = "Community quantizer known for high-quality GGUF conversions of popular open models.",
        features = "Instruction-following, coding, math, multilingual support",
        size = "~1.9 GB",
        quantization = "Q4_0",
        architecture = "Qwen 2.5 (Alibaba Cloud)",
        downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/blob/main/Qwen2.5-3B-Instruct-Q4_0.gguf",
        tested = true
    ),
    RecommendedModel(
        name = "HY-MT1.5-1.8B (Translation)",
        manufacturer = "Tencent",
        manufacturerDescription = "Tencent AI Lab. HunyuanTranslate team, specializing in neural machine translation.",
        features = "High-quality translation, DeepL-comparable quality, multilingual pairs",
        size = "~440 MB",
        quantization = "TBD (experimental)",
        architecture = "HY-MT 1.5 (Tencent)",
        downloadUrl = "https://huggingface.co/tencent/HY-MT1.5-1.8B",
        tested = false,
        experimental = true,
        notes = "Exact GGUF variant not yet confirmed. Visit the HuggingFace page to check for compatible quantizations."
    )
)

// ---- Screen -----------------------------------------------------------------

@Composable
fun ModelScreen(modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        TerminalPanel(
            modifier = Modifier.fillMaxWidth(),
            title = "RECOMMENDED_MODELS",
            accent = RetroCliColors.Cyan
        ) {
            Text(
                text = "> Curated list of models tested with LamaPhone. " +
                        "Download a .gguf file and load it from the SERVER tab.",
                style = MaterialTheme.typography.bodySmall,
                color = RetroCliColors.Muted
            )
        }

        // Model cards
        recommendedModels.forEach { model ->
            ModelCard(model = model)
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ---- Model card composable --------------------------------------------------

@Composable
private fun ModelCard(
    model: RecommendedModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val accent = when {
        model.experimental -> RetroCliColors.Warning
        model.tested       -> RetroCliColors.Success
        else               -> RetroCliColors.Cyan
    }

    TerminalPanel(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        title = null,
        accent = accent
    ) {
        Column {
            // Header row: name + expand icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = RetroCliColors.Cyan,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.manufacturer,
                            style = MaterialTheme.typography.labelSmall,
                            color = RetroCliColors.Magenta
                        )
                        Spacer(Modifier.width(8.dp))
                        if (model.tested) {
                            Icon(
                                imageVector = Icons.Filled.Verified,
                                contentDescription = "Tested",
                                tint = RetroCliColors.Success,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "TESTED",
                                style = MaterialTheme.typography.labelSmall,
                                color = RetroCliColors.Success
                            )
                        }
                        if (model.experimental) {
                            Icon(
                                imageVector = Icons.Filled.Science,
                                contentDescription = "Experimental",
                                tint = RetroCliColors.Warning,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "EXPERIMENTAL",
                                style = MaterialTheme.typography.labelSmall,
                                color = RetroCliColors.Warning
                            )
                        }
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = RetroCliColors.Muted
                )
            }

            // Collapsed summary
            if (!expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${model.size}  |  ${model.quantization}",
                    style = MaterialTheme.typography.labelSmall,
                    color = RetroCliColors.Muted,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Expanded details
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))

                    DetailRow(label = "MANUFACTURER", value = model.manufacturerDescription)
                    DetailRow(label = "FEATURES", value = model.features)
                    DetailRow(label = "SIZE", value = model.size)
                    DetailRow(label = "QUANTIZATION", value = model.quantization)
                    DetailRow(label = "ARCHITECTURE", value = model.architecture)

                    if (model.notes != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "> ${model.notes}",
                            style = MaterialTheme.typography.bodySmall,
                            color = RetroCliColors.Warning
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(model.downloadUrl))
                            context.startActivity(intent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = RetroCliColors.Void
                        )
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "OPEN_ON_HUGGINGFACE",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = RetroCliColors.Magenta,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = RetroCliColors.Text
        )
    }
}

// ---- Previews ---------------------------------------------------------------

@Preview(showBackground = true, name = "ModelScreen")
@Composable
private fun ModelScreenPreview() {
    LamaPhoneTheme(darkTheme = true) {
        ModelScreen()
    }
}
