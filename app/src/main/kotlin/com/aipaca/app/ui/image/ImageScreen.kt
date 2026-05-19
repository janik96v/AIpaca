package com.aipaca.app.ui.image

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aipaca.app.ImageGenState
import com.aipaca.app.engine.ImageGenRequest
import com.aipaca.app.engine.ImageGenResult
import com.aipaca.app.ui.components.ChipTone
import com.aipaca.app.ui.components.EditorialDivider
import com.aipaca.app.ui.components.EditorialMasthead
import com.aipaca.app.ui.components.MonoLabel
import com.aipaca.app.ui.components.StatusChip
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType
import kotlinx.coroutines.launch

class ImageScreenViewModel(app: Application) : AndroidViewModel(app) {

    var lastResult by mutableStateOf<ImageGenResult?>(null)
        private set

    fun loadModel(path: String) {
        viewModelScope.launch {
            ImageGenState.loadModel(path)
        }
    }

    fun generate(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float
    ) {
        viewModelScope.launch {
            val result = ImageGenState.generateImage(
                ImageGenRequest(
                    prompt         = prompt,
                    negativePrompt = negativePrompt,
                    width          = width,
                    height         = height,
                    steps          = steps,
                    cfgScale       = cfgScale,
                    seed           = -1L
                )
            )
            result.onSuccess { lastResult = it }
        }
    }

    fun clearError() = ImageGenState.clearError()
    fun unloadModel() = ImageGenState.unload()
}

