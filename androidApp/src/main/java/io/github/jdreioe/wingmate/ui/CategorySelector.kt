package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
// Row removed; FlowRow used instead
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.ExperimentalComposeUiApi
import io.github.jdreioe.wingmate.domain.CategoryItem
import android.util.Log
import androidx.compose.foundation.layout.ExperimentalLayoutApi
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySelectorDialog(
    languageLabel: String,
    categories: List<CategoryItem>,
    selected: CategoryItem?,
    onDismiss: () -> Unit,
    onCategorySelected: (CategoryItem) -> Unit,
    onAddCategory: (CategoryItem) -> Unit,
    onUpdateCategory: ((CategoryItem) -> Unit)? = null,
    availableLanguages: List<String> = emptyList()
) {
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CategoryItem?>(null) }
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category for $languageLabel") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Language label centered
                Text(
                    text = languageLabel,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .align(Alignment.CenterHorizontally)
                )

                // Category buttons row — wrap to next line when there's no room
                FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    for (cat in categories) {
                        val isSelected = selected?.id == cat.id
                        val label = if (!cat.selectedLanguage.isNullOrBlank()) "${cat.name} (${cat.selectedLanguage})" else (cat.name ?: cat.id)
                        val gestureModifier = Modifier
                            .padding(end = 0.dp)
                            .pointerInput(cat.id) {
                                detectTapGestures(
                                    onTap = { onCategorySelected(cat) },
                                    onLongPress = {
                                        Log.i("CategorySelector", "long-press on category id='${cat.id}' name='${cat.name}'")
                                        Toast.makeText(ctx, "Edit ${cat.name}", Toast.LENGTH_SHORT).show()
                                        if (onUpdateCategory != null) editTarget = cat
                                    }
                                )
                            }

                        // Render as a non-clickable Surface; pointerInput handles taps/long-press for touch
                        Surface(
                            modifier = gestureModifier,
                            shape = MaterialTheme.shapes.small,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    text = label,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // trailing + button immediately after last category
                    IconButton(onClick = { Log.i("CategorySelector", "add (+) pressed"); showAdd = true }) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = "Add category")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {}
    )

    if (showAdd) {
        // pass existing names so AddCategoryDialog can validate duplicates
        AddCategoryDialog(
            existingNames = categories.mapNotNull { it.name?.trim() },
            availableLanguages = availableLanguages,
            onDismiss = { showAdd = false },
            onSave = { c -> onAddCategory(c); showAdd = false }
        )
    }

    // edit flow: open AddCategoryDialog prefilled for editing
    if (editTarget != null) {
        val target = editTarget!!
        AddCategoryDialog(
            existingNames = categories.mapNotNull { it.name?.trim() }.filter { it != (target.name ?: "") },
            availableLanguages = availableLanguages,
            initial = target,
            onDismiss = { editTarget = null },
            onSave = { c ->
                // keep original id when updating name; build updated item
                val updated = CategoryItem(id = target.id, name = c.name, selectedLanguage = c.selectedLanguage)
                onUpdateCategory?.invoke(updated)
                editTarget = null
            }
        )
    }
}
