package com.subtranslator

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sourceLang: String
        get() = sp.getString("source", "en")!!
        set(v) = sp.edit { putString("source", v) }

    var targetLang: String
        get() = sp.getString("target", "vi")!!
        set(v) = sp.edit { putString("target", v) }

    var engine: Engine
        get() = runCatching { Engine.valueOf(sp.getString("engine", null)!!) }.getOrDefault(Engine.WHISPER)
        set(v) = sp.edit { putString("engine", v.name) }

    var whisperModel: WhisperModel
        get() = runCatching { WhisperModel.valueOf(sp.getString("whisper_model", null)!!) }
            .getOrDefault(WhisperModel.SMALL)
        set(v) = sp.edit { putString("whisper_model", v.name) }

    val language get() = Languages.get(sourceLang)

    /** The speech model the current settings need, or null if the engine can't do the language. */
    val recognitionModel: ModelSpec?
        get() = when (engine) {
            Engine.WHISPER -> whisperModel.spec
            Engine.VOSK -> language.voskSpec
        }

    var showOriginal: Boolean
        get() = sp.getBoolean("show_original", true)
        set(v) = sp.edit { putBoolean("show_original", v) }

    /** Subtitle text size in sp. */
    var textSize: Int
        get() = sp.getInt("text_size", 18)
        set(v) = sp.edit { putInt("text_size", v) }

    /** How many subtitle lines the overlay shows before it scrolls. */
    var visibleLines: Int
        get() = sp.getInt("visible_lines", 3)
        set(v) = sp.edit { putInt("visible_lines", v) }

    var overlayCollapsed: Boolean
        get() = sp.getBoolean("overlay_collapsed", false)
        set(v) = sp.edit { putBoolean("overlay_collapsed", v) }

    /** Overlay distance from the bottom of the screen, in px. */
    var overlayY: Int
        get() = sp.getInt("overlay_y", -1)
        set(v) = sp.edit { putInt("overlay_y", v) }
}
