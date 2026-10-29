package com.subtranslator

import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Recognition with whisper.cpp. Whisper is not streaming, so audio is cut into sentence-sized
 * chunks at pauses (simple energy-based voice activity detection) and each chunk is transcribed
 * on a separate decoder thread while capture continues.
 */
class WhisperEngine(
    projection: MediaProjection,
    language: Language,
    private val modelFile: File,
    listener: Listener,
) : SpeechEngine(projection, language, listener) {

    private var whisper: WhisperContext? = null
    private val chunks = LinkedBlockingDeque<FloatArray>()
    private var decoder: Thread? = null

    @Volatile
    private var closing = false

    override fun open() {
        whisper = WhisperContext.load(modelFile)
        decoder = Thread(::decodeLoop, "whisper-decoder").apply { start() }
    }

    override fun close() {
        // The decoder finishes its current chunk and exits.
        closing = true
        decoder?.join()
        whisper?.close()
    }

    // ---- Capture + voice activity detection (engine thread) ----

    override fun process(record: AudioRecord) {
        val frame = ShortArray(FRAME)
        val segment = FloatArray(HARD_MAX)
        val preRoll = ArrayDeque<FloatArray>()
        var length = 0
        var inSpeech = false
        var silentFrames = 0
        var voicedFrames = 0
        var energySum = 0f
        var energyFrames = 0
        var noise = 0.005f

        fun emit() {
            if (voicedFrames >= MIN_VOICED_FRAMES) {
                // whisper.cpp ignores input shorter than 1 s; pad with silence.
                chunks.offer(segment.copyOf(maxOf(length, MIN_CHUNK)))
            }
            length = 0; voicedFrames = 0; silentFrames = 0; energySum = 0f; energyFrames = 0
        }

        while (running) {
            val n = record.read(frame, 0, FRAME)
            if (n <= 0) continue
            val samples = FloatArray(n) { frame[it] / 32768f }
            val rms = sqrt(samples.fold(0f) { acc, s -> acc + s * s } / n)

            // Track the background level: follow it down quickly, up slowly.
            noise = if (rms < noise) noise * 0.9f + rms * 0.1f else noise * 0.995f + rms * 0.005f
            val threshold = maxOf(MIN_THRESHOLD, noise * 2.5f)
            val voiced = rms > threshold

            if (!inSpeech) {
                preRoll.addLast(samples)
                if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                if (!voiced) continue
                inSpeech = true
                postPartial(SPEAKING)
                for (f in preRoll) {
                    f.copyInto(segment, length)
                    length += f.size
                }
                preRoll.clear()
            } else {
                samples.copyInto(segment, length)
                length += n
            }

            energySum += rms
            energyFrames++
            if (voiced) {
                voicedFrames++
                silentFrames = 0
            } else {
                silentFrames++
            }

            val pause = silentFrames >= END_SILENCE_FRAMES
            // Speech over constant background (music, crowd) rarely goes fully silent:
            // after a while, also cut at a noticeable dip in loudness.
            val dip = length >= SOFT_MAX && rms < 0.5f * energySum / energyFrames
            if (pause || dip || length + FRAME > HARD_MAX) {
                emit()
                inSpeech = !pause
            }
        }
        if (inSpeech) emit()
    }

    // ---- Transcription (decoder thread) ----

    private fun decodeLoop() {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        var fast = false
        while (running && !closing) {
            var chunk = chunks.poll(200, TimeUnit.MILLISECONDS) ?: continue
            // Catch up if recognition fell behind: merge waiting chunks, drop very old audio.
            while (chunks.sumOf { it.size } > MAX_BACKLOG) chunks.pollFirst()
            while (chunks.isNotEmpty() && chunk.size + chunks.peekFirst()!!.size <= MERGE_MAX) {
                chunk += chunks.pollFirst()!!
            }

            val seconds = chunk.size.toFloat() / SAMPLE_RATE
            // A smaller encoder window is much faster at a small accuracy cost; only use it
            // when the phone can't keep up with the full window.
            val audioCtx = if (fast) minOf(1500, ((seconds + 2) * 50).toInt()) else 0
            val start = SystemClock.elapsedRealtime()
            val text = try {
                whisper?.transcribe(chunk, language.code, threads, audioCtx).orEmpty()
            } catch (t: Throwable) {
                Log.e(TAG, "Transcription failed", t)
                ""
            }
            val ratio = (SystemClock.elapsedRealtime() - start) / 1000f / seconds
            fast = if (fast) ratio > 0.25f else ratio > 0.7f

            clean(text)?.let(::postFinal)
        }
    }

    /** Removes markup and Whisper's typical hallucinations; null if nothing useful remains. */
    private fun clean(raw: String): String? {
        var text = raw
            .replace(BRACKETED, " ")
            .replace(REPEATS, "$1")
            .replace(WHITESPACE, " ")
            .trim()
        if (language.joinWithoutSpaces) text = text.replace(" ", "")
        if (text.none { it.isLetterOrDigit() }) return null
        val lower = text.lowercase()
        if (text.length < 80 && HALLUCINATIONS.any { it in lower }) return null
        return text
    }

    private companion object {
        const val TAG = "WhisperEngine"
        const val FRAME = SAMPLE_RATE / 10 // 100 ms
        const val PRE_ROLL_FRAMES = 3
        const val END_SILENCE_FRAMES = 6 // 600 ms pause ends a sentence
        const val MIN_VOICED_FRAMES = 3
        const val SOFT_MAX = SAMPLE_RATE * 6
        const val HARD_MAX = SAMPLE_RATE * 12
        const val MIN_CHUNK = SAMPLE_RATE * 11 / 10
        const val MERGE_MAX = SAMPLE_RATE * 15
        const val MAX_BACKLOG = SAMPLE_RATE * 30
        const val MIN_THRESHOLD = 0.008f

        val BRACKETED = Regex("""\[[^\]]*]|\([^)]*\)|♪|♫|\*[^*]*\*""")
        val REPEATS = Regex("""(.{2,40}?)(?:[\s,.!?、。，]*\1){3,}""")
        val WHITESPACE = Regex("""\s+""")

        /** Phrases Whisper tends to invent on silence or music (it learned them from subtitles). */
        val HALLUCINATIONS = listOf(
            "subscribe", "đăng ký kênh", "đăng kí kênh", "like và share", "like share",
            "cảm ơn các bạn đã theo dõi", "cảm ơn các bạn đã xem", "hẹn gặp lại các bạn",
            "ghiền mì gõ", "la la school", "thank you for watching", "thanks for watching",
            "amara.org", "ご視聴ありがとうございました", "チャンネル登録", "字幕", "订阅", "點贊", "点赞",
            "시청해주셔서", "구독",
        )
    }
}
