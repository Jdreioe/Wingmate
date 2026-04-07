package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.domain.CategoryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCategoryDialog(existingNames: List<String> = emptyList(), availableLanguages: List<String> = emptyList(), onDismiss: () -> Unit, onSave: (CategoryItem) -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf(availableLanguages.firstOrNull() ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add category") }, text = {
        Column(modifier = Modifier.fillMaxWidth()) {
            val showKeyboard = rememberShowKeyboardOnFocus()
            OutlinedTextField(value = name, onValueChange = {
                name = it
                error = null
            }, label = { Text("Category name") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).then(showKeyboard))
            if (availableLanguages.isNotEmpty()) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    TextField(value = selectedLanguage, onValueChange = {}, readOnly = true, label = { Text("Language") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) })
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = {}) {}
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        for (lang in availableLanguages) {
                            androidx.compose.material3.TextButton(onClick = { selectedLanguage = lang; expanded = false }) { Text(lang) }
                        }
                    }
                }
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
