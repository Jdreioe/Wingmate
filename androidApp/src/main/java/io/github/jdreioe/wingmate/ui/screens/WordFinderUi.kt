package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.application.IosWordFinderMatch
import io.github.jdreioe.wingmate.domain.obf.WordFinderGuidance

/**
 * Search dialog for locating any word across the active Screen's Pages and starting
 * step-by-step guided navigation to it without modifying vocabulary (#408).
 */
@Composable
internal fun WordFinderDialog(
    search: (String) -> List<IosWordFinderMatch>,
    onSelectMatch: (IosWordFinderMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, search) { search(query) }
    val focusRequester = remember { FocusRequester() }
    val hiddenLabel = stringResource(R.string.word_finder_hidden_badge)
    val fromStartLabel = stringResource(R.string.word_finder_from_start_page)

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.word_finder_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("word-finder-dialog"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("word-finder-query"),
                    placeholder = { Text(stringResource(R.string.word_finder_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.word_finder_clear_search),
                                )
                            }
                        }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            results.firstOrNull()?.let(onSelectMatch)
                        }
                    ),
                )

                when {
                    query.isBlank() -> {
                        Text(
                            text = stringResource(R.string.word_finder_prompt),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    results.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.word_finder_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("word-finder-empty"),
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .testTag("word-finder-results"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(
                                items = results,
                                key = { "${it.targetBoardId}:${it.buttonId}:${it.formattedPath}" },
                            ) { match ->
                                val showHiddenBadge = match.isHidden || match.steps.any { it.isHidden }
                                val rowDescription = buildString {
                                    append(match.label)
                                    append(", ")
                                    append(match.formattedPath)
                                    if (showHiddenBadge) {
                                        append(", ")
                                        append(hiddenLabel)
                                    }
                                    if (match.startsFromRoot) {
                                        append(", ")
                                        append(fromStartLabel)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = rowDescription
                                        }
                                        .clickable(role = Role.Button) { onSelectMatch(match) }
                                        .testTag("word-finder-result-${match.buttonId}"),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = match.label,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (showHiddenBadge) {
                                                WordFinderBadge(
                                                    label = hiddenLabel,
                                                    icon = Icons.Default.VisibilityOff,
                                                )
                                            }
                                            if (match.startsFromRoot) {
                                                WordFinderBadge(
                                                    label = fromStartLabel,
                                                    icon = Icons.Default.Home,
                                                )
                                            }
                                        }
                                        if (!match.vocalization.isNullOrBlank()) {
                                            Text(
                                                text = match.vocalization.orEmpty(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            text = match.formattedPath,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
private fun WordFinderBadge(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
    }
}

/**
 * Compact banner shown above the Page grid while step-by-step Word Finder guidance is active.
 * Keeps touch, switch scanning, dwell, and TalkBack controls accessible at every step.
 */
@Composable
internal fun WordFinderGuidanceBanner(
    guidance: WordFinderGuidance,
    activeBoardId: String?,
    selectOnTouchDown: Boolean = false,
    onGoToStep: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = guidance.currentStep ?: return
    val isOnStepPage = activeBoardId == step.boardId
    val findingLabel = stringResource(R.string.word_finder_finding, guidance.match.label)
    val stepInstruction = stringResource(
        R.string.word_finder_step_instruction,
        guidance.currentStepIndex + 1,
        guidance.totalSteps,
        step.buttonLabel,
    )
    val stopDescription = stringResource(R.string.word_finder_stop_description)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("word-finder-guidance-banner"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Explore,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "$findingLabel · ${guidance.match.formattedPath()}",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stepInstruction,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isOnStepPage) {
                val goToStepShape = RoundedCornerShape(50)
                FilledTonalButton(
                    onClick = { onGoToStep(step.boardId) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .typingAccessTarget(
                            targetId = "screens:word-finder-go-to-step",
                            enabled = true,
                            shape = goToStepShape,
                            action = { onGoToStep(step.boardId) },
                        )
                        .selectOnTouchDown(
                            enabled = selectOnTouchDown,
                            onSelect = { onGoToStep(step.boardId) },
                        )
                        .testTag("word-finder-go-to-step"),
                ) {
                    Text(stringResource(R.string.word_finder_go_to_step))
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            val stopShape = RoundedCornerShape(50)
            TextButton(
                onClick = onStop,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .semantics { contentDescription = stopDescription }
                    .typingAccessTarget(
                        targetId = "screens:word-finder-stop",
                        enabled = true,
                        shape = stopShape,
                        action = onStop,
                    )
                    .selectOnTouchDown(
                        enabled = selectOnTouchDown,
                        onSelect = onStop,
                    )
                    .testTag("word-finder-stop"),
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.word_finder_stop))
            }
        }
    }
}
