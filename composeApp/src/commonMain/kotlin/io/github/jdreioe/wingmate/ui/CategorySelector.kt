package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import io.github.jdreioe.wingmate.domain.CategoryItem

@Composable
fun CategorySelectorDialog(
    languageLabel: String,
    categories: List<CategoryItem>,
    selected: CategoryItem?,
    onDismiss: () -> Unit,
    onCategorySelected: (CategoryItem) -> Unit,
    onAddCategory: (CategoryItem) -> Unit,
    availableLanguages: List<String> = emptyList()
) {
    var showAdd by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Category for $languageLabel") }, text = {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Language label centered
            Text(text = languageLabel, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp).align(Alignment.CenterHorizontally))

            // Category buttons row — show each category as a button; selected one is filled
            Row(horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                for ((i, cat) in categories.withIndex()) {
                    val isSelected = selected?.id == cat.id
                    val label = if (!cat.selectedLanguage.isNullOrBlank()) "${cat.name} (${cat.selectedLanguage})" else (cat.name ?: cat.id)
                    if (isSelected) {
                        Button(onClick = { onCategorySelected(cat) }, modifier = Modifier.padding(end = 8.dp)) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(onClick = { onCategorySelected(cat) }, modifier = Modifier.padding(end = 8.dp)) {
                            Text(label)
                        }
                    }
                }
                // trailing + button immediately after last category
                IconButton(onClick = { showAdd = true }, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = "Add category")
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = onDismiss) { Text("Close") }
    }, dismissButton = {})

    if (showAdd) {
        // pass existing names so AddCategoryDialog can validate duplicates
        AddCategoryDialog(existingNames = categories.mapNotNull { it.name?.trim() }, availableLanguages = availableLanguages, onDismiss = { showAdd = false }, onSave = { c -> onAddCategory(c); showAdd = false })
    }
}
