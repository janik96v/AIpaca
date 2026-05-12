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
import androidx.compose.foundation.layout.width
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
        name                    = "Gemma 4 E2B Instruct",
        manufacturer            = "unsloth",
        manufacturerDescription = "Unsloth AI — specializes in memory-efficient, fast fine-tuning and GGUF exports of open models.",
        features                = "General-purpose instruction-following, multi-turn chat, reasoning",
        size                    = "~2.5 GB",
        quantization            = "Q4_0",
        architecture            = "Gemma 4 (Google DeepMind)",
        downloadUrl             = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF",
        tested                  = true
    ),
    RecommendedModel(
        name                    = "Qwen 2.5 3B Instruct",
        manufacturer            = "Qwen",
        manufacturerDescription = "Alibaba Cloud's Qwen team — official GGUF releases of the Qwen model family.",
        features                = "Instruction-following, coding, math, multilingual support",
        size                    = "~1.9 GB",
        quantization            = "Q4_0",
        architecture            = "Qwen 2.5 (Alibaba Cloud)",
        downloadUrl             = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF",
        tested                  = true
    ),
    RecommendedModel(
        name                    = "Qwen3 4B",
        manufacturer            = "Qwen",
        manufacturerDescription = "Alibaba Cloud's Qwen team — official GGUF releases of the Qwen model family.",
        features                = "Advanced reasoning, instruction-following, coding, multilingual support",
        size                    = "~2.6 GB",
        quantization            = "Q4_0",
        architecture            = "Qwen3 (Alibaba Cloud)",
        downloadUrl             = "https://huggingface.co/Qwen/Qwen3-4B-GGUF",
        tested                  = true,
        notes                   = "Qwen3 generation model. Tested with Q4_0 quantization."
    ),
    RecommendedModel(
        name                    = "HY-MT 1.5 1.8B",
        manufacturer            = "tencent",
        manufacturerDescription = "Tencent AI Lab. HunyuanTranslate team, specializing in neural machine translation.",
        features                = "High-quality translation, DeepL-comparable quality, multilingual pairs",
        size                    = "~440 MB",
        quantization            = "TBD",
        architecture            = "HY-MT 1.5 (Tencent)",
        downloadUrl             = "https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/tree/main",
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

        EditorialDivider(
            color = AlpacaColors.Line.Subtle,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        QuantGuide()

        Spacer(Modifier.height(32.dp))
    }
}

// ---- Quantization guide -----------------------------------------------------

private data class QuantEntry(
    val format: String,
    val gpu: Boolean,
    val note: String
)

private val quantGuideEntries = listOf(
    QuantEntry("Q4_0",   gpu = true,  note = "Fastest, smallest, lowest quality"),
    QuantEntry("Q4_1",   gpu = true,  note = "Slightly higher quality than Q4_0"),
    QuantEntry("Q4_K_S", gpu = true,  note = "Good quality, compact size"),
    QuantEntry("Q4_K_M", gpu = true,  note = "Best all-round choice"),
    QuantEntry("Q5_0",   gpu = false, note = "CPU only — no OpenCL kernel"),
    QuantEntry("Q5_K_S", gpu = true,  note = "Higher quality, modest size increase"),
    QuantEntry("Q5_K_M", gpu = true,  note = "High quality, recommended over Q5_0"),
    QuantEntry("Q6_K",   gpu = true,  note = "Near-lossless quality"),
    QuantEntry("Q8_0",   gpu = true,  note = "Effectively lossless, largest"),
    QuantEntry("IQ4_NL", gpu = true,  note = "Importance quant, efficient"),
)

@Composable
private fun QuantGuide(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MonoLabel(text = "REFERENCE · GPU COMPATIBILITY", tone = MonoLabelTone.Muted)
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = "Quantization Guide",
                    style = AlpacaType.TitleLg,
                    color = AlpacaColors.Text.Primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Which GGUF formats run on the GPU",
                    style = AlpacaType.BodySm,
                    color = AlpacaColors.Text.Muted
                )
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
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Text(
                    text  = "AIpaca uses the Adreno OpenCL backend from llama.cpp. Only specific quantization formats have optimised GPU kernels. All others fall back to CPU-only inference, which is significantly slower.",
                    style = AlpacaType.BodySm,
                    color = AlpacaColors.Text.Muted
                )
                Spacer(Modifier.height(16.dp))

                quantGuideEntries.forEach { entry ->
                    QuantGuideRow(entry)
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "When downloading a custom model, look for Q4_0, Q4_K, Q5_K, Q6_K, Q8_0, or IQ4_NL in the filename. Avoid Q5_0, Q5_1, and Q2/Q3 variants.",
                    style = AlpacaType.BodySm.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    color = AlpacaColors.Text.Subtle
                )
            }
        }
    }
}

@Composable
private fun QuantGuideRow(entry: QuantEntry, modifier: Modifier = Modifier) {
    Row(
        modifier          = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusChip(
            text = entry.format,
            tone = if (entry.gpu) ChipTone.Success else ChipTone.Neutral
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text     = entry.note,
            style    = AlpacaType.BodySm,
            color    = AlpacaColors.Text.Muted,
            modifier = Modifier.weight(1f)
        )
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
                val (labelStatus, labelTone) = when {
                    model.tested       -> "TESTED"       to MonoLabelTone.Accent
                    model.experimental -> "EXPERIMENTAL" to MonoLabelTone.Warning
                    else               -> "UNTESTED"     to MonoLabelTone.Muted
                }
                MonoLabel(
                    text = "№ ${index.toString().padStart(2, '0')} · $labelStatus",
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
