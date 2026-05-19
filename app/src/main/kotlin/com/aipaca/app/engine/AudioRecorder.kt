package com.aipaca.app.engine

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AudioRecorder"

// 16 kHz mono PCM-16 — whisper.cpp's required input format
private const val SAMPLE_RATE = 16_000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

/**
 * Records microphone audio and returns a [FloatArray] in the format expected by
 * whisper.cpp: 16 kHz, mono, float32 in [-1.0, 1.0].
 *
 * Usage: call [record] to start capturing (suspends until [stopRecording] is called),
 * then await the FloatArray result.
 */
class AudioRecorder {

    private val isRecording = AtomicBoolean(false)

    /**
     * Records from the microphone. Suspends on [Dispatchers.IO] until [stopRecording]
     * is called. The AudioRecord is always released in the `finally` block.
     *
     * @throws SecurityException if RECORD_AUDIO permission is missing.
     * @throws IllegalStateException if AudioRecord cannot be initialised.
     */
    suspend fun record(): FloatArray = withContext(Dispatchers.IO) {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        check(minBufSize != AudioRecord.ERROR && minBufSize != AudioRecord.ERROR_BAD_VALUE) {
            "AudioRecord.getMinBufferSize returned error"
        }

        val readChunkSize = SAMPLE_RATE / 10  // 1600 shorts = 0.1 s
        val bufSize = maxOf(minBufSize, readChunkSize * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufSize
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "AudioRecord failed to initialise"
        }

        isRecording.set(true)
        val rawSamples = mutableListOf<Short>()
        val chunk = ShortArray(readChunkSize)

        try {
            recorder.startRecording()
            Log.i(TAG, "Recording started at $SAMPLE_RATE Hz")

            while (isActive && isRecording.get()) {
                val read = recorder.read(chunk, 0, readChunkSize)
                if (read > 0) repeat(read) { i -> rawSamples.add(chunk[i]) }
            }
        } finally {
            recorder.stop()
            recorder.release()
            Log.i(TAG, "Recording stopped — ${rawSamples.size} samples (${rawSamples.size / SAMPLE_RATE.toFloat()}s)")
        }

        // Convert 16-bit PCM shorts to float32 in [-1, 1]
        FloatArray(rawSamples.size) { i -> rawSamples[i] / 32768f }
    }

    /** Signal the recording loop to stop. Thread-safe. */
    fun stopRecording() {
        isRecording.set(false)
    }
}
