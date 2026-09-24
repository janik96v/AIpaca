package com.aipaca.app.ui.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.aipaca.app.data.DownloadedModelEntry
import com.aipaca.app.data.DownloadedModelStore
import com.aipaca.app.data.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ModelFileImport"

/**
 * Returns a launcher for the system file picker. The picked file is copied into
 * app storage (skipped when an identical copy is already there), registered
 * with [ModelDownloadManager] so it is listed with the downloads, and handed to
 * [onImported].
 *
 * GGUF has no registered MIME type, so the picker accepts any file; the model
 * kind is inferred from the name (`*.bin` → Whisper, `*mmproj*` → vision projector).
 */
@Composable
fun rememberModelImporter(
    onImporting: (Boolean) -> Unit,
    onImported: (DownloadedModelEntry) -> Unit,
    onFailed: () -> Unit
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onImporting(true)
            val file = importFile(context, uri)
            onImporting(false)
            if (file == null) {
                onFailed()
            } else {
                onImported(ModelDownloadManager.registerLocalFile(file, modelTypeFor(file.name)))
            }
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

private suspend fun importFile(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
    try {
        if (uri.scheme == "file") return@withContext uri.path?.let(::File)?.takeIf { it.isFile }

        var name = "model.gguf"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        val dest = File(DownloadedModelStore.modelsDir(context), File(name).name)
        if (dest.isFile && size > 0 && dest.length() == size) {
            Log.i(TAG, "Already imported: ${dest.absolutePath}")
            return@withContext dest
        }
        val partial = File(dest.parentFile, dest.name + ".part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            if (!partial.renameTo(dest)) return@withContext null
        } finally {
            // Only a failed or cancelled copy leaves the partial file behind.
            if (partial.exists()) partial.delete()
        }
        Log.i(TAG, "Imported model to ${dest.absolutePath}")
        dest
    } catch (e: Exception) {
        Log.e(TAG, "Import failed for $uri", e)
        null
    }
}
