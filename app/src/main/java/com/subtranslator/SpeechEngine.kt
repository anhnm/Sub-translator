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

/**
 * Captures audio played by other apps (AudioPlaybackCapture) on a background thread and feeds
 * it to a speech recognizer. All listener callbacks are delivered on the main thread.
 */
abstract class SpeechEngine(
    private val projection: MediaProjection,
    protected val language: Language,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        /** In-progress text; [SPEAKING] when speech is heard but no text is available yet. */
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    protected var running = false
        private set
    private var thread: Thread? = null

    /** Starts capturing; if [previous] is given, waits for it to release its model first. */
    fun start(previous: SpeechEngine? = null) {
        running = true
        thread = Thread({
            previous?.thread?.join()
            run()
        }, javaClass.simpleName).apply { start() }
    }

    /** Asks the engine to stop; it releases its resources on its own thread. */
    fun stop() {
        running = false
    }

    /** Loads the model. Runs on the engine thread. */
    protected abstract fun open()

    /** Reads [record] until [running] turns false. */
    protected abstract fun process(record: AudioRecord)

    /** Releases the model. Always called, even if [open] failed. */
    protected abstract fun close()

    @SuppressLint("MissingPermission") // checked by StartCaptureActivity before the service starts
    private fun run() {
        var record: AudioRecord? = null
        try {
            open()
            record = createRecord()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                postError("Không khởi tạo được thu âm hệ thống")
                return
            }
            record.startRecording()
            post { listener.onReady() }
            process(record)
        } catch (t: Throwable) {
            Log.e(TAG, "Speech engine failed", t)
            postError(t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                record?.stop()
            } catch (_: IllegalStateException) {
            }
            record?.release()
            close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord {
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
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    protected fun postPartial(text: String) = post { listener.onPartial(text) }
    protected fun postFinal(text: String) = post { listener.onFinal(text) }
    private fun postError(message: String) = post { listener.onError(message) }

    private fun post(block: () -> Unit) {
        if (running) main.post { if (running) block() }
    }

    companion object {
        private const val TAG = "SpeechEngine"
        const val SAMPLE_RATE = 16000
        const val SPEAKING = "…"
    }
}
