package fi.marmorikatu.core.config

/**
 * The language the voice assistant listens and speaks in. Finnish is the house
 * default, but native STT/TTS assets for Finnish are not present on every device
 * (notably many iPads), where recognition and synthesis silently do nothing —
 * English then gives a working voice assistant on any device.
 *
 * [bcp47] is the dash-form tag used by Android (`EXTRA_LANGUAGE`,
 * `Locale.forLanguageTag`) and iOS `AVSpeechSynthesisVoice`; [iosLocale] is the
 * underscore-form identifier `NSLocale`/`SFSpeechRecognizer` expects.
 */
enum class SpeechLanguage(
    val bcp47: String,
    val iosLocale: String,
    val label: String,
) {
    Finnish("fi-FI", "fi_FI", "Suomi"),
    English("en-US", "en_US", "English");

    /** Primary subtag ("fi"/"en"), for matching regional voice variants. */
    val prefix: String get() = bcp47.substringBefore('-')

    companion object {
        /** Parse a persisted name, defaulting to [Finnish] on anything unknown. */
        fun fromName(raw: String?): SpeechLanguage =
            entries.firstOrNull { it.name == raw } ?: Finnish
    }
}
