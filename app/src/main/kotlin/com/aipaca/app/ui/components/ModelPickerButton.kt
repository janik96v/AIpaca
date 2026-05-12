package com.aipaca.app.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aipaca.app.ui.theme.AIpacaTheme
import com.aipaca.app.ui.theme.AlpacaColors
import com.aipaca.app.ui.theme.AlpacaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ModelPickerButton"

/**
 * Opens the system file picker for any file type (GGUF has no standard MIME)
 * and, for SAF content URIs, copies the file into internal storage before
 * calling [onModelSelected] with the resolved absolute path.
 *
 * Editorial styling: terracotta accent, sentence-case "Load model" label.
 */
@Composable
fun ModelPickerButton(
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = resolveUri(context, uri)
            if (path != null) {
                onModelSelected(path)
            } else {
                Log.e(TAG, "Could not resolve URI: $uri")
            }
        }
    }

    Button(
        onClick  = { launcher.launch(arrayOf("*/*")) },
        modifier = modifier,
        enabled  = !isLoading,
        shape    = RoundedCornerShape(6.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor         = AlpacaColors.Accent.Primary,
            contentColor           = AlpacaColors.Text.OnAccent,
            disabledContainerColor = AlpacaColors.Surface.Elevated,
            disabledContentColor   = AlpacaColors.Text.Subtle
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier    = Modifier.size(16.dp),
                color       = AlpacaColors.Text.OnAccent,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector        = Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier           = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text  = if (isLoading) "Loading model" else "Load model",
            style = AlpacaType.LabelLg
        )
    }
}

private suspend fun resolveUri(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            if (uri.scheme == "file") {
                return@withContext uri.path
            }
            val fileName = getFileName(context, uri) ?: "model.gguf"
            val destFile = File(context.filesDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied model to ${destFile.absolutePath}")
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "resolveUri failed", e)
            null
        }
    }

private fun getFileName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
    } catch (e: Exception) {
        null
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF14140F)
@Composable
private fun ModelPickerButtonPreview() {
    AIpacaTheme {
        ModelPickerButton(onModelSelected = {})
    }
}
