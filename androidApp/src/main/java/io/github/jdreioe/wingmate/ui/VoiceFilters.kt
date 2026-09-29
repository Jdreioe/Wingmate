package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.GoogleVoiceModel
import io.github.jdreioe.wingmate.domain.Voice

import com.hojmoseit.wingmate.R

// Search and filter helpers for the voice picker in Settings.

@Composable
internal fun GoogleModelFilterChips(
    models: List<GoogleVoiceModel>,
    selected: GoogleVoiceModel?,
    onSelected: (GoogleVoiceModel?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelected(null) },
            label = { Text(stringResource(R.string.voice_model_all)) },
        )
        models.forEach { model ->
            FilterChip(
                selected = selected == model,
                onClick = { onSelected(model) },
                label = { Text(googleVoiceModelLabel(model)) },
            )
        }
    }
}

@Composable
private fun googleVoiceModelLabel(model: GoogleVoiceModel): String = stringResource(
    when (model) {
        GoogleVoiceModel.GEMINI_3_1_FLASH -> R.string.voice_model_gemini_3_1_flash
        GoogleVoiceModel.GEMINI_2_5_FLASH -> R.string.voice_model_gemini_2_5_flash
        GoogleVoiceModel.GEMINI_2_5_FLASH_LITE -> R.string.voice_model_gemini_2_5_flash_lite
        GoogleVoiceModel.GEMINI_2_5_PRO -> R.string.voice_model_gemini_2_5_pro
        GoogleVoiceModel.CHIRP_3_HD -> R.string.voice_model_chirp_3_hd
        GoogleVoiceModel.STUDIO -> R.string.voice_model_studio
        GoogleVoiceModel.NEURAL2 -> R.string.voice_model_neural2
        GoogleVoiceModel.WAVENET -> R.string.voice_model_wavenet
        GoogleVoiceModel.STANDARD -> R.string.voice_model_standard
        GoogleVoiceModel.OTHER -> R.string.voice_model_other
    },
)

internal fun matchesVoiceFilters(
    voice: Voice,
    queryTerms: List<String>,
    genderFilter: String?
): Boolean {
    if (genderFilter != null && !voice.gender.equals(genderFilter, ignoreCase = true)) {
        return false
    }

    if (queryTerms.isEmpty()) {
        return true
    }

    val searchable = buildVoiceSearchText(voice)
    return queryTerms.all { term -> searchable.contains(term) }
}

internal fun buildVoiceSearchText(voice: Voice): String {
    val supported = voice.supportedLanguages ?: emptyList()
    return buildString {
        append(voice.displayName.orEmpty())
        append(' ')
        append(voice.name.orEmpty())
        append(' ')
        append(voice.primaryLanguage.orEmpty())
        voice.primaryLanguage?.let { append(' '); append(localizedLocaleDisplayName(it)) }
        append(' ')
        append(voice.gender.orEmpty())
        if (supported.isNotEmpty()) {
            append(' ')
            append(supported.joinToString(" "))
            append(' ')
            append(supported.joinToString(" ") { localizedLocaleDisplayName(it) })
        }
    }.lowercase()
}
