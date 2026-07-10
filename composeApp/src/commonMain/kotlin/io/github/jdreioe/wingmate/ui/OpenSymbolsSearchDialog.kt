package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.net.URL
import wingmatekmp.composeapp.generated.resources.Res
import wingmatekmp.composeapp.generated.resources.common_cancel
import wingmatekmp.composeapp.generated.resources.opensymbols_no_results
import wingmatekmp.composeapp.generated.resources.opensymbols_not_configured
import wingmatekmp.composeapp.generated.resources.opensymbols_search_label
import wingmatekmp.composeapp.generated.resources.opensymbols_search_title

/**
 * Dialog to search OpenSymbols for pictograms.
 * Returns the selected image URL on pick.
 */
@Composable
fun OpenSymbolsSearchDialog(
    onDismiss: () -> Unit,
    onSelect: (imageUrl: String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OpenSymbolsClient.SymbolResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val isConfigured = OpenSymbolsClient.isConfigured()
    val notConfiguredMessage = stringResource(Res.string.opensymbols_not_configured)
    val normalizedQuery = searchQuery.trim()

    LaunchedEffect(isConfigured, normalizedQuery) {
        if (!isConfigured) {
            isLoading = false
            results = emptyList()
            return@LaunchedEffect
        }

        if (normalizedQuery.isBlank()) {
            isLoading = false
            results = emptyList()
            return@LaunchedEffect
        }

        isLoading = true
        results = OpenSymbolsClient.search(normalizedQuery)
        isLoading = false
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.opensymbols_search_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 500.dp)) {
                // Search input
                val showKeyboard = rememberShowKeyboardOnFocus()
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(Res.string.opensymbols_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().then(showKeyboard)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Results grid
                if (!isConfigured) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            notConfiguredMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (results.isEmpty() && normalizedQuery.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(Res.string.opensymbols_no_results), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(results.take(30)) { symbol ->
                            SymbolGridItem(
                                symbol = symbol,
                                onClick = {
                                    symbol.image_url?.let { url ->
                                        onSelect(url)
                                        onDismiss()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        }
    )
}

@Composable
private fun SymbolGridItem(
    symbol: OpenSymbolsClient.SymbolResult,
    onClick: () -> Unit
) {
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    
    LaunchedEffect(symbol.image_url) {
        symbol.image_url?.let { url ->
            imageBitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = URL(url).readBytes()
                    bytes.toComposeImageBitmap()
                }.getOrNull()
            }
        }
    }
    
    Card(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap!!,
                    contentDescription = symbol.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = symbol.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}
