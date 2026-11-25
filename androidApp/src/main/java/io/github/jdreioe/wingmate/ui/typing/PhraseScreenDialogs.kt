package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.CategoryItem

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TypingCategoryDialogs(
    ui: PhraseScreenState,
    categories: List<CategoryItem>,
    categoryMenu: CategoryItem?,
    showAddCategoryDialog: Boolean,
    confirmDeleteCategory: CategoryItem?,
    fontSizeScale: Float,
    onAction: (PhraseScreenAction) -> Unit,
) {
    categoryMenu?.let { menuCategory ->
        val index = categories.indexOfFirst { it.id == menuCategory.id }
        val moveCategory: (Int) -> Unit = { target ->
            onAction(PhraseScreenAction.MoveCategory(menuCategory.id, target))
        }
        val menuTextStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = MaterialTheme.typography.bodyLarge.fontSize * fontSizeScale
        )
        ModalBottomSheet(onDismissRequest = { onAction(PhraseScreenAction.DismissDialog) }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.category_move_left), style = menuTextStyle) },
                    enabled = index > 0,
                    onClick = {
                        onAction(PhraseScreenAction.DismissDialog)
                        moveCategory(index - 1)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.category_move_right), style = menuTextStyle) },
                    enabled = index in 0 until categories.lastIndex,
                    onClick = {
                        onAction(PhraseScreenAction.DismissDialog)
                        moveCategory(index + 1)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.category_delete_with_phrases), style = menuTextStyle) },
                    onClick = {
                        onAction(PhraseScreenAction.DismissDialog)
                        onAction(PhraseScreenAction.RequestDialog(TypingDialog.DeleteCategory, categoryId = menuCategory.id))
                    },
                )
            }
        }
    }

    if (showAddCategoryDialog) {
        val categoryName = ui.categoryName
        AlertDialog(
            onDismissRequest = { onAction(PhraseScreenAction.DismissDialog) },
            title = {
                Text(
                    stringResource(R.string.category_add_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = MaterialTheme.typography.titleLarge.fontSize * fontSizeScale
                    )
                )
            },
            text = {
                val showKeyboard = Modifier.showKeyboardOnFocus()
                OutlinedTextField(
                    value = categoryName,
                    isError = ui.mutationFailed,
                    supportingText = if (ui.mutationFailed) ({ Text(stringResource(R.string.typing_screen_save_failed)) }) else null,
                    onValueChange = { onAction(PhraseScreenAction.CategoryName(it)) },
                    placeholder = {
                        Text(
                            stringResource(R.string.category_name_label),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize * fontSizeScale
                            )
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.then(showKeyboard)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !ui.mutationInProgress && ui.categoryName.isNotBlank(),
                    onClick = { onAction(PhraseScreenAction.SaveCategory) }
                ) {
                    Text(
                        stringResource(R.string.common_add),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = MaterialTheme.typography.labelLarge.fontSize * fontSizeScale
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onAction(PhraseScreenAction.DismissDialog)
                    onAction(PhraseScreenAction.CategoryName(""))
                }) {
                    Text(
                        stringResource(R.string.common_cancel),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = MaterialTheme.typography.labelLarge.fontSize * fontSizeScale
                        )
                    )
                }
            }
        )
    }

    if (confirmDeleteCategory != null) {
        AlertDialog(
            onDismissRequest = { onAction(PhraseScreenAction.DismissDialog) },
            title = {
                Text(
                    stringResource(R.string.category_delete_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = MaterialTheme.typography.titleLarge.fontSize * fontSizeScale
                    )
                )
            },
            text = {
                Text(
                    stringResource(R.string.category_delete_message, confirmDeleteCategory.name.orEmpty()),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * fontSizeScale
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onAction(PhraseScreenAction.DeleteCategory) }) {
                    Text(
                        stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = MaterialTheme.typography.labelLarge.fontSize * fontSizeScale
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(PhraseScreenAction.DismissDialog) }) {
                    Text(
                        stringResource(R.string.common_cancel),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = MaterialTheme.typography.labelLarge.fontSize * fontSizeScale
                        )
                    )
                }
            }
        )
    }
}
