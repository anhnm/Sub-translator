package com.subtranslator

import android.os.Build
import java.io.Closeable
import java.io.File

/** A loaded whisper.cpp model. Not thread-safe: use from one thread at a time. */
class WhisperContext private constructor(private var handle: Long) : Closeable {

    /** Transcribes 16 kHz mono samples; [audioCtx] 0 uses the full 30 s encoder window. */
    fun transcribe(samples: FloatArray, language: String, threads: Int, audioCtx: Int): String {
        check(handle != 0L) { "closed" }
        val bytes = WhisperLib.nativeTranscribe(handle, samples, language, threads, audioCtx, NO_SPEECH_THRESHOLD)
        return String(bytes, Charsets.UTF_8)
    }

    override fun close() {
        if (handle != 0L) WhisperLib.nativeFree(handle)
        handle = 0L
    }

    companion object {
        private const val NO_SPEECH_THRESHOLD = 0.6f

        fun load(model: File): WhisperContext {
            WhisperLib.ensureLoaded()
            val handle = WhisperLib.nativeInit(model.absolutePath)
            if (handle == 0L) throw IllegalStateException("Không nạp được mô hình Whisper")
            return WhisperContext(handle)
        }
    }
}

object WhisperLib {
    /**
     * The native library is built for ARMv8.2 with dot product and fp16 instructions; loading it
     * on an older CPU would crash, so check the CPU features first.
     */
    val isCpuSupported: Boolean by lazy {
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) return@lazy false
        val features = runCatching {
            File("/proc/cpuinfo").readLines()
                .filter { it.startsWith("Features") }
                .flatMap { it.substringAfter(':').trim().split(' ') }
                .toSet()
        }.getOrDefault(emptySet())
        "asimddp" in features && "asimdhp" in features
    }

    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        if (!isCpuSupported) throw IllegalStateException("CPU không hỗ trợ Whisper")
        System.loadLibrary("subwhisper")
        loaded = true
    }

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(handle: Long)
    external fun nativeTranscribe(
        handle: Long,
        audio: FloatArray,
        language: String,
        threads: Int,
        audioCtx: Int,
        noSpeechThreshold: Float,
    ): ByteArray
}
