package io.github.jdreioe.wingmate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.*
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.application.FeatureUsageEvents
import io.github.jdreioe.wingmate.application.EditingAccessController
import io.github.jdreioe.wingmate.domain.CommunicationPlaybackStatus
import io.github.jdreioe.wingmate.domain.Message
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.TextPredictionService
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.obf.ObfButtonActionEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import org.koin.compose.getKoin

import com.hojmoseit.wingmate.BuildConfig
import com.hojmoseit.wingmate.R
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.jdreioe.wingmate.domain.Settings
import kotlin.math.roundToInt

@Preview
@Composable
internal fun PhraseScreenContentPreview() {
    AppTheme {
        PhraseScreenContent(
            ui = PhraseScreenState(
                input = TextFieldValue("I would like some water"),
                phrases = listOf(
                    Phrase(id = "water", text = "Water", createdAt = 0),
                    Phrase(id = "help", text = "Please help", createdAt = 0),
                ),
                phraseStatus = TypingLoadStatus.Ready,
                template = io.github.jdreioe.wingmate.application.TypingScreenProjector.defaultTemplate(4, now = 0),
                templateStatus = TypingLoadStatus.Ready,
                categoryStatus = TypingLoadStatus.Ready,
            ),
            onAction = {},
        )
    }
}

@Composable
internal fun SecondaryLanguageTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    highlightRanges: List<TextRange> = emptyList(),
    highlightColor: Color,
    ssmlRanges: List<TextRange> = emptyList(),
    ssmlColor: Color = MaterialTheme.colorScheme.primaryContainer,
    textStyle: TextStyle,
    placeholder: (@Composable () -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
) {
    val annotated: AnnotatedString = remember(value.text, highlightRanges, highlightColor, ssmlRanges, ssmlColor) {
        buildAnnotatedString {
            append(value.text)
            highlightRanges.sortedBy { it.start }.forEach { range ->
                val start = range.start.coerceIn(0, value.text.length)
                val end = range.end.coerceIn(0, value.text.length)
                if (start < end) {
                    addStyle(SpanStyle(background = highlightColor), start, end)
                }
            }
            ssmlRanges.sortedBy { it.start }.forEach { range ->
                val start = range.start.coerceIn(0, value.text.length)
                val end = range.end.coerceIn(0, value.text.length)
                if (start < end) {
                    addStyle(SpanStyle(background = ssmlColor, fontWeight = FontWeight.Bold), start, end)
                }
            }
        }
    }

    // Wrap the plain TextFieldValue with our annotated string for display
    val styledValue = value.copy(annotatedString = annotated)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val inputModifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .showKeyboardOnFocus()
            BasicTextField(
                value = styledValue,
                onValueChange = {
                    // Pass the plain text back to the parent to keep the logic simple there
                    onValueChange(it.copy(annotatedString = AnnotatedString(it.text)))
                },
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = inputModifier,
                minLines = minLines,
                maxLines = maxLines,
                // The hint sits inside the field so a screen reader names the edit box with it.
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) placeholder?.invoke()
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
internal fun RepositoryFailurePanel(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.typing_screen_load_failed),
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.common_retry))
        }
    }
}

/** The top bar's actions, shared by the phone top bar and the wide layout's side panel. */
@Composable
internal fun TypingAppBarActions(
    mathModeSupported: Boolean,
    mathMode: Boolean,
    onMathModeChange: (Boolean) -> Unit,
    onOpenScreens: () -> Unit,
    screensEnabled: Boolean,
    showFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (mathModeSupported) {
        IconToggleButton(
            checked = mathMode,
            onCheckedChange = onMathModeChange,
        ) {
            Icon(
                imageVector = Icons.Filled.Calculate,
                contentDescription = stringResource(R.string.speech_math_mode_description),
                tint = if (mathMode) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
    IconButton(
        onClick = onOpenScreens,
        enabled = screensEnabled
    ) {
        Icon(
            imageVector = Icons.Filled.GridView,
            contentDescription = stringResource(R.string.mode_switch_to_screens)
        )
    }

    IconButton(onClick = onToggleFullscreen) {
        Icon(
            imageVector = if (showFullscreen) {
                Icons.Filled.FullscreenExit
            } else {
                Icons.Filled.Fullscreen
            },
            contentDescription = stringResource(R.string.phrase_screen_toggle_fullscreen_cd)
        )
    }

    IconButton(onClick = onOpenSettings) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.phrase_screen_app_settings)
        )
    }
}
