package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.Voice

/** Whether this voice speaks more than one language, so a second language can be chosen. */
internal fun Voice?.isMultilingual(): Boolean = this?.supportedLanguages.orEmpty().distinct().size > 1

/**
 * The second language that actually applies with [voice], or null. One rule for Typing,
 * setup's Hear your voice, and Review, so none promises a language the voice cannot speak.
 */
internal fun Settings.secondaryLanguageFor(voice: Voice?): String? =
    secondaryLanguage.takeIf {
        it.isNotBlank() && it != primaryLanguage && voice.isMultilingual() && it in voice?.supportedLanguages.orEmpty()
    }
