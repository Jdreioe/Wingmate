package io.github.jdreioe.wingmate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import io.github.jdreioe.wingmate.infrastructure.ImageCacher
import io.github.jdreioe.wingmate.infrastructure.OpenSymbolsClient
import io.github.jdreioe.wingmate.infrastructure.SymbolSearchClient
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource

import com.hojmoseit.wingmate.R
import org.koin.compose.getKoin
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
    var results by remember { mutableStateOf<List<SymbolSearchClient.SymbolResult>>(emptyList()) }
    var packageFilter by remember { mutableStateOf(SymbolSearchClient.Package.All) }
    var isLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<OpenSymbolsClient.SearchError?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var prioritizeArasaac by remember { mutableStateOf(false) }
    val koin = getKoin()
    val imageCacher = remember(koin) { koin.getOrNull<ImageCacher>() }
    val notConfiguredMessage = stringResource(R.string.opensymbols_not_configured)
    val normalizedQuery = searchQuery.trim()
    val locale = Locale.current.language

    LaunchedEffect(imageCacher) {
        prioritizeArasaac = runCatching {
            (imageCacher?.cachedArasaacSymbolCount() ?: 0) > 0
        }.getOrDefault(false)
    }

    LaunchedEffect(normalizedQuery, locale, packageFilter, prioritizeArasaac, retryKey) {

        if (normalizedQuery.isBlank()) {
            isLoading = false
            results = emptyList()
            searchError = null
            return@LaunchedEffect
        }

        delay(350)
        isLoading = true
        searchError = null
        when (
            val response = SymbolSearchClient.search(
                query = normalizedQuery,
                locale = locale,
                packageFilter = packageFilter,
                prioritizeArasaac = prioritizeArasaac,
            )
        ) {
            is SymbolSearchClient.SearchResponse.Success -> results = response.symbols
            is SymbolSearchClient.SearchResponse.Failure -> {
                results = emptyList()
                searchError = response.error
            }
        }
        isLoading = false
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.opensymbols_search_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 500.dp)) {
                // Search input
                val showKeyboard = Modifier.showKeyboardOnFocus()
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.opensymbols_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().then(showKeyboard)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SymbolSearchClient.Package.entries.forEach { option ->
                        FilterChip(
                            selected = packageFilter == option,
                            onClick = { packageFilter = option },
                            label = {
                                Text(
                                    when (option) {
                                        SymbolSearchClient.Package.All -> stringResource(R.string.symbol_package_all)
                                        SymbolSearchClient.Package.OpenSymbols -> "OpenSymbols"
                                        SymbolSearchClient.Package.Mulberry -> "Mulberry"
                                        SymbolSearchClient.Package.Arasaac -> "ARASAAC"
                                    }
                                )
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                // Results grid
                if (isLoading) {
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
                                OpenSymbolsClient.SearchError.Throttled ->
                                    stringResource(R.string.opensymbols_throttled)
                                OpenSymbolsClient.SearchError.NotConfigured -> notConfiguredMessage
                                else -> stringResource(R.string.opensymbols_search_failed)
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { retryKey++ }) {
                            Text(stringResource(R.string.opensymbols_retry))
                        }
                    }
                } else if (results.isEmpty() && normalizedQuery.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.opensymbols_no_results), style = MaterialTheme.typography.bodyMedium)
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
                                    symbol.imageUrl?.let { url ->
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun SymbolGridItem(
    symbol: SymbolSearchClient.SymbolResult,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(enabled = symbol.imageUrl != null, onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SubcomposeAsyncImage(
                    model = symbol.imageUrl,
                    contentDescription = symbol.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } },
                    error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.opensymbols_image_unavailable))
                    } }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = symbol.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
            Text(
                text = when (symbol.source) {
                    SymbolSearchClient.Source.OpenSymbols -> "OpenSymbols"
                    SymbolSearchClient.Source.Mulberry -> "Mulberry"
                    SymbolSearchClient.Source.Arasaac -> "ARASAAC"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
