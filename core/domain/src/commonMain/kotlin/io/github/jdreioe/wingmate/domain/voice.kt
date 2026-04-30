package io.github.jdreioe.wingmate.domain

import kotlinx.serialization.Serializable

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
)

@Serializable
data class SpeechServiceConfig(
    val endpoint: String = "",
    val subscriptionKey: String = "",
)

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
)

@Serializable
data class UiSettings(
    val id: Int? = null,
    val name: String = "default",
    val primaryLanguage: String = "en-US",
    val secondaryLanguage: String = "en-US",
    val isWiggleMode: Boolean = false,
)