@Composable
fun ImageScreen(vm: ImageScreenViewModel = viewModel()) {
    val isLoaded     by ImageGenState.isLoaded.collectAsState()
    val isLoading    by ImageGenState.isLoadingModel.collectAsState()
    val isGenerating by ImageGenState.isGenerating.collectAsState()
    val errorMessage by ImageGenState.errorMessage.collectAsState()
    val modelPath    by ImageGenState.modelPath.collectAsState()

    var prompt         by remember { mutableStateOf("") }
    var negativePrompt by remember { mutableStateOf("") }
    var selectedSize   by remember { mutableStateOf("512x512") }
    var steps          by remember { mutableStateOf(20) }
    var cfgScale       by remember { mutableStateOf(7.5f) }

    val modelName = modelPath?.substringAfterLast('/')?.substringBeforeLast('.') ?: "None"
    val metaLine  = if (isLoaded) "MODEL · $modelName" else "NO MODEL LOADED"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AlpacaColors.Surface.Canvas)
            .verticalScroll(rememberScrollState())
    ) {
        EditorialMasthead(title = "Image.", meta = metaLine)

        // ---- Model status row ------------------------------------------------
        Row(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment   = Alignment.CenterVertically
        ) {
            StatusChip(
                text = if (isLoading) "Loading…" else if (isLoaded) "Ready" else "No model",
                tone = if (isLoaded && !isLoading) ChipTone.Success
                       else if (isLoading) ChipTone.Warning
                       else ChipTone.Neutral
            )
            if (isLoaded) {
                IconButton(
                    onClick  = { vm.unloadModel() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.FolderOpen,
                        contentDescription = "Unload model",
                        tint               = AlpacaColors.Text.Muted,
                        modifier           = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ---- Error banner ----------------------------------------------------
        AnimatedVisibility(visible = errorMessage != null, enter = fadeIn(), exit = fadeOut()) {
            errorMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .background(AlpacaColors.State.Error.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text  = msg,
                        style = AlpacaType.BodySm,
                        color = AlpacaColors.State.Error
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- Prompt input ----------------------------------------------------
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            MonoLabel(text = "PROMPT")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value         = prompt,
                onValueChange = { prompt = it },
                modifier      = Modifier.fillMaxWidth(),
                placeholder   = { Text("A llama wearing a top hat…", color = AlpacaColors.Text.Subtle, style = AlpacaType.BodyMd) },
                textStyle     = AlpacaType.BodyMd.copy(color = AlpacaColors.Text.Body),
                minLines      = 3,
                maxLines      = 5,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Default
                ),
                colors        = textFieldColors(),
                shape         = RoundedCornerShape(6.dp)
            )

            Spacer(Modifier.height(12.dp))
            MonoLabel(text = "NEGATIVE PROMPT")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value         = negativePrompt,
                onValueChange = { negativePrompt = it },
                modifier      = Modifier.fillMaxWidth(),
                placeholder   = { Text("blurry, low quality…", color = AlpacaColors.Text.Subtle, style = AlpacaType.BodyMd) },
                textStyle     = AlpacaType.BodyMd.copy(color = AlpacaColors.Text.Body),
                maxLines      = 3,
                colors        = textFieldColors(),
                shape         = RoundedCornerShape(6.dp)
            )

            Spacer(Modifier.height(12.dp))

            // ---- Size / steps row ------------------------------------------
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    MonoLabel(text = "SIZE")
                    Spacer(Modifier.height(6.dp))
                    SizeSelector(
                        selected  = selectedSize,
                        onSelect  = { selectedSize = it },
                        modifier  = Modifier.fillMaxWidth()
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    MonoLabel(text = "STEPS")
                    Spacer(Modifier.height(6.dp))
                    StepSelector(
                        steps    = steps,
                        onSteps  = { steps = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- Generate button --------------------------------------------
            Button(
                onClick  = {
                    vm.clearError()
                    val (w, h) = parseSize(selectedSize)
                    vm.generate(prompt.trim(), negativePrompt.trim(), w, h, steps, cfgScale)
                },
                enabled  = isLoaded && !isGenerating && prompt.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = RoundedCornerShape(6.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = AlpacaColors.Accent.Primary,
                    contentColor           = AlpacaColors.Text.OnAccent,
                    disabledContainerColor = AlpacaColors.Surface.Elevated,
                    disabledContentColor   = AlpacaColors.Text.Subtle
                )
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier  = Modifier.size(18.dp),
                        color     = AlpacaColors.Text.OnAccent,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector        = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier           = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Generate", style = AlpacaType.LabelLg)
                }
            }

            // ---- No model CTA -----------------------------------------------
            if (!isLoaded && !isLoading) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text  = "Load a diffusion model in the Models tab to generate images. Compatible formats: GGUF (SD 1.5, Tiny-SD).",
                    style = AlpacaType.BodySm,
                    color = AlpacaColors.Text.Muted
                )
            }
        }

        // ---- Generated image ------------------------------------------------
        vm.lastResult?.let { result ->
            Spacer(Modifier.height(24.dp))
            EditorialDivider(modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(16.dp))
            MonoLabel(
                text     = "${result.width} × ${result.height} PX",
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))
            val bitmap = remember(result) { result.toBitmap() }
            Image(
                bitmap             = bitmap.asImageBitmap(),
                contentDescription = "Generated image",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, AlpacaColors.Line.Hairline, RoundedCornerShape(8.dp))
                    .aspectRatio(result.width.toFloat() / result.height.toFloat())
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ---- Sub-components ---------------------------------------------------------

@Composable
private fun SizeSelector(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf("256x256", "384x384", "512x512", "512x768", "768x512")
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value         = selected,
            onValueChange = {},
            readOnly      = true,
            modifier      = Modifier.fillMaxWidth(),
            textStyle     = AlpacaType.BodyMd.copy(color = AlpacaColors.Text.Body),
            colors        = textFieldColors(),
            shape         = RoundedCornerShape(6.dp),
            trailingIcon  = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Outlined.FolderOpen, null, tint = AlpacaColors.Text.Muted, modifier = Modifier.size(16.dp))
                }
            }
        )
        if (expanded) {
            androidx.compose.material3.DropdownMenu(
                expanded         = expanded,
                onDismissRequest = { expanded = false },
                modifier         = Modifier.background(AlpacaColors.Surface.Card)
            ) {
                options.forEach { size ->
                    androidx.compose.material3.DropdownMenuItem(
                        text    = { Text(size, style = AlpacaType.BodyMd, color = AlpacaColors.Text.Body) },
                        onClick = { onSelect(size); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepSelector(steps: Int, onSteps: (Int) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(4, 8, 12, 20, 30, 50)
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value         = steps.toString(),
            onValueChange = {},
            readOnly      = true,
            modifier      = Modifier.fillMaxWidth(),
            textStyle     = AlpacaType.BodyMd.copy(color = AlpacaColors.Text.Body),
            colors        = textFieldColors(),
            shape         = RoundedCornerShape(6.dp),
            trailingIcon  = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Outlined.FolderOpen, null, tint = AlpacaColors.Text.Muted, modifier = Modifier.size(16.dp))
                }
            }
        )
        if (expanded) {
            androidx.compose.material3.DropdownMenu(
                expanded         = expanded,
                onDismissRequest = { expanded = false },
                modifier         = Modifier.background(AlpacaColors.Surface.Card)
            ) {
                options.forEach { s ->
                    androidx.compose.material3.DropdownMenuItem(
                        text    = { Text(s.toString(), style = AlpacaType.BodyMd, color = AlpacaColors.Text.Body) },
                        onClick = { onSteps(s); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = AlpacaColors.Accent.Primary,
    unfocusedBorderColor = AlpacaColors.Line.Hairline,
    cursorColor          = AlpacaColors.Accent.Primary,
    focusedTextColor     = AlpacaColors.Text.Body,
    unfocusedTextColor   = AlpacaColors.Text.Body,
    focusedContainerColor   = AlpacaColors.Surface.Recess,
    unfocusedContainerColor = AlpacaColors.Surface.Recess
)

private fun parseSize(size: String): Pair<Int, Int> {
    val parts = size.lowercase().split("x")
    val w = parts.getOrNull(0)?.toIntOrNull() ?: 512
    val h = parts.getOrNull(1)?.toIntOrNull() ?: 512
    return Pair(w, h)
}
