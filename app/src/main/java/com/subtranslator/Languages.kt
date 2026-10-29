package com.subtranslator

import com.google.mlkit.nl.translate.TranslateLanguage

/** A downloadable speech model. Vosk models are zip archives; Whisper models are single files. */
data class ModelSpec(
    val name: String,
    val url: String,
    val sizeMb: Int,
    val zipped: Boolean,
)

/**
 * A language the app can listen to and/or translate into. Whisper understands all of them;
 * Vosk only those with a [voskModel].
 */
data class Language(
    val code: String,
    val label: String,
    val voskModel: String? = null,
    val voskSizeMb: Int = 0,
) {
    val voskSpec
        get() = voskModel?.let {
            ModelSpec(it, "https://alphacephei.com/vosk/models/$it.zip", voskSizeMb, zipped = true)
        }

    /** Vosk separates CJK characters with spaces; strip them for display/translation. */
    val joinWithoutSpaces get() = code == TranslateLanguage.CHINESE || code == TranslateLanguage.JAPANESE

    override fun toString() = label
}

enum class Engine { WHISPER, VOSK }

enum class WhisperModel(val file: String, val sizeMb: Int) {
    BASE("ggml-base-q5_1.bin", 57),
    SMALL("ggml-small-q5_1.bin", 181);

    val spec get() = ModelSpec(file, "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file", sizeMb, zipped = false)
}

object Languages {
    val all = listOf(
        Language(TranslateLanguage.VIETNAMESE, "Tiếng Việt", "vosk-model-small-vn-0.4", 32),
        Language(TranslateLanguage.ENGLISH, "Tiếng Anh", "vosk-model-small-en-us-0.15", 40),
        Language(TranslateLanguage.CHINESE, "Tiếng Trung", "vosk-model-small-cn-0.22", 42),
        Language(TranslateLanguage.JAPANESE, "Tiếng Nhật", "vosk-model-small-ja-0.22", 48),
        Language(TranslateLanguage.KOREAN, "Tiếng Hàn", "vosk-model-small-ko-0.22", 82),
        Language(TranslateLanguage.THAI, "Tiếng Thái"),
        Language(TranslateLanguage.INDONESIAN, "Tiếng Indonesia"),
        Language(TranslateLanguage.MALAY, "Tiếng Mã Lai"),
        Language(TranslateLanguage.FRENCH, "Tiếng Pháp", "vosk-model-small-fr-0.22", 41),
        Language(TranslateLanguage.GERMAN, "Tiếng Đức", "vosk-model-small-de-0.15", 45),
        Language(TranslateLanguage.SPANISH, "Tiếng Tây Ban Nha", "vosk-model-small-es-0.42", 39),
        Language(TranslateLanguage.RUSSIAN, "Tiếng Nga", "vosk-model-small-ru-0.22", 45),
        Language(TranslateLanguage.ITALIAN, "Tiếng Ý", "vosk-model-small-it-0.22", 48),
        Language(TranslateLanguage.PORTUGUESE, "Tiếng Bồ Đào Nha", "vosk-model-small-pt-0.3", 31),
        Language(TranslateLanguage.HINDI, "Tiếng Hindi", "vosk-model-small-hi-0.22", 42),
        Language(TranslateLanguage.TURKISH, "Tiếng Thổ Nhĩ Kỳ", "vosk-model-small-tr-0.3", 35),
        Language(TranslateLanguage.DUTCH, "Tiếng Hà Lan", "vosk-model-small-nl-0.22", 39),
        Language(TranslateLanguage.POLISH, "Tiếng Ba Lan", "vosk-model-small-pl-0.22", 50),
        Language(TranslateLanguage.UKRAINIAN, "Tiếng Ukraina", "vosk-model-small-uk-v3-small", 133),
        Language(TranslateLanguage.ARABIC, "Tiếng Ả Rập"),
        Language(TranslateLanguage.PERSIAN, "Tiếng Ba Tư", "vosk-model-small-fa-0.42", 53),
        Language(TranslateLanguage.CATALAN, "Tiếng Catalan", "vosk-model-small-ca-0.4", 42),
    )

    fun get(code: String) = all.firstOrNull { it.code == code } ?: all.first()
}
