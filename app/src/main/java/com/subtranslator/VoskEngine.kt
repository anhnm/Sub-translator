package com.subtranslator

import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Streaming recognition with Vosk: fast and light, gives live partial results. */
class VoskEngine(
    projection: MediaProjection,
    language: Language,
    private val modelDir: File,
    listener: Listener,
) : SpeechEngine(projection, language, listener) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    override fun open() {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        model = Model(modelDir.absolutePath).also {
            recognizer = Recognizer(it, SAMPLE_RATE.toFloat())
        }
    }

    override fun process(record: AudioRecord) {
        val recognizer = recognizer ?: return
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
                if (text.isNotEmpty()) postFinal(text)
            } else {
                val text = clean(JSONObject(recognizer.partialResult).optString("partial"))
                if (text.isEmpty()) samplesInUtterance = 0
                if (text != lastPartial) {
                    lastPartial = text
                    if (text.isNotEmpty()) postPartial(text)
                }
            }
        }
    }

    override fun close() {
        recognizer?.close()
        model?.close()
    }

    private fun clean(text: String): String {
        val t = text.trim()
        return if (language.joinWithoutSpaces) t.replace(" ", "") else t
    }

    private companion object {
        const val TAG = "VoskEngine"
        const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 7
    }
}
