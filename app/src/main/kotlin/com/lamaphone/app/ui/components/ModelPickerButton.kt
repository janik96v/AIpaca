package com.lamaphone.app.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.lamaphone.app.ui.theme.LamaPhoneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ModelPickerButton"

/**
 * A button that opens the system file picker filtered to any file type
 * (GGUF files have no standard MIME type), copies the selected file into
 * the app's internal storage if it is a SAF content URI, then calls
 * [onModelSelected] with the resolved absolute path.
 */
@Composable
fun ModelPickerButton(
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
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
        onClick   = { launcher.launch(arrayOf("*/*")) },
        modifier  = modifier
    ) {
        Icon(
            imageVector        = Icons.Filled.FolderOpen,
            contentDescription = null
        )
        Text(text = "  Load model from storage")
    }
}

/**
 * Resolve a content URI to an absolute file path.
 * For SAF URIs the file is copied to internal storage first.
 */
private suspend fun resolveUri(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            // Try to get a direct file:// path first
            if (uri.scheme == "file") {
                return@withContext uri.path
            }

            // Content URI — copy to internal storage
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

@Preview(showBackground = true)
@Composable
private fun ModelPickerButtonPreview() {
    LamaPhoneTheme {
        ModelPickerButton(onModelSelected = {})
    }
}
