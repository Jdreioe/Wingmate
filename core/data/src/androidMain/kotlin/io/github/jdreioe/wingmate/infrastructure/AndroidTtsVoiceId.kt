package io.github.jdreioe.wingmate.infrastructure

internal data class ParsedAndroidTtsVoiceId(
    val enginePackageName: String?,
    val voiceName: String?,
)

internal object AndroidTtsVoiceId {
    private const val PREFIX = "android-tts|"
    private const val ENGINE_MARKER = "engine="
    private const val VOICE_MARKER = "|voice="

    fun encode(enginePackageName: String, voiceName: String): String {
        return "$PREFIX$ENGINE_MARKER$enginePackageName$VOICE_MARKER$voiceName"
    }

    fun parse(storedName: String?): ParsedAndroidTtsVoiceId {
        if (storedName.isNullOrBlank()) {
            return ParsedAndroidTtsVoiceId(enginePackageName = null, voiceName = null)
        }

        if (!storedName.startsWith(PREFIX)) {
            // Backward compatibility for previously persisted plain Android voice names.
            return ParsedAndroidTtsVoiceId(enginePackageName = null, voiceName = storedName)
        }

        val body = storedName.removePrefix(PREFIX)
        val separatorIndex = body.indexOf(VOICE_MARKER)
        if (separatorIndex <= 0) {
            return ParsedAndroidTtsVoiceId(enginePackageName = null, voiceName = storedName)
        }

        val enginePart = body.substring(0, separatorIndex)
        val voicePart = body.substring(separatorIndex + VOICE_MARKER.length)

        val enginePackageName = enginePart
            .removePrefix(ENGINE_MARKER)
            .takeIf { it.isNotBlank() }
        val voiceName = voicePart.takeIf { it.isNotBlank() }

        return if (enginePackageName != null && voiceName != null) {
            ParsedAndroidTtsVoiceId(enginePackageName = enginePackageName, voiceName = voiceName)
        } else {
            ParsedAndroidTtsVoiceId(enginePackageName = null, voiceName = storedName)
        }
    }
}