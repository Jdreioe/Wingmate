package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.platform.FilePicker
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_cancel
import wingmatekmp.composeapp.generated.resources.phrase_image_label
import wingmatekmp.composeapp.generated.resources.phrase_pick_file
import wingmatekmp.composeapp.generated.resources.phrase_open_symbols
import wingmatekmp.composeapp.generated.resources.phrase_record_button
import wingmatekmp.composeapp.generated.resources.phrase_select_image_title

/** Lets people choose the type of image before requesting gallery or file access. */
@Composable
internal fun ImageSourcePickerDialog(
    onDismiss: () -> Unit,
    onPhoto: (String) -> Unit,
    onSymbol: () -> Unit,
    onRecord: (() -> Unit)? = null
) {
    val koin = getKoin()
    val filePicker = koin.getOrNull<FilePicker>()
    val scope = rememberCoroutineScope()
    val pickerTitle = stringResource(Res.string.phrase_select_image_title)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.phrase_image_label)) },
        text = { Text(stringResource(Res.string.phrase_select_image_title)) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = filePicker != null,
                    onClick = {
                        scope.launch {
                            filePicker?.pickFile(pickerTitle, listOf("png", "jpg", "jpeg", "svg"))
                                ?.let { onPhoto(if (it.startsWith("http")) it else "file://$it") }
                        }
                    }
                ) { Text(stringResource(Res.string.phrase_pick_file)) }
                TextButton(onClick = onSymbol) { Text(stringResource(Res.string.phrase_open_symbols)) }
                if (onRecord != null) {
                    TextButton(onClick = onRecord) { Text(stringResource(Res.string.phrase_record_button)) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        }
    )
}
