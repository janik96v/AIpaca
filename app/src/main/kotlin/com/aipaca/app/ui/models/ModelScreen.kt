package com.aipaca.app.ui.models

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aipaca.app.ui.components.ChipTone
import com.aipaca.app.ui.components.EditorialDivider
import com.aipaca.app.ui.components.EditorialMasthead
import com.aipaca.app.ui.components.InlineCTA
import com.aipaca.app.ui.components.MonoLabel
import com.aipaca.app.ui.components.MonoLabelTone
import com.aipaca.app.ui.components.StatusChip
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType

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
        name                    = "Gemma 3 4B Instruct",
        manufacturer            = "bartowski",
        manufacturerDescription = "Community quantizer known for high-quality GGUF conversions of popular open models.",
        features                = "General-purpose instruction-following, multi-turn chat, reasoning",
        size                    = "~2.5 GB",
        quantization            = "Q4_0",
        architecture            = "Gemma 3 (Google DeepMind)",
        downloadUrl             = "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/blob/main/google_gemma-3-4b-it-Q4_0.gguf",
        tested                  = true
    ),
    RecommendedModel(
        name                    = "Qwen 2.5 3B Instruct",
        manufacturer            = "bartowski",
        manufacturerDescription = "Community quantizer known for high-quality GGUF conversions of popular open models.",
        features                = "Instruction-following, coding, math, multilingual support",
        size                    = "~1.9 GB",
        quantization            = "Q4_0",
        architecture            = "Qwen 2.5 (Alibaba Cloud)",
        downloadUrl             = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/blob/main/Qwen2.5-3B-Instruct-Q4_0.gguf",
        tested                  = true
    ),
    RecommendedModel(
        name                    = "HY-MT 1.5 1.8B",
        manufacturer            = "tencent",
        manufacturerDescription = "Tencent AI Lab. HunyuanTranslate team, specializing in neural machine translation.",
        features                = "High-quality translation, DeepL-comparable quality, multilingual pairs",
        size                    = "~440 MB",
        quantization            = "TBD",
        architecture            = "HY-MT 1.5 (Tencent)",
        downloadUrl             = "https://huggingface.co/tencent/HY-MT1.5-1.8B",
        tested                  = false,
        experimental            = true,
        notes                   = "GGUF variant not yet confirmed. Visit the Hugging Face page to check for compatible quantizations."
    )
)

// ---- Screen -----------------------------------------------------------------

@Composable
fun ModelScreen(modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AlpacaColors.Surface.Canvas)
            .verticalScroll(scrollState)
    ) {
        EditorialMasthead(
            title = "Models.",
            meta  = "CURATED LIBRARY · ${recommendedModels.size} ENTRIES"
        )

        Spacer(Modifier.height(16.dp))

        recommendedModels.forEachIndexed { index, model ->
            ModelEntry(
                index = index + 1,
                model = model
            )
            if (index < recommendedModels.size - 1) {
                EditorialDivider(
                    color = AlpacaColors.Line.Subtle,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ---- Entry ------------------------------------------------------------------

@Composable
private fun ModelEntry(
    index: Int,
    model: RecommendedModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier            = Modifier.fillMaxWidth(),
            verticalAlignment   = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val labelTone = if (model.experimental) MonoLabelTone.Muted else MonoLabelTone.Accent
                MonoLabel(
                    text = "№ ${index.toString().padStart(2, '0')} · ${if (model.tested) "TESTED" else if (model.experimental) "EXPERIMENTAL" else "UNTESTED"}",
                    tone = labelTone
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = model.name,
                    style = AlpacaType.TitleLg,
                    color = AlpacaColors.Text.Primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "${model.manufacturer} · ${model.quantization} · ${model.size}",
                    style = AlpacaType.BodySm,
                    color = AlpacaColors.Text.Muted
                )

                if (!expanded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text  = model.features,
                        style = AlpacaType.BodySm.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        color = AlpacaColors.Text.Subtle,
                        maxLines = 1
                    )
                }
            }
            Icon(
                imageVector        = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint               = AlpacaColors.Text.Muted,
                modifier           = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter   = expandVertically(),
            exit    = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                DetailRow(label = "MANUFACTURER",  value = model.manufacturerDescription)
                DetailRow(label = "FEATURES",      value = model.features)
                DetailRow(label = "ARCHITECTURE",  value = model.architecture)

                if (model.notes != null) {
                    Spacer(Modifier.height(12.dp))
                    EditorialDivider(color = AlpacaColors.Line.Subtle)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text  = model.notes,
                        style = AlpacaType.BodySm,
                        color = AlpacaColors.Text.Muted
                    )
                }

                Spacer(Modifier.height(16.dp))

                InlineCTA(
                    text    = "Open on Hugging Face",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(model.downloadUrl))
                        context.startActivity(intent)
                    }
                )
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
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        MonoLabel(text = label)
        Spacer(Modifier.height(4.dp))
        Text(
            text  = value,
            style = AlpacaType.BodyMd,
            color = AlpacaColors.Text.Body
        )
    }
}

// ---- Previews ---------------------------------------------------------------

@Preview(showBackground = true, name = "ModelScreen")
@Composable
private fun ModelScreenPreview() {
    AIpacaTheme {
        ModelScreen()
    }
}
