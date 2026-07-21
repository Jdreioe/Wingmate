package io.github.jdreioe.wingmate.domain.obf

/**
 * Resolved image source following the OBF priority order:
 * **data → path → url → symbol**.
 */
sealed class ObfImageSource {
    data class DataUri(val data: String) : ObfImageSource()
    data class Path(val path: String) : ObfImageSource()
    data class Url(val url: String) : ObfImageSource()
    data class Symbol(val symbol: ObfSymbol) : ObfImageSource()
    data object None : ObfImageSource()
}

/**
 * Pick the highest-priority non-blank image reference on [image].
 */
fun resolveObfImageSource(image: ObfImage?): ObfImageSource {
    if (image == null) return ObfImageSource.None
    // Spec priority: data → path → url → symbol. data_url is a standard remote data endpoint
    // and is treated as a data-class reference ahead of path/url when inline data is absent.
    val data = image.data?.takeIf { it.isNotBlank() }
        ?: image.dataUrl?.takeIf { it.isNotBlank() }
    if (data != null) return ObfImageSource.DataUri(data)
    val path = image.path?.takeIf { it.isNotBlank() }
    if (path != null) return ObfImageSource.Path(path)
    val url = image.url?.takeIf { it.isNotBlank() }
    if (url != null) return ObfImageSource.Url(url)
    val symbol = image.symbol
    if (symbol != null && (!symbol.set.isNullOrBlank() || !symbol.filename.isNullOrBlank() || !symbol.libraryKey.isNullOrBlank())) {
        return ObfImageSource.Symbol(symbol)
    }
    return ObfImageSource.None
}
