package fi.marmorikatu.core.config

/**
 * The assistant's persona: drives both the animated avatar face and the native
 * TTS voice (naisääni / miesääni). Finnish names because that's the user-facing
 * vocabulary and the persisted value.
 */
enum class AssistantGender {
    Nainen,
    Mies;

    companion object {
        /** Parse a persisted name, defaulting to [Nainen] on anything unknown. */
        fun fromName(raw: String?): AssistantGender =
            entries.firstOrNull { it.name == raw } ?: Nainen
    }
}
