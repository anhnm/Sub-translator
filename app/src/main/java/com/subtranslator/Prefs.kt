package com.subtranslator

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sourceLang: String
        get() = sp.getString("source", Languages.sources.first().code)!!
        set(v) = sp.edit { putString("source", v) }

    var targetLang: String
        get() = sp.getString("target", Languages.targets.first().code)!!
        set(v) = sp.edit { putString("target", v) }

    var showOriginal: Boolean
        get() = sp.getBoolean("show_original", true)
        set(v) = sp.edit { putBoolean("show_original", v) }

    /** Subtitle text size in sp. */
    var textSize: Int
        get() = sp.getInt("text_size", 18)
        set(v) = sp.edit { putInt("text_size", v) }

    /** Overlay distance from the bottom of the screen, in px. */
    var overlayY: Int
        get() = sp.getInt("overlay_y", -1)
        set(v) = sp.edit { putInt("overlay_y", v) }
}
