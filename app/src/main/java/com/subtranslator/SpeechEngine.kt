package com.subtranslator

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Captures audio played by other apps (AudioPlaybackCapture) and runs it through Vosk.
 * All callbacks are delivered on the main thread.
 */
class SpeechEngine(
    private val projection: MediaProjection,
    private val language: SourceLanguage,
    private val modelDir: File,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::run, "speech-engine").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }

    @SuppressLint("MissingPermission") // checked by StartCaptureActivity before the service starts
    private fun run() {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var record: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                post { listener.onError("Không khởi tạo được thu âm hệ thống") }
                return
            }
            record.startRecording()
            post { listener.onReady() }

            val buffer = ShortArray(SAMPLE_RATE / 5) // 200 ms chunks
            var lastPartial = ""
            var samplesInUtterance = 0
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) {
                    if (n < 0) Log.w(TAG, "AudioRecord.read returned $n")
                    continue
                }
                samplesInUtterance += n
                val isFinal = recognizer.acceptWaveForm(buffer, n)
                if (isFinal || (lastPartial.isNotEmpty() && samplesInUtterance > MAX_UTTERANCE_SAMPLES)) {
                    // Long uninterrupted speech never hits Vosk's silence endpoint; cut it manually.
                    val json = if (isFinal) recognizer.result else recognizer.finalResult
                    val text = clean(JSONObject(json).optString("text"))
                    lastPartial = ""
                    samplesInUtterance = 0
                    if (text.isNotEmpty()) post { listener.onFinal(text) }
                } else {
                    val text = clean(JSONObject(recognizer.partialResult).optString("partial"))
                    if (text.isEmpty()) samplesInUtterance = 0
                    if (text != lastPartial) {
                        lastPartial = text
                        if (text.isNotEmpty()) post { listener.onPartial(text) }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Speech engine failed", t)
            post { listener.onError(t.message ?: t.javaClass.simpleName) }
        } finally {
            try {
                record?.stop()
            } catch (_: IllegalStateException) {
            }
            record?.release()
            recognizer?.close()
            model?.close()
        }
    }

    private fun clean(text: String): String {
        val t = text.trim()
        return if (language.joinWithoutSpaces) t.replace(" ", "") else t
    }

    private fun post(block: () -> Unit) {
        if (running) main.post { if (running) block() }
    }

    companion object {
        private const val TAG = "SpeechEngine"
        const val SAMPLE_RATE = 16000
        private const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 12
    }
}
