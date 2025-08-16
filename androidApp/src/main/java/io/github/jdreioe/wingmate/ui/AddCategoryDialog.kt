package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.CategoryItem
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddCategoryDialog(existingNames: List<String> = emptyList(), availableLanguages: List<String> = emptyList(), initial: CategoryItem? = null, onDismiss: () -> Unit, onSave: (CategoryItem) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var showLangDialog by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf(initial?.selectedLanguage ?: availableLanguages.firstOrNull() ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add category") }, text = {
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = name, onValueChange = {
                name = it
                error = null
            }, label = { Text("Category name") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            if (availableLanguages.isNotEmpty()) {
                // show a read-only field that opens a language selection dialog (mirrors desktop)
                TextField(
                    value = selectedLanguage,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Language") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable { showLangDialog = true }
                )
                LanguageSelectionDialog(show = showLangDialog, languages = availableLanguages, selected = selectedLanguage, onDismiss = { showLangDialog = false }, onSelect = { sel -> selectedLanguage = sel })
            }
            if (error != null) Text(error ?: "", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Button(onClick = {
            val trimmed = name.trim()
            if (trimmed.isBlank()) {
                error = "Name cannot be empty"
                return@Button
            }
            if (existingNames.any { it.equals(trimmed, ignoreCase = true) }) {
                error = "Category with this name already exists"
                return@Button
            }
            val id = trimmed.replace("\\s+".toRegex(), "_")
            onSave(CategoryItem(id = id, name = trimmed, selectedLanguage = selectedLanguage.ifEmpty { null }))
            onDismiss()
        }) { Text("Save") }
    }, dismissButton = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
    })
}
