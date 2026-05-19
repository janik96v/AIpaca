package com.aipaca.app.engine

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

data class ImageGenRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7.5f,
    val seed: Long = -1L
)

data class ImageGenResult(
    val rgbaBytes: ByteArray,
    val width: Int,
    val height: Int
) {
    fun toBitmap(): Bitmap {
        val pixels = IntArray(width * height) { i ->
            val b = i * 4
            val r = rgbaBytes[b].toInt() and 0xFF
            val g = rgbaBytes[b + 1].toInt() and 0xFF
            val bv = rgbaBytes[b + 2].toInt() and 0xFF
            val a = rgbaBytes[b + 3].toInt() and 0xFF
            (a shl 24) or (r shl 16) or (g shl 8) or bv
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun toPngBytes(): ByteArray {
        val bitmap = toBitmap()
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    fun toPngBase64(): String = Base64.encodeToString(toPngBytes(), Base64.NO_WRAP)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageGenResult) return false
        return width == other.width && height == other.height && rgbaBytes.contentEquals(other.rgbaBytes)
    }

    override fun hashCode(): Int {
        var result = rgbaBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

interface ImageGenerationEngine {
    suspend fun loadModel(modelPath: String, nThreads: Int = 4): Result<Unit>
    suspend fun generateImage(request: ImageGenRequest): Result<ImageGenResult>
    fun isLoaded(): Boolean
    fun getModelPath(): String?
    fun unload()
}
