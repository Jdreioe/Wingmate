package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hojmoseit.wingmate.R
import io.github.jdreioe.wingmate.domain.obf.SpeechScreenImport

@Composable
internal fun SpeechImportDialog(state: BoardSetManagerState, onAction: (BoardSetManagerAction) -> Unit) {
    val draft = state.speechDraft
    val previous = stringResource(R.string.speech_import_previous)
    val next = stringResource(R.string.speech_import_next)
    AlertDialog(
        onDismissRequest = { onAction(BoardSetManagerAction.SpeechImportDismissed) },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.speech_import_title))
                if (state.speechImportFailed) {
                    Text(
                        stringResource(R.string.speech_import_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.speech_import_help))
                }
                item {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { onAction(BoardSetManagerAction.SpeechNameChanged(it)) },
                        label = { Text(stringResource(R.string.speech_import_name)) },
                        enabled = !state.isCreating, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = draft.text,
                        onValueChange = { onAction(BoardSetManagerAction.SpeechTextChanged(it)) },
                        label = { Text(stringResource(R.string.speech_import_text)) },
                        enabled = !state.isCreating, minLines = 5, maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(stringResource(R.string.speech_import_count, draft.paragraphsPerPage))
                    Row {
                        TextButton(
                            onClick = { onAction(BoardSetManagerAction.SpeechCountChanged(draft.paragraphsPerPage - 1)) },
                            enabled = !state.isCreating && draft.paragraphsPerPage > 1,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.speech_import_fewer)) }
                        TextButton(
                            onClick = { onAction(BoardSetManagerAction.SpeechCountChanged(draft.paragraphsPerPage + 1)) },
                            enabled = !state.isCreating && draft.paragraphsPerPage < SpeechScreenImport.MAX_PARAGRAPHS_PER_PAGE,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.speech_import_more)) }
                    }
                    Text(stringResource(R.string.speech_import_preview, draft.pages.size))
                }
                itemsIndexed(draft.pages) { index, page ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}. ${page.name}", style = MaterialTheme.typography.titleSmall)
                        page.paragraphs.forEach { paragraph -> Text(paragraph) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(BoardSetManagerAction.SpeechImportSubmitted(previous, next)) },
                enabled = draft.canImport && !state.isCreating,
            ) {
                if (state.isCreating) CircularProgressIndicator()
                else Text(stringResource(R.string.speech_import_create))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(BoardSetManagerAction.SpeechImportDismissed) },
                enabled = !state.isCreating,
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Preview
@Composable
private fun SpeechImportPreview() {
    MaterialTheme {
        SpeechImportDialog(
            BoardSetManagerState(speechDraft = SpeechImportDraft("My speech", "Hello everyone.\n\nThank you for coming.")),
            onAction = {},
        )
    }
}
