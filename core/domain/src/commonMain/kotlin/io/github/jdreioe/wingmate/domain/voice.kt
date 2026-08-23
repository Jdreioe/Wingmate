package io.github.jdreioe.wingmate.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class VoiceProvider {
    AZURE,
    GOOGLE,
}

@Serializable
enum class GoogleVoiceModel(val apiModelName: String? = null) {
    GEMINI_3_1_FLASH("gemini-3.1-flash-tts-preview"),
    GEMINI_2_5_FLASH("gemini-2.5-flash-tts"),
    GEMINI_2_5_FLASH_LITE("gemini-2.5-flash-lite-preview-tts"),
    GEMINI_2_5_PRO("gemini-2.5-pro-tts"),
    CHIRP_3_HD,
    STUDIO,
    NEURAL2,
    WAVENET,
    STANDARD,
    OTHER,
}

@Serializable
data class Voice(
    val id: Int? = null,
    val name: String? = null,
    val supportedLanguages: List<String>? = null,
    val gender: String? = null,
    val primaryLanguage: String? = null,
    val createdAt: Long? = null,
    val displayName: String? = null,
    val selectedLanguage: String = "",
    val pitch: Double? = null,
    val rate: Double? = null,
    val pitchForSSML: String? = null,
    val rateForSSML: String? = null,
    /** Cloud catalog owner. Nullable so catalogs saved by older Wingmate versions remain readable. */
    val provider: VoiceProvider? = null,
    /** Google synthesis tier, when this is a Google Cloud voice. */
    val googleModel: GoogleVoiceModel? = null,
    /** Provider-facing voice name when [name] is a stable Wingmate catalog identifier. */
    val providerVoiceName: String? = null,
    /** Request Azure's Math-domain pronunciation rules for plain-text expressions. */
    @Transient val mathMode: Boolean = false,
) {
    /** String bridge for Swift, which cannot reliably bind nullable Kotlin enum properties. */
    val googleModelId: String?
        get() = resolvedGoogleModel()?.name
}

fun Voice.resolvedProvider(): VoiceProvider {
    provider?.let { return it }
    val voiceName = name.orEmpty()
    return if (
        voiceName.startsWith("gemini-") ||
        voiceName.contains("-Chirp", ignoreCase = true) ||
        voiceName.contains("-Journey-", ignoreCase = true) ||
        voiceName.contains("-Neural2-", ignoreCase = true) ||
        voiceName.contains("-Polyglot-", ignoreCase = true) ||
        voiceName.contains("-Standard-", ignoreCase = true) ||
        voiceName.contains("-Studio-", ignoreCase = true) ||
        voiceName.contains("-Wavenet-", ignoreCase = true)
    ) VoiceProvider.GOOGLE else VoiceProvider.AZURE
}

fun Voice.resolvedGoogleModel(): GoogleVoiceModel? {
    googleModel?.let { return it }
    val voiceName = name.orEmpty()
    return when {
        voiceName.startsWith("gemini-3.1-flash-tts-preview|") -> GoogleVoiceModel.GEMINI_3_1_FLASH
        voiceName.startsWith("gemini-2.5-flash-tts|") -> GoogleVoiceModel.GEMINI_2_5_FLASH
        voiceName.startsWith("gemini-2.5-flash-lite-preview-tts|") -> GoogleVoiceModel.GEMINI_2_5_FLASH_LITE
        voiceName.startsWith("gemini-2.5-pro-tts|") -> GoogleVoiceModel.GEMINI_2_5_PRO
        voiceName.contains("-Chirp3-HD-", ignoreCase = true) -> GoogleVoiceModel.CHIRP_3_HD
        voiceName.contains("-Studio-", ignoreCase = true) -> GoogleVoiceModel.STUDIO
        voiceName.contains("-Neural2-", ignoreCase = true) -> GoogleVoiceModel.NEURAL2
        voiceName.contains("-Wavenet-", ignoreCase = true) -> GoogleVoiceModel.WAVENET
        voiceName.contains("-Standard-", ignoreCase = true) -> GoogleVoiceModel.STANDARD
        resolvedProvider() == VoiceProvider.GOOGLE -> GoogleVoiceModel.OTHER
        else -> null
    }
}

fun List<Voice>.forTtsEngine(engine: TtsEngine): List<Voice> = when (engine) {
    TtsEngine.SYSTEM -> emptyList()
    TtsEngine.GOOGLE_CLOUD -> filter { it.resolvedProvider() == VoiceProvider.GOOGLE }
    TtsEngine.AZURE_USER_RESOURCE,
    TtsEngine.AZURE_MANAGED,
    -> filter { it.resolvedProvider() == VoiceProvider.AZURE }
}

fun Voice.withPreferredSupportedLanguage(preferredLanguage: String?): Voice {
    val supported = (supportedLanguages.orEmpty() + listOfNotNull(primaryLanguage)).distinct()
    val language = preferredLanguage?.takeIf { it in supported }
        ?: selectedLanguage.takeIf { it in supported }
        ?: primaryLanguage
        ?: return this
    return copy(selectedLanguage = language)
}

/**
 * Backfills catalog-only metadata (secondary locales, provider, model) onto a voice
 * persisted by an older Wingmate version. Older builds saved the selected voice
 * without the cloud catalog's fields, so multilingual voices only offered their
 * primary locale. The persisted voice keeps any metadata it already carries.
 */
