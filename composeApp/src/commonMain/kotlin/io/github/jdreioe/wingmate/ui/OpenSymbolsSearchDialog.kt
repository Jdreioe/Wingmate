package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URL

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
    val scope = rememberCoroutineScope()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search OpenSymbols") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 500.dp)) {
                // Search input
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search for pictogram...") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            if (searchQuery.isNotBlank()) {
                                isLoading = true
                                scope.launch {
                                    results = OpenSymbolsClient.search(searchQuery)
                                    isLoading = false
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Results grid
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (results.isEmpty() && searchQuery.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No results found", style = MaterialTheme.typography.bodyMedium)
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
                    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
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
