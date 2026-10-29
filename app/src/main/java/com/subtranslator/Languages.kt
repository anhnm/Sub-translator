package com.subtranslator

import com.google.mlkit.nl.translate.TranslateLanguage

/** A language the app can listen to: needs a Vosk speech model and an ML Kit translation code. */
data class SourceLanguage(
    val code: String,
    val label: String,
    val voskModel: String,
    val sizeMb: Int,
) {
    val modelUrl get() = "https://alphacephei.com/vosk/models/$voskModel.zip"

    /** Vosk separates CJK characters with spaces; strip them for display/translation. */
    val joinWithoutSpaces get() = code == TranslateLanguage.CHINESE || code == TranslateLanguage.JAPANESE

    override fun toString() = "$label (~$sizeMb MB)"
}

data class TargetLanguage(val code: String, val label: String) {
    override fun toString() = label
}

object Languages {
    val sources = listOf(
        SourceLanguage(TranslateLanguage.ENGLISH, "Tiếng Anh", "vosk-model-small-en-us-0.15", 40),
        SourceLanguage(TranslateLanguage.CHINESE, "Tiếng Trung", "vosk-model-small-cn-0.22", 42),
        SourceLanguage(TranslateLanguage.JAPANESE, "Tiếng Nhật", "vosk-model-small-ja-0.22", 48),
        SourceLanguage(TranslateLanguage.KOREAN, "Tiếng Hàn", "vosk-model-small-ko-0.22", 82),
        SourceLanguage(TranslateLanguage.VIETNAMESE, "Tiếng Việt", "vosk-model-small-vn-0.4", 32),
        SourceLanguage(TranslateLanguage.FRENCH, "Tiếng Pháp", "vosk-model-small-fr-0.22", 41),
        SourceLanguage(TranslateLanguage.GERMAN, "Tiếng Đức", "vosk-model-small-de-0.15", 45),
        SourceLanguage(TranslateLanguage.SPANISH, "Tiếng Tây Ban Nha", "vosk-model-small-es-0.42", 39),
        SourceLanguage(TranslateLanguage.RUSSIAN, "Tiếng Nga", "vosk-model-small-ru-0.22", 45),
        SourceLanguage(TranslateLanguage.ITALIAN, "Tiếng Ý", "vosk-model-small-it-0.22", 48),
        SourceLanguage(TranslateLanguage.PORTUGUESE, "Tiếng Bồ Đào Nha", "vosk-model-small-pt-0.3", 31),
        SourceLanguage(TranslateLanguage.HINDI, "Tiếng Hindi", "vosk-model-small-hi-0.22", 42),
        SourceLanguage(TranslateLanguage.TURKISH, "Tiếng Thổ Nhĩ Kỳ", "vosk-model-small-tr-0.3", 35),
        SourceLanguage(TranslateLanguage.DUTCH, "Tiếng Hà Lan", "vosk-model-small-nl-0.22", 39),
        SourceLanguage(TranslateLanguage.POLISH, "Tiếng Ba Lan", "vosk-model-small-pl-0.22", 50),
        SourceLanguage(TranslateLanguage.UKRAINIAN, "Tiếng Ukraina", "vosk-model-small-uk-v3-small", 133),
        SourceLanguage(TranslateLanguage.PERSIAN, "Tiếng Ba Tư", "vosk-model-small-fa-0.42", 53),
        SourceLanguage(TranslateLanguage.CATALAN, "Tiếng Catalan", "vosk-model-small-ca-0.4", 42),
    )

    val targets = listOf(
        TargetLanguage(TranslateLanguage.VIETNAMESE, "Tiếng Việt"),
        TargetLanguage(TranslateLanguage.ENGLISH, "Tiếng Anh"),
        TargetLanguage(TranslateLanguage.CHINESE, "Tiếng Trung"),
        TargetLanguage(TranslateLanguage.JAPANESE, "Tiếng Nhật"),
        TargetLanguage(TranslateLanguage.KOREAN, "Tiếng Hàn"),
        TargetLanguage(TranslateLanguage.THAI, "Tiếng Thái"),
        TargetLanguage(TranslateLanguage.INDONESIAN, "Tiếng Indonesia"),
        TargetLanguage(TranslateLanguage.MALAY, "Tiếng Mã Lai"),
        TargetLanguage(TranslateLanguage.FRENCH, "Tiếng Pháp"),
        TargetLanguage(TranslateLanguage.GERMAN, "Tiếng Đức"),
        TargetLanguage(TranslateLanguage.SPANISH, "Tiếng Tây Ban Nha"),
        TargetLanguage(TranslateLanguage.RUSSIAN, "Tiếng Nga"),
        TargetLanguage(TranslateLanguage.ITALIAN, "Tiếng Ý"),
        TargetLanguage(TranslateLanguage.PORTUGUESE, "Tiếng Bồ Đào Nha"),
        TargetLanguage(TranslateLanguage.HINDI, "Tiếng Hindi"),
        TargetLanguage(TranslateLanguage.TURKISH, "Tiếng Thổ Nhĩ Kỳ"),
        TargetLanguage(TranslateLanguage.DUTCH, "Tiếng Hà Lan"),
        TargetLanguage(TranslateLanguage.POLISH, "Tiếng Ba Lan"),
        TargetLanguage(TranslateLanguage.UKRAINIAN, "Tiếng Ukraina"),
        TargetLanguage(TranslateLanguage.ARABIC, "Tiếng Ả Rập"),
        TargetLanguage(TranslateLanguage.PERSIAN, "Tiếng Ba Tư"),
        TargetLanguage(TranslateLanguage.CATALAN, "Tiếng Catalan"),
    )

    fun source(code: String) = sources.firstOrNull { it.code == code } ?: sources.first()
    fun target(code: String) = targets.firstOrNull { it.code == code } ?: targets.first()
}
