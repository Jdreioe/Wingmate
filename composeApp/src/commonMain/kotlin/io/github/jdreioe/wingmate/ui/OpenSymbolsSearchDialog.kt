package io.github.jdreioe.wingmate.ui

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import wingmatekmp.composeapp.generated.resources.*

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
    var searchError by remember { mutableStateOf<OpenSymbolsClient.SearchError?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    val isConfigured = OpenSymbolsClient.isConfigured()
    val notConfiguredMessage = stringResource(Res.string.opensymbols_not_configured)
    val normalizedQuery = searchQuery.trim()
    val locale = Locale.current.language

    LaunchedEffect(isConfigured, normalizedQuery, locale, retryKey) {
        if (!isConfigured) {
            isLoading = false
            results = emptyList()
            searchError = OpenSymbolsClient.SearchError.NotConfigured
            return@LaunchedEffect
        }

        if (normalizedQuery.isBlank()) {
            isLoading = false
            results = emptyList()
            searchError = null
            return@LaunchedEffect
        }

        delay(350)
        isLoading = true
        searchError = null
        when (val response = OpenSymbolsClient.search(normalizedQuery, locale)) {
            is OpenSymbolsClient.SearchResponse.Success -> results = response.symbols
            is OpenSymbolsClient.SearchResponse.Failure -> {
                results = emptyList()
                searchError = response.error
            }
        }
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
                } else if (searchError != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            when (searchError) {
                                OpenSymbolsClient.SearchError.Authentication,
                                OpenSymbolsClient.SearchError.TokenExpired ->
                                    stringResource(Res.string.opensymbols_auth_failed)
                                OpenSymbolsClient.SearchError.Throttled ->
                                    stringResource(Res.string.opensymbols_throttled)
                                else -> stringResource(Res.string.opensymbols_search_failed)
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { retryKey++ }) {
                            Text(stringResource(Res.string.opensymbols_retry))
                        }
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
    Card(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(enabled = symbol.image_url != null, onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SubcomposeAsyncImage(
                    model = symbol.image_url,
                    contentDescription = symbol.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } },
                    error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(Res.string.opensymbols_image_unavailable))
                    } }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = symbol.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}