fun Voice.withCatalogMetadata(catalog: List<Voice>): Voice {
    val fresh = catalog.firstOrNull { it.name == name } ?: return this
    return copy(
        supportedLanguages = supportedLanguages ?: fresh.supportedLanguages,
        provider = provider ?: fresh.provider,
        googleModel = googleModel ?: fresh.googleModel,
        providerVoiceName = providerVoiceName ?: fresh.providerVoiceName,
        displayName = displayName ?: fresh.displayName,
        primaryLanguage = primaryLanguage ?: fresh.primaryLanguage,
    )
}

/**
 * Collapse Google's locale-prefixed names into one entry per model and speaker.
 * The entry retains every locale in which that speaker is available.
 */
fun List<Voice>.toGoogleVoiceCatalog(): List<Voice> {
    val recognized = filter { it.resolvedGoogleModel() != GoogleVoiceModel.OTHER }
        .groupBy { voice -> voice.resolvedGoogleModel() to voice.googleSpeakerName() }
        .mapNotNull { (key, variants) ->
            val model = key.first ?: return@mapNotNull null
            val speaker = key.second.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val languages = variants
                .flatMap { listOfNotNull(it.primaryLanguage) + it.supportedLanguages.orEmpty() }
                .distinct()
                .sorted()
            variants.first().copy(
                name = "google|${model.name}|$speaker",
                displayName = model.googleDisplayName(speaker),
                supportedLanguages = languages,
                primaryLanguage = languages.firstOrNull(),
                selectedLanguage = "",
                provider = VoiceProvider.GOOGLE,
                googleModel = model,
                providerVoiceName = speaker,
            )
        }

    val gemini = recognized
        .filter { it.googleModel == GoogleVoiceModel.CHIRP_3_HD }
        .flatMap { chirpVoice ->
            GoogleVoiceModel.entries.filter { it.apiModelName != null }.map { model ->
                chirpVoice.copy(
                    name = "google|${model.name}|${chirpVoice.providerVoiceName}",
                    displayName = chirpVoice.providerVoiceName,
                    googleModel = model,
                )
            }
        }

    val other = filter { it.resolvedGoogleModel() == GoogleVoiceModel.OTHER }
        .map { voice ->
            voice.copy(
                displayName = voice.name?.substringAfter('-', voice.name.orEmpty())?.substringAfter('-'),
                provider = VoiceProvider.GOOGLE,
                googleModel = GoogleVoiceModel.OTHER,
                providerVoiceName = voice.name,
            )
        }
    return (gemini + recognized + other).distinctBy { it.name }
}

private fun Voice.googleSpeakerName(): String = when (resolvedGoogleModel()) {
    GoogleVoiceModel.CHIRP_3_HD -> name.orEmpty().substringAfter("-Chirp3-HD-")
    GoogleVoiceModel.STUDIO -> name.orEmpty().substringAfter("-Studio-")
    GoogleVoiceModel.NEURAL2 -> name.orEmpty().substringAfter("-Neural2-")
    GoogleVoiceModel.WAVENET -> name.orEmpty().substringAfter("-Wavenet-")
    GoogleVoiceModel.STANDARD -> name.orEmpty().substringAfter("-Standard-")
    else -> providerVoiceName ?: name.orEmpty()
}

private fun GoogleVoiceModel.googleDisplayName(speaker: String): String = when (this) {
    GoogleVoiceModel.CHIRP_3_HD,
    GoogleVoiceModel.GEMINI_3_1_FLASH,
    GoogleVoiceModel.GEMINI_2_5_FLASH,
    GoogleVoiceModel.GEMINI_2_5_FLASH_LITE,
    GoogleVoiceModel.GEMINI_2_5_PRO,
    -> speaker
    GoogleVoiceModel.STUDIO -> "Studio $speaker"
    GoogleVoiceModel.NEURAL2 -> "Neural2 $speaker"
    GoogleVoiceModel.WAVENET -> "WaveNet $speaker"
    GoogleVoiceModel.STANDARD -> "Standard $speaker"
    GoogleVoiceModel.OTHER -> speaker
}

fun Voice?.withLanguageOverride(languageTag: String?): Voice? {
    val language = languageTag?.trim()?.takeIf(String::isNotEmpty) ?: return this
    return (this ?: Voice()).copy(primaryLanguage = language, selectedLanguage = language)
}

@Serializable
data class SpeechServiceConfig(
    val endpoint: String = "",
    val subscriptionKey: String = "",
) {
    override fun toString(): String =
        "SpeechServiceConfig(endpoint=$endpoint, subscriptionKey=<redacted>)"
}

/** Safe representation for settings screens and platform bridges. */
@Serializable
data class SpeechServiceConfigStatus(
    val endpoint: String = "",
    val credentialConfigured: Boolean = false,
)

/** Device-local Google Cloud Text-to-Speech BYOK credential. */
@Serializable
data class GoogleSpeechConfig(val apiKey: String = "") {
    override fun toString(): String = "GoogleSpeechConfig(apiKey=<redacted>)"
}

/** Safe representation for native settings screens. */
@Serializable
data class GoogleSpeechConfigStatus(val credentialConfigured: Boolean = false)

@Serializable
data class SaidText(
    val id: Int? = null,
    val date: Long? = null,
    val saidText: String? = null,
    val voiceName: String? = null,
    val pitch: Double? = null,
    val speed: Double? = null,
    val audioFilePath: String? = null,
    val createdAt: Long? = null,
    val position: Int? = null,
    val primaryLanguage: String? = null,
    /** Whether this playback was eligible for the user-facing History feed. */
    val visibleInHistory: Boolean = true,
)

@Serializable
data class UiSettings(
    val id: Int? = null,
    val name: String = "default",
    val primaryLanguage: String = "en-US",
    val secondaryLanguage: String = "",
    val isWiggleMode: Boolean = false,
)
