package io.github.jdreioe.wingmate.domain

import kotlinx.serialization.Serializable

@Serializable
data class Phrase(
    val id: String,
    val text: String,
    val name: String? = null,
    val backgroundColor: String? = null,
    val parentId: String? = null,
    val isCategory: Boolean = false,
    val createdAt: Long
)

@Serializable
data class CategoryItem(
    val id: String,
    val name: String? = null,
    val isFolder: Boolean = false,
    // selected language for this category (one of the supported languages for the selected voice)
    val selectedLanguage: String? = null
)

@Serializable
data class Settings(
    val language: String = "en",
    val voice: String = "default",
    val speechRate: Float = 1.0f,
    // UI-level settings: primary and secondary locales used by the UI language selector
    val primaryLanguage: String = "en-US",
    val secondaryLanguage: String = "en-US",
    // TTS preference: true = use system TTS, false = use Azure TTS
    val useSystemTts: Boolean = false
)
